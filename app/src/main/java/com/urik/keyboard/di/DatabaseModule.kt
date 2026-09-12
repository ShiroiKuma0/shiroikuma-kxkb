package com.urik.keyboard.di

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import com.urik.keyboard.utils.isDeviceLocked
import com.urik.keyboard.utils.isUserUnlocked
import com.urik.keyboard.data.database.ClipboardDao
import com.urik.keyboard.data.database.CustomKeyMappingDao
import com.urik.keyboard.data.database.DatabaseAvailability
import com.urik.keyboard.data.database.DatabaseFiles
import com.urik.keyboard.data.database.DatabaseSecurityManager
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.data.database.PassphraseResult
import com.urik.keyboard.data.database.UserDictionaryDao
import com.urik.keyboard.data.database.UserKanjiFrequencyDao
import com.urik.keyboard.data.database.UserWordBigramDao
import com.urik.keyboard.data.database.UserWordFrequencyDao
import com.urik.keyboard.utils.ErrorLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides database dependencies with SQLCipher encryption.
 *
 * Database lifecycle:
 * - First launch: creates an encrypted database (if the device has a lock screen)
 * - Upgrade from unencrypted: brought under encryption once the probe has shown the file to be plain
 * - No lock screen: falls back to an unencrypted database
 * - Before first unlock, or with the passphrase undecryptable right now: an in-memory stand-in, flagged in
 *   [DatabaseAvailability] — the file is not touched
 * - Unreadable for good (master key gone, corrupt): the file is SET ASIDE, never deleted, and a fresh one made
 *
 * Every verdict is reached by an EAGER probe ([DatabaseFiles.probe]) before Room is handed the file. Room opens
 * lazily, so a try/catch around the builder — what this module used to rely on — sees nothing, and the library's
 * default corruption handling then decides for us: it deletes. That is how a keyboard whose process started while
 * the screen was locked lost every learned word on 2026-09-03.
 *
 * Singleton providers ensure single database instance per app lifecycle.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private val defaultOpener = object : DatabaseOpener {
        override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase =
            KeyboardDatabase.getInstance(context, passphrase)
    }

    /**
     * How many process starts in a row may end with the passphrase [PassphraseResult.Unavailable] while the
     * device is UNLOCKED before the state is treated as permanent and the database set aside. Starts while the
     * device is locked never count.
     */
    const val MAX_UNLOCKED_STRIKES = 5

    @Volatile
    @VisibleForTesting
    internal var opener: DatabaseOpener = defaultOpener

    /** The eager file check, swapped by the tests (a real probe needs SQLCipher's native library). */
    private val defaultProber: (Context, ByteArray?) -> DatabaseFiles.Probe = DatabaseFiles::probe

    @Volatile
    @VisibleForTesting
    internal var prober: (Context, ByteArray?) -> DatabaseFiles.Probe = defaultProber

    /** The key change (`sqlcipher_export` into a fresh file), swapped by the tests for the same reason. */
    private val defaultRekeyer: (Context, ByteArray?, ByteArray) -> Boolean = DatabaseFiles::reencrypt

    @Volatile
    @VisibleForTesting
    internal var rekeyer: (Context, ByteArray?, ByteArray) -> Boolean = defaultRekeyer

    @VisibleForTesting
    internal fun setOpenerForTesting(testOpener: DatabaseOpener) {
        opener = testOpener
    }

    @VisibleForTesting
    internal fun resetOpenerForTesting() {
        opener = defaultOpener
    }

    @VisibleForTesting
    internal fun setProberForTesting(testProber: (Context, ByteArray?) -> DatabaseFiles.Probe) {
        prober = testProber
    }

    @VisibleForTesting
    internal fun resetProberForTesting() {
        prober = defaultProber
        rekeyer = defaultRekeyer
    }

    @VisibleForTesting
    internal fun setRekeyerForTesting(testRekeyer: (Context, ByteArray?, ByteArray) -> Boolean) {
        rekeyer = testRekeyer
    }

    @Provides
    @Singleton
    fun provideDatabaseSecurityManager(@ApplicationContext context: Context): DatabaseSecurityManager =
        DatabaseSecurityManager(context)

    @Provides
    @Singleton
    fun provideKeyboardDatabase(
        @ApplicationContext context: Context,
        securityManager: DatabaseSecurityManager
    ): KeyboardDatabase {
        // BFU / Direct Boot: credential-protected storage and the keystore are unavailable, and Hilt injects
        // this at the IME's onCreate — BEFORE any try/catch of ours — so it must NEVER throw on the lock screen
        // (that could brick PIN entry). Return a throwaway in-memory DB; the keyboard runs no-prediction in BFU
        // and restarts itself on unlock.
        if (!context.isUserUnlocked) {
            return standIn(context, DatabaseAvailability.Mode.BEFORE_FIRST_UNLOCK, "before first unlock")
        }
        return openRealDatabase(context, securityManager)
    }

    /** The unlocked path, one verdict per [PassphraseResult]. Internal so the tests can drive it directly. */
    internal fun openRealDatabase(context: Context, securityManager: DatabaseSecurityManager): KeyboardDatabase =
        when (val verdict = securityManager.resolvePassphrase()) {
            is PassphraseResult.Available ->
                try {
                    openEncrypted(context, securityManager, verdict.passphrase)
                } finally {
                    verdict.passphrase.fill(0)
                }

            PassphraseResult.NoEncryption -> openPlain(context, securityManager)

            is PassphraseResult.Unavailable -> onUnavailable(context, securityManager, verdict)

            is PassphraseResult.KeyGone -> startOver(context, securityManager, verdict.why)
        }

    private fun openEncrypted(
        context: Context,
        securityManager: DatabaseSecurityManager,
        passphrase: ByteArray
    ): KeyboardDatabase {
        var key = passphrase
        when (prober(context, passphrase)) {
            DatabaseFiles.Probe.OK, DatabaseFiles.Probe.ABSENT -> Unit

            DatabaseFiles.Probe.NOT_A_DATABASE -> {
                // Not readable with our key. Two files are expected here and brought under the real key: one
                // keyed with the 32 zero bytes every build before +324 actually used (see DatabaseFiles), and a
                // PLAIN one — the pre-encryption upgrade, or what an old build's crash recovery left behind after
                // it wiped the encrypted file. Anything else is set aside, never deleted. Should the re-encryption
                // itself fail, the zero-keyed file is opened as it is rather than lost.
                val zero = DatabaseFiles.LEGACY_ZERO_KEY
                if (prober(context, zero) == DatabaseFiles.Probe.OK) {
                    if (rekeyer(context, zero, passphrase)) {
                        logPhase("legacy_zero_key_reencrypted", ErrorLogger.Severity.HIGH)
                    } else {
                        logPhase("legacy_zero_key_kept", ErrorLogger.Severity.CRITICAL)
                        key = zero
                    }
                } else if (prober(context, null) == DatabaseFiles.Probe.OK && rekeyer(context, null, passphrase)) {
                    logPhase("plain_database_encrypted", ErrorLogger.Severity.HIGH)
                } else if (!setAside(context, "unreadable")) {
                    return standInUnmoved(context, "unreadable")
                }
            }

            DatabaseFiles.Probe.CORRUPT -> if (!setAside(context, "corrupt")) return standInUnmoved(context, "corrupt")

            // I/O, permissions, locking: nothing is known about the content, so nothing is done to the file.
            // Counted like an undecryptable passphrase — persistent trouble escalates the same way.
            DatabaseFiles.Probe.ERROR ->
                return onUnavailable(context, securityManager, PassphraseResult.Unavailable(null))
        }
        securityManager.clearStrikes()
        DatabaseAvailability.real()
        return opener.open(context, key)
    }

    private fun openPlain(context: Context, securityManager: DatabaseSecurityManager): KeyboardDatabase {
        when (prober(context, null)) {
            DatabaseFiles.Probe.OK, DatabaseFiles.Probe.ABSENT -> Unit

            // An encrypted file with no passphrase on record cannot be opened by anyone; keep it, start fresh.
            DatabaseFiles.Probe.NOT_A_DATABASE ->
                if (!setAside(context, "unreadable")) return standInUnmoved(context, "unreadable")

            DatabaseFiles.Probe.CORRUPT -> if (!setAside(context, "corrupt")) return standInUnmoved(context, "corrupt")

            DatabaseFiles.Probe.ERROR ->
                return onUnavailable(context, securityManager, PassphraseResult.Unavailable(null))
        }
        securityManager.clearStrikes()
        DatabaseAvailability.real()
        return opener.open(context, null)
    }

    /**
     * The passphrase exists but did not decrypt (or the file did not open) THIS time. The file is left exactly as
     * it is and the keyboard runs on the stand-in; the IME ends the process at the next quiet unlocked moment so
     * the real store comes back. Only a run of such starts while unlocked is taken as permanent.
     */
    private fun onUnavailable(
        context: Context,
        securityManager: DatabaseSecurityManager,
        verdict: PassphraseResult.Unavailable
    ): KeyboardDatabase {
        val locked = context.isDeviceLocked
        val strikes = if (locked) securityManager.strikes() else securityManager.recordStrike()
        if (!locked && strikes >= MAX_UNLOCKED_STRIKES) {
            return startOver(
                context,
                securityManager,
                "passphrase unavailable on $strikes consecutive unlocked starts"
            )
        }
        ErrorLogger.logException(
            component = "DatabaseModule",
            severity = ErrorLogger.Severity.CRITICAL,
            exception = verdict.cause ?: IllegalStateException("passphrase unavailable"),
            context = mapOf(
                "phase" to "passphrase_unavailable",
                "action" to "in_memory_stand_in",
                "device_locked" to locked.toString(),
                "strikes" to strikes.toString()
            )
        )
        val why = if (locked) "passphrase unavailable while the device is locked" else "passphrase unavailable"
        return standIn(context, DatabaseAvailability.Mode.PASSPHRASE_UNAVAILABLE, why)
    }

    /**
     * The database can never be read again with what we have: set it aside, forget the passphrase, and make a
     * fresh one through the ordinary path (which now generates a new passphrase — or, failing that, stands in).
     */
    private fun startOver(
        context: Context,
        securityManager: DatabaseSecurityManager,
        why: String
    ): KeyboardDatabase {
        ErrorLogger.logException(
            component = "DatabaseModule",
            severity = ErrorLogger.Severity.CRITICAL,
            exception = IllegalStateException(why),
            context = mapOf("phase" to "key_gone", "action" to "set_aside_and_start_over")
        )
        if (!setAside(context, "unreadable")) return standInUnmoved(context, "unreadable")
        securityManager.forgetStoredPassphrase()
        return openRealDatabase(context, securityManager)
    }

    private fun setAside(context: Context, reason: String): Boolean = DatabaseFiles.moveAside(context, reason) != null

    /** The file should have been set aside but could not be renamed: leave it, stand in, try again next start. */
    private fun standInUnmoved(context: Context, reason: String): KeyboardDatabase =
        standIn(context, DatabaseAvailability.Mode.PASSPHRASE_UNAVAILABLE, "$reason file could not be set aside")

    /** A throwaway empty DB with no storage/keystore dependency, flagged so nobody mistakes it for the real one. */
    private fun standIn(context: Context, mode: DatabaseAvailability.Mode, detail: String): KeyboardDatabase {
        DatabaseAvailability.fallback(mode, detail)
        return Room.inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun logPhase(phase: String, severity: ErrorLogger.Severity) {
        ErrorLogger.logException(
            component = "DatabaseModule",
            severity = severity,
            exception = IllegalStateException(phase),
            context = mapOf("phase" to phase)
        )
    }

    @Provides
    fun provideLearnedWordDao(database: KeyboardDatabase): LearnedWordDao = database.learnedWordDao()

    @Provides
    fun provideClipboardDao(database: KeyboardDatabase): ClipboardDao = database.clipboardDao()

    @Provides
    fun provideCustomKeyMappingDao(database: KeyboardDatabase): CustomKeyMappingDao = database.customKeyMappingDao()

    @Provides
    fun provideUserWordFrequencyDao(database: KeyboardDatabase): UserWordFrequencyDao = database.userWordFrequencyDao()

    @Provides
    fun provideUserWordBigramDao(database: KeyboardDatabase): UserWordBigramDao = database.userWordBigramDao()

    @Provides
    fun provideUserKanjiFrequencyDao(database: KeyboardDatabase): UserKanjiFrequencyDao =
        database.userKanjiFrequencyDao()

    @Provides
    fun provideUserDictionaryDao(database: KeyboardDatabase): UserDictionaryDao =
        database.userDictionaryDao()
}
