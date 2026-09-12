package com.urik.keyboard.data.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.R
import com.urik.keyboard.utils.ErrorLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric has no AndroidKeyStore provider, so every keystore touch throws here — which is exactly the
 * "keystore unwell" case the verdicts must classify as [PassphraseResult.Unavailable], never as anything that
 * would touch the file.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseSecurityManagerTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ErrorLogger.resetForTesting()
        ErrorLogger.init(context)
        DatabaseSecurityManager(context, lockScreenCheck = { true }).forgetStoredPassphrase()
    }

    private fun dePrefs() = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("urik_db_prefs", Context.MODE_PRIVATE)

    @Test
    fun `nothing stored and no lock screen is NoEncryption and logs HIGH`() {
        val manager = DatabaseSecurityManager(context, lockScreenCheck = { false })
        val beforeCount = ErrorLogger.getErrorCount()

        val result = manager.resolvePassphrase()

        assertTrue(result is PassphraseResult.NoEncryption)
        assertTrue("Expected at least one new error entry", ErrorLogger.getErrorCount() > beforeCount)
        assertFalse(manager.hasStoredPassphrase())
    }

    @Test
    fun `nothing stored with a lock screen but no keystore is Unavailable, nothing stored afterwards`() {
        val manager = DatabaseSecurityManager(context, lockScreenCheck = { true })

        val result = manager.resolvePassphrase()

        assertTrue("$result", result is PassphraseResult.Unavailable)
        assertFalse("a failed generation must not leave a half-stored passphrase", manager.hasStoredPassphrase())
    }

    @Test
    fun `stored passphrase with no keystore is Unavailable — never KeyGone, never NoEncryption`() {
        dePrefs().edit().putString("encrypted_passphrase", "AAAA").putString("encryption_iv", "AAAA").commit()
        // Even without a lock screen: an encrypted database is decrypted regardless.
        val manager = DatabaseSecurityManager(context, lockScreenCheck = { false })

        val result = manager.resolvePassphrase()

        assertTrue("$result", result is PassphraseResult.Unavailable)
        assertTrue(manager.hasStoredPassphrase())
        assertEquals(DatabaseSecurityManager.LEGACY_MASTER_KEY_ALIAS, manager.storedAlias())
    }

    @Test
    fun `forgetStoredPassphrase clears passphrase, iv, alias and strikes`() {
        dePrefs().edit()
            .putString("encrypted_passphrase", "AAAA")
            .putString("encryption_iv", "AAAA")
            .putString("master_key_alias", DatabaseSecurityManager.MASTER_KEY_ALIAS)
            .putInt("passphrase_strikes", 3)
            .commit()
        val manager = DatabaseSecurityManager(context, lockScreenCheck = { true })
        assertTrue(manager.hasStoredPassphrase())
        assertEquals(3, manager.strikes())

        manager.forgetStoredPassphrase()

        assertFalse(manager.hasStoredPassphrase())
        assertEquals(0, manager.strikes())
        assertEquals(DatabaseSecurityManager.LEGACY_MASTER_KEY_ALIAS, manager.storedAlias())
    }

    @Test
    fun `strikes count up, survive a new instance, and clear`() {
        val manager = DatabaseSecurityManager(context, lockScreenCheck = { true })
        assertEquals(0, manager.strikes())
        assertEquals(1, manager.recordStrike())
        assertEquals(2, manager.recordStrike())
        assertEquals(2, DatabaseSecurityManager(context, lockScreenCheck = { true }).strikes())
        manager.clearStrikes()
        assertEquals(0, manager.strikes())
    }

    @Test
    fun `prefs field is non-null immediately after construction`() {
        val manager = DatabaseSecurityManager(context, lockScreenCheck = { false })
        val field = DatabaseSecurityManager::class.java
            .getDeclaredField("prefs")
            .apply { isAccessible = true }
        assertNotNull("prefs must be non-null after construction", field.get(manager))
    }

    @Test
    fun `hasDeviceLockScreen returns false when no lock screen configured`() {
        val manager = DatabaseSecurityManager(context, lockScreenCheck = { false })
        assertTrue(
            "SEC-01 requires hasDeviceLockScreen() to reflect lock screen absence",
            !manager.hasDeviceLockScreen()
        )
    }

    @Test
    fun `privacy_settings_keystore_api27_title string resource exists`() {
        val title = context.getString(R.string.privacy_settings_keystore_api27_title)
        assertTrue("SEC-02 requires api27 title string", title.isNotEmpty())
    }

    @Test
    fun `privacy_settings_keystore_api27_summary string resource exists`() {
        val summary = context.getString(R.string.privacy_settings_keystore_api27_summary)
        assertTrue("SEC-02 requires api27 summary string", summary.isNotEmpty())
    }
}
