package com.urik.keyboard.service

import android.content.Context
import androidx.room.Room
import com.urik.keyboard.data.VoiceCorpusBackup
import com.urik.keyboard.data.VoiceCorpusRepository
import com.urik.keyboard.data.database.CustomKeyMappingDao
import com.urik.keyboard.data.database.DatabaseAvailability
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.data.database.UserDictionaryDao
import com.urik.keyboard.data.database.UserWordBigramDao
import com.urik.keyboard.data.database.UserWordFrequencyDao
import com.urik.keyboard.service.voice.VoiceKnowledge
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.ErrorLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The voice corpus survives a backup round trip: rows, word evidence and the recordings themselves (streamed as
 * binary entries), and a second import of the same archive duplicates nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoiceCorpusBackupTest {
    private lateinit var context: Context
    private lateinit var db: KeyboardDatabase
    private lateinit var repository: VoiceCorpusRepository
    private lateinit var manager: BackupManager
    private val clipBytes = ByteArray(5000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ErrorLogger.resetForTesting()
        ErrorLogger.init(context)
        DatabaseAvailability.resetForTesting()
        db = Room.inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java).allowMainThreadQueries().build()
        repository = VoiceCorpusRepository(context, db.voiceCorpusDao())
        manager = BackupManager(
            context, mock<SettingsRepository>(), mock<CustomKeyMappingDao>(), mock<UserDictionaryDao>(),
            mock<LearnedWordDao>(), mock<UserWordFrequencyDao>(), mock<UserWordBigramDao>(), mock<BlacklistRepository>()
        )
        manager.voiceCorpusBackup = VoiceCorpusBackup(context, db.voiceCorpusDao(), repository)
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, VoiceCorpusRepository.CORPUS_DIR).deleteRecursively()
    }

    @Test
    fun `recordings, rows and corrections come back after a wipe, and a re-import adds nothing`() = runBlocking<Unit> {
        val dao = db.voiceCorpusDao()
        val dir = File(context.filesDir, VoiceCorpusRepository.CORPUS_DIR).apply { mkdirs() }
        File(dir, "u1.wav").writeBytes(clipBytes)
        dao.insertUtterance("u1", "cs", "Chleba houské", "Chleba housky", "u1.wav", 16000, 1L)
        dao.insertEvent("u1", "cs", 1, "houské", "housky", "corrected", 0.2f, "UNKNOWN_WORD", 2L)

        val out = ByteArrayOutputStream()
        val exported = manager.export(setOf(BackupPart.VOICE_CORPUS), out, "t")
        assertTrue(exported.errors.toString(), exported.ok)

        repository.deleteAll()
        assertEquals(0, dao.utteranceCount())

        val imported = manager.import(setOf(BackupPart.VOICE_CORPUS), ByteArrayInputStream(out.toByteArray()))
        assertTrue(imported.errors.toString(), imported.ok)
        manager.import(setOf(BackupPart.VOICE_CORPUS), ByteArrayInputStream(out.toByteArray()))

        assertEquals(1, dao.utteranceCount())
        assertEquals(1, dao.allEvents().size)
        assertArrayEquals(clipBytes, File(dir, "u1.wav").readBytes())
        repository.ensureLoaded("cs")
        assertEquals(VoiceKnowledge.Misrecognized("housky"), repository.knowledge("cs", "houské"))
    }

    @Test
    fun `the corpus part is opt-in`() {
        assertTrue(!BackupPart.VOICE_CORPUS.defaultSelected)
    }
}
