package com.urik.keyboard.data.database

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.urik.keyboard.utils.ErrorLogger
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the security manager could make of the stored passphrase — the ONE verdict `DatabaseModule` acts on.
 * The distinction that matters is between [Unavailable] (nothing is known to be lost; try again next start)
 * and [KeyGone] (it will never decrypt again): the first must not touch the file, the second may set it aside.
 */
sealed class PassphraseResult {
    /** Decrypted and ready; the caller zeroes it after use. */
    class Available(val passphrase: ByteArray) : PassphraseResult()

    /** Nothing stored and no lock screen: upstream's unencrypted mode for lock-screen-less devices. */
    object NoEncryption : PassphraseResult()

    /**
     * A passphrase IS stored but could not be decrypted right now — the keystore refused, threw, or is not
     * there yet. The next process start may well succeed.
     */
    class Unavailable(val cause: Throwable?) : PassphraseResult()

    /**
     * A passphrase is stored but can never be decrypted again: the master key is missing from the keystore (a
     * data directory cloned or restored without it) or the ciphertext no longer matches it.
     */
    class KeyGone(val why: String) : PassphraseResult()
}

/**
 * Architecture:
 * - Master key (AES-256) stored in hardware security module
 * - Database passphrase (32 bytes) encrypted by master key, ciphertext in device-protected prefs
 * - Device lock screen required to START encrypting; an encrypted database is decrypted regardless (the
 *   user removing their lock screen must not lock them out of their own words)
 *
 * The master key is deliberately NOT bound to the device being unlocked (`setUnlockedDeviceRequired`): the
 * keyboard's process is created while the screen is locked as a matter of course — the lock-screen password
 * field, a sister app's backup broadcast, a scheduled batch — and a key that refuses to work at those moments
 * costs a whole session's learning, or (before 2026-09-11) the whole database. Keys made by earlier builds
 * with that flag are re-wrapped under [MASTER_KEY_ALIAS] the first time they decrypt successfully.
 */
class DatabaseSecurityManager(
    private val context: Context,
    @get:VisibleForTesting internal val lockScreenCheck: () -> Boolean = { defaultLockScreenCheck(context) }
) {
    private val passphraseKey = "encrypted_passphrase"
    private val ivKey = "encryption_iv"
    private val aliasKey = "master_key_alias"
    private val strikesKey = "passphrase_strikes"

    private val prefs: SharedPreferences = deviceProtectedPrefs(context)

    /** Without a lock screen, Keystore keys are vulnerable to offline attacks. */
    fun hasDeviceLockScreen(): Boolean = lockScreenCheck()

    /** Whether an encrypted passphrase is on record — i.e. the database file, if any, is expected encrypted. */
    fun hasStoredPassphrase(): Boolean = prefs.contains(passphraseKey) && prefs.contains(ivKey)

    /** The keystore alias the stored passphrase is wrapped under (legacy builds used [LEGACY_MASTER_KEY_ALIAS]). */
    @VisibleForTesting
    internal fun storedAlias(): String = prefs.getString(aliasKey, LEGACY_MASTER_KEY_ALIAS) ?: LEGACY_MASTER_KEY_ALIAS

    /** Resolve the SQLCipher passphrase; see [PassphraseResult] for the four verdicts. Never throws. */
    fun resolvePassphrase(): PassphraseResult {
        if (!hasStoredPassphrase()) {
            if (!hasDeviceLockScreen()) {
                ErrorLogger.logException(
                    component = TAG,
                    severity = ErrorLogger.Severity.HIGH,
                    exception = IllegalStateException("Device has no lock screen; database encryption unavailable"),
                    context = mapOf("condition" to "no_lock_screen")
                )
                return PassphraseResult.NoEncryption
            }
            return try {
                PassphraseResult.Available(generateAndStorePassphrase())
            } catch (e: Exception) {
                log("generateAndStorePassphrase", e)
                PassphraseResult.Unavailable(e)
            }
        }

        val alias = storedAlias()
        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (e: Exception) {
            log("keystore_load", e)
            return PassphraseResult.Unavailable(e)
        }
        val present = try {
            keyStore.containsAlias(alias)
        } catch (e: Exception) {
            log("containsAlias", e)
            return PassphraseResult.Unavailable(e)
        }
        if (!present) {
            // A clean "no such key" is permanent: the ciphertext in prefs can never be opened again. (A keystore
            // that is merely unwell throws, and lands in Unavailable above.)
            log("master_key_missing", IllegalStateException("master key '$alias' missing from the keystore"))
            return PassphraseResult.KeyGone("master key missing from the keystore")
        }
        return try {
            val key = keyStore.getKey(alias, null) as SecretKey
            val passphrase = decrypt(key)
            if (alias == LEGACY_MASTER_KEY_ALIAS) rewrapUnderCurrentAlias(keyStore, passphrase)
            PassphraseResult.Available(passphrase)
        } catch (e: AEADBadTagException) {
            log("retrieveStoredPassphrase", e)
            PassphraseResult.KeyGone("stored passphrase does not match the master key")
        } catch (e: KeyPermanentlyInvalidatedException) {
            log("retrieveStoredPassphrase", e)
            PassphraseResult.KeyGone("master key permanently invalidated")
        } catch (e: Exception) {
            log("retrieveStoredPassphrase", e)
            PassphraseResult.Unavailable(e)
        }
    }

    /**
     * Forget the stored passphrase (and the strike count) so the next [resolvePassphrase] starts a fresh
     * one. Only ever called AFTER the database it belonged to has been set aside.
     */
    fun forgetStoredPassphrase() {
        prefs.edit().remove(passphraseKey).remove(ivKey).remove(aliasKey).remove(strikesKey).commit()
    }

    // ---- consecutive-failure strikes (device-protected, survive the process) ---------------------------

    /** How many process starts in a row ended in [PassphraseResult.Unavailable] while the device was unlocked. */
    fun strikes(): Int = prefs.getInt(strikesKey, 0)

    fun recordStrike(): Int {
        val n = strikes() + 1
        prefs.edit().putInt(strikesKey, n).commit()
        return n
    }

    fun clearStrikes() {
        if (prefs.contains(strikesKey)) prefs.edit().remove(strikesKey).commit()
    }

    // ---- key material --------------------------------------------------------------------------------

    private fun generateAndStorePassphrase(): ByteArray {
        val secretKey = generateMasterKey(MASTER_KEY_ALIAS)
        val passphrase = ByteArray(PASSPHRASE_BYTES)
        secureRandom.nextBytes(passphrase)
        storeEncrypted(passphrase, secretKey, MASTER_KEY_ALIAS)
        return passphrase
    }

    private fun decrypt(key: SecretKey): ByteArray {
        val encryptedPassphrase = Base64.decode(prefs.getString(passphraseKey, null), Base64.NO_WRAP)
        val iv = Base64.decode(prefs.getString(ivKey, null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encryptedPassphrase)
    }

    private fun storeEncrypted(passphrase: ByteArray, key: SecretKey, alias: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(passphrase)
        val iv = cipher.iv
        prefs.edit()
            .putString(passphraseKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(ivKey, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(aliasKey, alias)
            .commit()
    }

    /**
     * Move a passphrase wrapped by a legacy, unlocked-device-bound key under a fresh key without that binding.
     * The new prefs are committed as one write before the old key is dropped, so a death in between leaves
     * either the old, still-working state or the new one — never neither. Any failure is logged and the
     * legacy key keeps serving.
     */
    private fun rewrapUnderCurrentAlias(keyStore: KeyStore, passphrase: ByteArray) {
        try {
            storeEncrypted(passphrase, generateMasterKey(MASTER_KEY_ALIAS), MASTER_KEY_ALIAS)
            runCatching { keyStore.deleteEntry(LEGACY_MASTER_KEY_ALIAS) }
        } catch (e: Exception) {
            log("rewrap", e, ErrorLogger.Severity.HIGH)
        }
    }

    /**
     * Generates an AES-256 master key in the Android Keystore.
     *
     * setUserAuthenticationRequired(false): the keyboard decrypts in the background with no UI to prompt.
     * No setUnlockedDeviceRequired either — see the class comment.
     */
    private fun generateMasterKey(alias: String): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec
            .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun log(operation: String, e: Exception, severity: ErrorLogger.Severity = ErrorLogger.Severity.CRITICAL) {
        ErrorLogger.logException(
            component = TAG,
            severity = severity,
            exception = e,
            context = mapOf("operation" to operation)
        )
    }

    companion object {
        private const val TAG = "DatabaseSecurityManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_BITS = 256
        private const val PASSPHRASE_BYTES = 32

        /** Keys made before 2026-09-11 (unlocked-device-bound); re-wrapped on first successful use. */
        const val LEGACY_MASTER_KEY_ALIAS = "urik_database_master_key"

        /** Keys made since. */
        const val MASTER_KEY_ALIAS = "kxkb_database_master_key"

        private val secureRandom = java.security.SecureRandom()

        private fun deviceProtectedPrefs(context: Context): SharedPreferences {
            val deContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            val dePrefs = deContext.getSharedPreferences("urik_db_prefs", Context.MODE_PRIVATE)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
                !dePrefs.contains("encrypted_passphrase")
            ) {
                val cePrefs = context.getSharedPreferences("urik_db_prefs", Context.MODE_PRIVATE)
                val encPassphrase = cePrefs.getString("encrypted_passphrase", null)
                val iv = cePrefs.getString("encryption_iv", null)
                if (encPassphrase != null && iv != null) {
                    dePrefs.edit()
                        .putString("encrypted_passphrase", encPassphrase)
                        .putString("encryption_iv", iv)
                        .commit()
                    cePrefs.edit()
                        .remove("encrypted_passphrase")
                        .remove("encryption_iv")
                        .commit()
                }
            }

            return dePrefs
        }

        private fun defaultLockScreenCheck(context: Context): Boolean {
            val keyguardManager =
                context.getSystemService(Context.KEYGUARD_SERVICE)
                    as? android.app.KeyguardManager
            return keyguardManager?.isDeviceSecure ?: false
        }
    }
}
