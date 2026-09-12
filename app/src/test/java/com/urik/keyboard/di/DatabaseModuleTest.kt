package com.urik.keyboard.di

import android.app.KeyguardManager
import android.content.Context
import com.urik.keyboard.data.database.DatabaseAvailability
import com.urik.keyboard.data.database.DatabaseFiles
import com.urik.keyboard.data.database.DatabaseSecurityManager
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.PassphraseResult
import com.urik.keyboard.markUserUnlocked
import com.urik.keyboard.utils.ErrorLogger
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The open decision tree of [DatabaseModule], driven through its two seams — the passphrase verdict (a mocked
 * [DatabaseSecurityManager]) and the file probe ([DatabaseModule.prober]) — against a REAL file in the
 * Robolectric app's database directory, so "set aside" and "untouched" are checked on disk.
 *
 * The one rule every case here guards: the database file is never deleted, and never handed to Room unless
 * the probe said it opens with what we have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseModuleTest {
    @Mock private lateinit var securityManager: DatabaseSecurityManager

    private lateinit var context: Context
    private lateinit var closeable: AutoCloseable
    private lateinit var dbFile: File

    private val realDb: KeyboardDatabase = mock()
    private val opened = mutableListOf<ByteArray?>()
    private val passphrase get() = ByteArray(32) { 0x42 }
    private val rekeys = mutableListOf<Pair<ByteArray?, ByteArray>>()

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        context = RuntimeEnvironment.getApplication()
        context.markUserUnlocked()
        ErrorLogger.resetForTesting()
        ErrorLogger.init(context)
        DatabaseAvailability.resetForTesting()
        dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        cleanDatabaseDir()
        opened.clear()
        rekeys.clear()
        rekey(false)
        DatabaseModule.setOpenerForTesting(
            object : DatabaseOpener {
                override fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase {
                    opened.add(passphrase?.copyOf())
                    return realDb
                }
            }
        )
        whenever(securityManager.strikes()).thenReturn(0)
        whenever(securityManager.recordStrike()).thenReturn(1)
    }

    @After
    fun tearDown() {
        DatabaseModule.resetOpenerForTesting()
        DatabaseModule.resetProberForTesting()
        DatabaseAvailability.resetForTesting()
        cleanDatabaseDir()
        closeable.close()
    }

    private fun cleanDatabaseDir() {
        dbFile.parentFile?.listFiles()
            ?.filter { it.name.startsWith(KeyboardDatabase.DATABASE_NAME) }
            ?.forEach { it.delete() }
    }

    private fun writeDbFile(content: String = "encrypted bytes") {
        dbFile.writeText(content)
        File(dbFile.path + "-wal").writeText("wal")
    }

    private fun setAsideFiles(): List<File> =
        dbFile.parentFile!!.listFiles()!!
            .filter { it.name.startsWith(KeyboardDatabase.DATABASE_NAME + ".") }
            .sortedBy { it.name }

    /** Stub the probe per key kind: the real passphrase, the legacy zero key, or plain (null). */
    private fun probe(
        real: DatabaseFiles.Probe = DatabaseFiles.Probe.NOT_A_DATABASE,
        zero: DatabaseFiles.Probe = DatabaseFiles.Probe.NOT_A_DATABASE,
        plain: DatabaseFiles.Probe = DatabaseFiles.Probe.NOT_A_DATABASE
    ) {
        DatabaseModule.setProberForTesting { _, passphrase ->
            when {
                passphrase == null -> plain
                passphrase.all { it == 0.toByte() } -> zero
                else -> real
            }
        }
    }

    private fun rekey(result: Boolean) {
        DatabaseModule.setRekeyerForTesting { _, from, to ->
            rekeys.add(from?.copyOf() to to.copyOf())
            result
        }
    }

    private fun setDeviceLocked(locked: Boolean) {
        Shadows.shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(locked)
    }

    // ---- Available ------------------------------------------------------------------------------------

    @Test
    fun `available and probe OK opens encrypted, marks real, clears strikes`() {
        writeDbFile()
        whenever(securityManager.resolvePassphrase()).thenReturn(PassphraseResult.Available(passphrase))
        probe(real = DatabaseFiles.Probe.OK)

        val db = DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertSame(realDb, db)
        assertEquals(1, opened.size)
        assertTrue("opened with the passphrase", opened[0]!!.contentEquals(passphrase))
        assertTrue(DatabaseAvailability.isReal)
        assertTrue("file untouched", dbFile.exists())
        assertTrue(setAsideFiles().isEmpty())
        verify(securityManager).clearStrikes()
    }

    @Test
    fun `available with no file opens a fresh encrypted database`() {
        whenever(securityManager.resolvePassphrase()).thenReturn(PassphraseResult.Available(passphrase))
        probe(real = DatabaseFiles.Probe.ABSENT)

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertEquals(1, opened.size)
        assertNotNull(opened[0])
        assertTrue(DatabaseAvailability.isReal)
    }

    @Test
    fun `available but file is PLAIN gets encrypted in place, never set aside`() {
        writeDbFile("plain sqlite")
        whenever(securityManager.resolvePassphrase()).thenReturn(PassphraseResult.Available(passphrase))
        probe(plain = DatabaseFiles.Probe.OK)
        rekey(true)

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertEquals(1, rekeys.size)
        assertNull("from plain", rekeys[0].first)
        assertTrue("to the real passphrase", rekeys[0].second.contentEquals(passphrase))
        assertTrue("file still in place", dbFile.exists())
        assertTrue(setAsideFiles().isEmpty())
        assertEquals(1, opened.size)
        assertTrue("opened with the real passphrase", opened[0]!!.contentEquals(passphrase))
        assertTrue(DatabaseAvailability.isReal)
    }

    @Test
    fun `available but file keyed with the legacy ZERO key is re-encrypted to the real passphrase`() {
        writeDbFile()
        whenever(securityManager.resolvePassphrase()).thenReturn(PassphraseResult.Available(passphrase))
        probe(zero = DatabaseFiles.Probe.OK)
        rekey(true)

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertEquals(1, rekeys.size)
        assertTrue("from the zero key", rekeys[0].first!!.all { it == 0.toByte() })
        assertTrue("to the real passphrase", rekeys[0].second.contentEquals(passphrase))
        assertTrue(setAsideFiles().isEmpty())
        assertEquals(1, opened.size)
        assertTrue("opened with the real passphrase", opened[0]!!.contentEquals(passphrase))
        assertTrue(DatabaseAvailability.isReal)
    }

    @Test
    fun `zero-keyed file whose re-encryption fails is opened with the zero key, not set aside`() {
        writeDbFile()
        whenever(securityManager.resolvePassphrase()).thenReturn(PassphraseResult.Available(passphrase))
        probe(zero = DatabaseFiles.Probe.OK)
        rekey(false)

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertTrue("file still in place", dbFile.exists())
        assertTrue(setAsideFiles().isEmpty())
        assertEquals(1, opened.size)
        assertTrue("opened with the zero key", opened[0]!!.all { it == 0.toByte() })
        assertTrue(DatabaseAvailability.isReal)
    }

    @Test
    fun `available but file unreadable with every key is set aside with its sidecars, then fresh`() {
        writeDbFile()
        whenever(securityManager.resolvePassphrase()).thenReturn(PassphraseResult.Available(passphrase))
        probe()

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertTrue("no re-encryption attempted", rekeys.isEmpty())
        assertFalse("main file moved", dbFile.exists())
        assertFalse("sidecar moved", File(dbFile.path + "-wal").exists())
        val aside = setAsideFiles()
        assertEquals(2, aside.size)
        assertTrue(aside[0].name.startsWith(KeyboardDatabase.DATABASE_NAME + ".unreadable-"))
        assertTrue(aside[1].name.endsWith("-wal"))
        assertEquals("encrypted bytes", aside[0].readText())
        assertEquals(1, opened.size)
        assertTrue(DatabaseAvailability.isReal)
    }

    @Test
    fun `available but corrupt is set aside as corrupt`() {
        writeDbFile()
        whenever(securityManager.resolvePassphrase()).thenReturn(PassphraseResult.Available(passphrase))
        probe(real = DatabaseFiles.Probe.CORRUPT)

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertFalse(dbFile.exists())
        assertTrue(setAsideFiles().first().name.contains(".corrupt-"))
        assertEquals(1, opened.size)
    }

    @Test
    fun `available but probe ERROR leaves the file alone and stands in`() {
        writeDbFile()
        whenever(securityManager.resolvePassphrase()).thenReturn(PassphraseResult.Available(passphrase))
        probe(real = DatabaseFiles.Probe.ERROR)
        setDeviceLocked(false)

        val db = DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertTrue("stand-in, not the real DB", db !== realDb)
        assertTrue(opened.isEmpty())
        assertTrue("file untouched", dbFile.exists())
        assertEquals(DatabaseAvailability.Mode.PASSPHRASE_UNAVAILABLE, DatabaseAvailability.mode)
        verify(securityManager).recordStrike()
    }

    // ---- Unavailable ----------------------------------------------------------------------------------

    @Test
    fun `unavailable while unlocked stands in, records a strike, touches nothing`() {
        writeDbFile()
        whenever(securityManager.resolvePassphrase())
            .thenReturn(PassphraseResult.Unavailable(RuntimeException("keystore")))
        setDeviceLocked(false)

        val db = DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertTrue(db !== realDb)
        assertTrue(opened.isEmpty())
        assertTrue(dbFile.exists())
        assertTrue(setAsideFiles().isEmpty())
        assertEquals(DatabaseAvailability.Mode.PASSPHRASE_UNAVAILABLE, DatabaseAvailability.mode)
        assertTrue(DatabaseAvailability.restartWhenUnlockedHeals)
        verify(securityManager).recordStrike()
        verify(securityManager, never()).forgetStoredPassphrase()
    }

    @Test
    fun `unavailable while the device is LOCKED records no strike`() {
        writeDbFile()
        whenever(securityManager.resolvePassphrase())
            .thenReturn(PassphraseResult.Unavailable(RuntimeException("device locked")))
        setDeviceLocked(true)

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        verify(securityManager, never()).recordStrike()
        assertTrue(dbFile.exists())
        assertEquals(DatabaseAvailability.Mode.PASSPHRASE_UNAVAILABLE, DatabaseAvailability.mode)
        assertTrue(DatabaseAvailability.detail.contains("locked"))
    }

    @Test
    fun `unavailable on the MAX-th unlocked start sets the file aside and starts over`() {
        writeDbFile()
        whenever(securityManager.resolvePassphrase())
            .thenReturn(PassphraseResult.Unavailable(RuntimeException("keystore")))
            .thenReturn(PassphraseResult.Available(passphrase))
        whenever(securityManager.recordStrike()).thenReturn(DatabaseModule.MAX_UNLOCKED_STRIKES)
        setDeviceLocked(false)
        probe(real = DatabaseFiles.Probe.ABSENT)

        val db = DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertSame(realDb, db)
        assertFalse(dbFile.exists())
        assertTrue(setAsideFiles().first().name.contains(".unreadable-"))
        verify(securityManager).forgetStoredPassphrase()
        assertEquals(1, opened.size)
        assertTrue(DatabaseAvailability.isReal)
    }

    @Test
    fun `strikes below MAX while unlocked keep standing in`() {
        writeDbFile()
        whenever(securityManager.resolvePassphrase()).thenReturn(PassphraseResult.Unavailable(null))
        whenever(securityManager.recordStrike()).thenReturn(DatabaseModule.MAX_UNLOCKED_STRIKES - 1)
        setDeviceLocked(false)

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertTrue(dbFile.exists())
        verify(securityManager, never()).forgetStoredPassphrase()
        assertEquals(DatabaseAvailability.Mode.PASSPHRASE_UNAVAILABLE, DatabaseAvailability.mode)
    }

    // ---- KeyGone --------------------------------------------------------------------------------------

    @Test
    fun `key gone sets the file aside, forgets the passphrase and opens fresh`() {
        writeDbFile()
        whenever(securityManager.resolvePassphrase())
            .thenReturn(PassphraseResult.KeyGone("master key missing"))
            .thenReturn(PassphraseResult.Available(passphrase))
        probe(real = DatabaseFiles.Probe.ABSENT)

        val db = DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertSame(realDb, db)
        assertFalse(dbFile.exists())
        assertEquals(2, setAsideFiles().size)
        verify(securityManager).forgetStoredPassphrase()
        assertTrue(DatabaseAvailability.isReal)
    }

    @Test
    fun `key gone with no file just starts fresh`() {
        whenever(securityManager.resolvePassphrase())
            .thenReturn(PassphraseResult.KeyGone("master key missing"))
            .thenReturn(PassphraseResult.Available(passphrase))
        probe(real = DatabaseFiles.Probe.ABSENT)

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertTrue(setAsideFiles().isEmpty())
        verify(securityManager).forgetStoredPassphrase()
        assertEquals(1, opened.size)
    }

    // ---- NoEncryption ---------------------------------------------------------------------------------

    @Test
    fun `no encryption opens plain when the file reads as plain`() {
        writeDbFile("plain")
        whenever(securityManager.resolvePassphrase()).thenReturn(PassphraseResult.NoEncryption)
        probe(plain = DatabaseFiles.Probe.OK)

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertEquals(1, opened.size)
        assertNull("plain open", opened[0])
        assertTrue(dbFile.exists())
        assertTrue(DatabaseAvailability.isReal)
    }

    @Test
    fun `no encryption with an unreadable (encrypted) file sets it aside`() {
        writeDbFile()
        whenever(securityManager.resolvePassphrase()).thenReturn(PassphraseResult.NoEncryption)
        probe(plain = DatabaseFiles.Probe.NOT_A_DATABASE)

        DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertFalse(dbFile.exists())
        assertTrue(setAsideFiles().first().name.contains(".unreadable-"))
        assertEquals(1, opened.size)
        assertNull(opened[0])
    }

    // ---- Before first unlock --------------------------------------------------------------------------

    @Test
    fun `before first unlock stands in without asking the security manager`() {
        Shadows.shadowOf(context.getSystemService(android.os.UserManager::class.java)).setUserUnlocked(false)
        writeDbFile()

        val db = DatabaseModule.provideKeyboardDatabase(context, securityManager)

        assertTrue(db !== realDb)
        verify(securityManager, never()).resolvePassphrase()
        assertEquals(DatabaseAvailability.Mode.BEFORE_FIRST_UNLOCK, DatabaseAvailability.mode)
        assertFalse(
            "BFU heals through the unlock receiver, not the hide hook",
            DatabaseAvailability.restartWhenUnlockedHeals
        )
        assertTrue(dbFile.exists())
    }
}
