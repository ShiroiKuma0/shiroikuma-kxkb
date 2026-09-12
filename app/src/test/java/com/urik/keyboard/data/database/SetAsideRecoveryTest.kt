package com.urik.keyboard.data.database

import android.content.Context
import androidx.room.Room
import com.urik.keyboard.markUserUnlocked
import com.urik.keyboard.service.BackupManager
import com.urik.keyboard.service.BlacklistRepository
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.ErrorLogger
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The merge-back of a set-aside copy, end to end on real Room databases (Robolectric's plain SQLite stands in
 * for SQLCipher: the probe and the copy opener are stubbed to "plain", everything after them is the real code).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SetAsideRecoveryTest {
    private lateinit var context: Context
    private lateinit var live: KeyboardDatabase
    private lateinit var recovery: SetAsideRecovery
    private lateinit var dir: File
    private val asideName = "${KeyboardDatabase.DATABASE_NAME}.unreadable-2026-09-11_23-15-00"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.markUserUnlocked()
        ErrorLogger.resetForTesting()
        ErrorLogger.init(context)
        DatabaseAvailability.resetForTesting()
        dir = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME).parentFile!!.apply { mkdirs() }
        clean()
        live = Room.inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java).allowMainThreadQueries().build()
        val settings: SettingsRepository = mock()
        val blacklist: BlacklistRepository = mock()
        val liveBackup = BackupManager(
            context, settings, live.customKeyMappingDao(), live.userDictionaryDao(), live.learnedWordDao(),
            live.userWordFrequencyDao(), live.userWordBigramDao(), blacklist
        )
        val security: DatabaseSecurityManager = mock()
        recovery = SetAsideRecovery(context, security, liveBackup, settings, blacklist).apply {
            prober = { _, key -> if (key == null) DatabaseFiles.Probe.OK else DatabaseFiles.Probe.NOT_A_DATABASE }
            opener = { ctx, name, _ ->
                Room.databaseBuilder(ctx, KeyboardDatabase::class.java, name).allowMainThreadQueries().build()
            }
        }
    }

    @After
    fun tearDown() {
        live.close()
        clean()
        DatabaseAvailability.resetForTesting()
    }

    private fun clean() {
        dir.listFiles()?.filter { it.name.startsWith(KeyboardDatabase.DATABASE_NAME) }?.forEach { it.delete() }
    }

    private fun writeAsideCopy(name: String = asideName) = runBlocking {
        val copy = Room.databaseBuilder(context, KeyboardDatabase::class.java, name).allowMainThreadQueries().build()
        copy.userDictionaryDao().importRow("ja", "japanese", "しろいくま", "白い熊", 5, 1L, 2L)
        copy.learnedWordDao().importWordWithMerge(
            LearnedWord.create(word = "obouruč", wordNormalized = "obouruc", languageTag = "cs", frequency = 3)
        )
        copy.userWordBigramDao().importRow("cs", "white", "bear", 2, 3L)
        copy.close()
    }

    @Test
    fun `a set-aside copy is merged into the live database and renamed recovered`() = runBlocking<Unit> {
        writeAsideCopy()

        val merged = recovery.run()

        assertEquals(1, merged)
        val dict = live.userDictionaryDao().getAll()
        assertEquals(1, dict.size)
        assertEquals("白い熊", dict[0].value)
        assertEquals(5, dict[0].frequency)
        val words = live.learnedWordDao().getAllLearnedWords()
        assertEquals(listOf("obouruč"), words.map { it.word })
        assertEquals(1, live.userWordBigramDao().getAll().size)
        assertFalse("original name gone", File(dir, asideName).exists())
        assertTrue(
            "renamed to recovered",
            File(dir, asideName.replace(".unreadable-", ".recovered-")).exists()
        )
    }

    @Test
    fun `a second run finds nothing to do`() = runBlocking<Unit> {
        writeAsideCopy()
        recovery.run()

        assertEquals(0, recovery.run())
        assertEquals(1, live.userDictionaryDao().getAll().size)
    }

    @Test
    fun `merging adds to what the live database already holds`() = runBlocking<Unit> {
        live.userDictionaryDao().importRow("en", "shortcut", "sk", "白い熊", 1, 1L, 1L)
        live.learnedWordDao().importWordWithMerge(
            LearnedWord.create(word = "obouruč", wordNormalized = "obouruc", languageTag = "cs", frequency = 4)
        )
        writeAsideCopy()

        recovery.run()

        assertEquals(2, live.userDictionaryDao().getAll().size)
        val word = live.learnedWordDao().getAllLearnedWords().single()
        assertEquals("frequencies merge, rows never duplicate", 7, word.frequency)
    }

    @Test
    fun `a copy that opens with no key is left exactly where it is`() = runBlocking<Unit> {
        writeAsideCopy()
        recovery.prober = { _, _ -> DatabaseFiles.Probe.NOT_A_DATABASE }

        assertEquals(0, recovery.run())
        assertTrue(File(dir, asideName).exists())
        assertTrue(live.userDictionaryDao().getAll().isEmpty())
    }

    @Test
    fun `nothing runs on a stand-in database`() = runBlocking<Unit> {
        writeAsideCopy()
        DatabaseAvailability.fallback(DatabaseAvailability.Mode.PASSPHRASE_UNAVAILABLE, "test")

        assertEquals(0, recovery.run())
        assertTrue(File(dir, asideName).exists())
    }

    @Test
    fun `recovered and unrelated files are not candidates`() = runBlocking<Unit> {
        writeAsideCopy("${KeyboardDatabase.DATABASE_NAME}.recovered-2026-09-11_23-15-00")
        writeAsideCopy("${KeyboardDatabase.DATABASE_NAME}_other")

        assertEquals(0, recovery.run())
        assertTrue(live.userDictionaryDao().getAll().isEmpty())
    }
}
