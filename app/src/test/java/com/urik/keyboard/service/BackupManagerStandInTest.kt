package com.urik.keyboard.service

import android.content.Context
import com.urik.keyboard.R
import com.urik.keyboard.data.database.CustomKeyMappingDao
import com.urik.keyboard.data.database.DatabaseAvailability
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.data.database.UserDictionaryDao
import com.urik.keyboard.data.database.UserWordBigramDao
import com.urik.keyboard.data.database.UserWordFrequencyDao
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.ErrorLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * While the injected database is the in-memory stand-in, the three Room-backed parts must FAIL — no entry in
 * the archive, the part named in `errors` — rather than export as empty or import into a store that dies with
 * the process. The 2026-09-03 backup was exactly such an export, taken in good faith and restored in good faith.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupManagerStandInTest {
    private lateinit var context: Context
    private val settingsRepository: SettingsRepository = mock()
    private val customKeyMappingDao: CustomKeyMappingDao = mock()
    private val userDictionaryDao: UserDictionaryDao = mock()
    private val learnedWordDao: LearnedWordDao = mock()
    private val userWordFrequencyDao: UserWordFrequencyDao = mock()
    private val userWordBigramDao: UserWordBigramDao = mock()
    private val blacklistRepository: BlacklistRepository = mock()
    private lateinit var manager: BackupManager
    private val dictionaryParts = setOf(BackupPart.USER_DICTIONARY, BackupPart.LEARNED_WORDS, BackupPart.NEXT_WORD)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ErrorLogger.resetForTesting()
        ErrorLogger.init(context)
        DatabaseAvailability.resetForTesting()
        runBlocking {
            whenever(blacklistRepository.getAll()).thenReturn(setOf("nope"))
            whenever(userDictionaryDao.getAll()).thenReturn(emptyList())
            whenever(learnedWordDao.getAllLearnedWords()).thenReturn(emptyList())
            whenever(userWordFrequencyDao.getAll()).thenReturn(emptyList())
            whenever(userWordBigramDao.getAll()).thenReturn(emptyList())
            whenever(customKeyMappingDao.getAllMappings()).thenReturn(emptyList())
            whenever(settingsRepository.exportPreferences()).thenReturn(Result.success(emptyMap()))
            whenever(settingsRepository.exportRawBackupValues(any())).thenReturn(emptyMap())
        }
        manager = BackupManager(
            context, settingsRepository, customKeyMappingDao, userDictionaryDao, learnedWordDao,
            userWordFrequencyDao, userWordBigramDao, blacklistRepository
        )
    }

    @After
    fun tearDown() = DatabaseAvailability.resetForTesting()

    private fun entriesOf(zip: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                out[e.name] = z.readBytes().toString(Charsets.UTF_8)
                z.closeEntry()
                e = z.nextEntry
            }
        }
        return out
    }

    @Test
    fun `export on the stand-in fails the dictionary parts and writes no entry for them`() = runBlocking<Unit> {
        DatabaseAvailability.fallback(DatabaseAvailability.Mode.PASSPHRASE_UNAVAILABLE, "test")
        val out = ByteArrayOutputStream()

        val result = manager.export(dictionaryParts + BackupPart.BLACKLIST + BackupPart.SETTINGS, out, "t")

        assertEquals(
            dictionaryParts.map { context.getString(it.labelRes) }.toSet(),
            result.errors.toSet()
        )
        val entries = entriesOf(out.toByteArray())
        dictionaryParts.forEach { assertFalse("${it.fileName} must not be written", entries.containsKey(it.fileName)) }
        assertTrue(entries.containsKey(BackupPart.BLACKLIST.fileName))
        val manifestParts = JSONObject(entries[BackupManager.MANIFEST]!!).getJSONArray("parts")
        val listed = (0 until manifestParts.length()).map { manifestParts.getString(it) }.toSet()
        assertEquals(setOf(BackupPart.BLACKLIST.id, BackupPart.SETTINGS.id), listed)
        // Settings still exports, but without a key-mappings section the stand-in would have answered as empty.
        assertFalse(JSONObject(entries[BackupPart.SETTINGS.fileName]!!).has("customKeyMappings"))
        verify(userDictionaryDao, never()).getAll()
        verify(learnedWordDao, never()).getAllLearnedWords()
        verify(userWordBigramDao, never()).getAll()
    }

    @Test
    fun `export on the real database writes every part`() = runBlocking<Unit> {
        val out = ByteArrayOutputStream()

        val result = manager.export(dictionaryParts + BackupPart.SETTINGS, out, "t")

        assertTrue(result.ok)
        val entries = entriesOf(out.toByteArray())
        dictionaryParts.forEach { assertTrue(entries.containsKey(it.fileName)) }
        assertTrue(JSONObject(entries[BackupPart.SETTINGS.fileName]!!).has("customKeyMappings"))
    }

    @Test
    fun `import on the stand-in fails the dictionary parts and never touches the DAOs`() = runBlocking<Unit> {
        // A real archive first, then import it while standing in.
        val out = ByteArrayOutputStream()
        manager.export(dictionaryParts + BackupPart.BLACKLIST, out, "t")
        DatabaseAvailability.fallback(DatabaseAvailability.Mode.BEFORE_FIRST_UNLOCK, "test")

        val result = manager.import(dictionaryParts + BackupPart.BLACKLIST, ByteArrayInputStream(out.toByteArray()))

        assertEquals(dictionaryParts.map { context.getString(it.labelRes) }.toSet(), result.errors.toSet())
        assertEquals(1, result.lines.size)
        verify(userDictionaryDao, never()).importRow(any(), any(), any(), any(), any(), any(), any())
        verify(learnedWordDao, never()).importWordWithMerge(any())
        verify(userWordBigramDao, never()).importRow(any(), any(), any(), any(), any())
        verify(blacklistRepository).addAll(any())
    }

    @Test
    fun `the unavailable notice string exists`() {
        assertTrue(context.getString(R.string.export_import_db_unavailable).isNotBlank())
    }
}
