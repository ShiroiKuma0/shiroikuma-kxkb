package com.urik.keyboard.data

import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.UserWordBigramDao
import com.urik.keyboard.data.database.UserWordFrequency
import com.urik.keyboard.data.database.UserWordFrequencyDao
import com.urik.keyboard.service.WordNormalizer
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ManagedCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Your typing history as the cluster bar reads it: the words you type most, and the ones you have just
 * typed. The counts are written out on a 300 ms debounce, so a word committed a moment ago is still only an
 * entry in [WordFrequencyRepository]'s pending map — and "a word typed once leads" would be a promise
 * broken on the very keystroke that matters if the predictor could not see it there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WordFrequencyHistoryTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: KeyboardDatabase
    private lateinit var frequencyDao: UserWordFrequencyDao
    private lateinit var bigramDao: UserWordBigramDao
    private lateinit var repository: WordFrequencyRepository

    private fun row(word: String, frequency: Int) =
        UserWordFrequency(languageTag = "en", wordNormalized = word, frequency = frequency)

    @Before
    fun setup() {
        database = mock()
        bigramDao = mock()
        frequencyDao =
            mock {
                onBlocking { getMostFrequentWords(any(), any()) } doReturn emptyList()
                onBlocking { findWord(any(), any()) } doReturn null
                onBlocking { findWords(any(), any()) } doReturn emptyList()
                onBlocking { deleteForLanguage(any(), any()) } doReturn 0
            }

        val wordNormalizer = mock<WordNormalizer>()
        whenever(wordNormalizer.normalize(any(), any())).thenAnswer {
            (it.arguments[0] as String).trim().lowercase()
        }

        val cacheMemoryManager = mock<CacheMemoryManager>()
        whenever(
            cacheMemoryManager.createCache<String, Int>(eq("user_word_frequency_cache"), eq(2000), anyOrNull())
        ).thenReturn(ManagedCache("t_freq", 2000, null))
        whenever(
            cacheMemoryManager.createCache<String, LinkedHashSet<String>>(
                eq("user_bigram_cache"),
                eq(100),
                anyOrNull()
            )
        ).thenReturn(ManagedCache("t_bigram", 100, null))

        repository =
            WordFrequencyRepository(
                database = database,
                userWordFrequencyDao = frequencyDao,
                userWordBigramDao = bigramDao,
                wordNormalizer = wordNormalizer,
                cacheMemoryManager = cacheMemoryManager,
                ioDispatcher = testDispatcher,
                defaultDispatcher = testDispatcher
            )
    }

    @Test
    fun `a word typed a moment ago is already part of your history`() = runTest {
        whenever(frequencyDao.getMostFrequentWords(eq("en"), any())).thenReturn(listOf(row("hello", 9)))

        repository.incrementFrequency("recommendation", "en")

        val history = repository.topWords("en", 2000)
        val pending = history.filter { it.first == "recommendation" }
        assertEquals("the pending word is listed, once", 1, pending.size)
        assertEquals("counted from the increment that is still waiting", 1, pending.single().second)
        assertTrue("the stored words are still listed", history.any { it.first == "hello" })
    }

    @Test
    fun `the history is ordered by how often you type`() = runTest {
        whenever(frequencyDao.getMostFrequentWords(eq("en"), any()))
            .thenReturn(listOf(row("hello", 9), row("recommend", 2)))

        repeat(20) { repository.incrementFrequency("recommend", "en") }

        val history = repository.topWords("en", 2000)
        assertEquals("22 beats 9, and the pending 20 are counted", "recommend", history.first().first)
        assertEquals(22, history.first().second)
    }

    @Test
    fun `your count includes what has not been written out yet`() = runTest {
        whenever(frequencyDao.findWord(eq("en"), eq("recommend"))).thenReturn(row("recommend", 5))

        repository.incrementFrequency("recommend", "en")
        repository.incrementFrequency("recommend", "en")

        assertEquals(7, repository.getFrequency("recommend", "en"))
    }

    @Test
    fun `a stored count survives a word being typed while uncached`() = runTest {
        // The cache holds the count as the DATABASE has it. Writing an optimistic one here used to overwrite
        // a real standing whenever the entry had been evicted: a word typed fifty times came back as 1.
        whenever(frequencyDao.findWord(eq("en"), eq("recommend"))).thenReturn(row("recommend", 50))

        repository.incrementFrequency("recommend", "en")

        assertEquals(51, repository.getFrequency("recommend", "en"))
    }

    @Test
    fun `forgetting a word drops the increments still waiting to be written`() = runTest {
        // In the order it really happens: the bar read your history, offered the word, and THEN you
        // long-pressed it — so the cached list has to lose it as well as the table.
        var stored = listOf(row("recommend", 4))
        whenever(frequencyDao.getMostFrequentWords(eq("en"), any())).thenAnswer { stored }
        whenever(frequencyDao.deleteForLanguage(eq("en"), eq("recommend"))).thenAnswer {
            stored = emptyList()
            1
        }
        repository.incrementFrequency("recommend", "en")
        assertTrue(repository.topWords("en", 2000).any { it.first == "recommend" })

        val forgotten = repository.forget("recommend", "en")

        assertTrue("there was something to forget", forgotten)
        verify(frequencyDao).deleteForLanguage("en", "recommend")
        assertFalse(
            "and nothing of it is left to predict from",
            repository.topWords("en", 2000).any { it.first == "recommend" }
        )
        assertEquals("nor to rank by", 0, repository.getFrequency("recommend", "en"))
    }

    @Test
    fun `forgetting a word nobody typed reports nothing to forget`() = runTest {
        assertFalse(repository.forget("recommend", "en"))
    }

    @Test
    fun `the most-typed list is read once and carried forward`() = runTest {
        // It is read on the input path, so re-querying 2 000 rows per keystroke is exactly what must not
        // happen; the flush moves the cached list by the same deltas it writes.
        whenever(frequencyDao.getMostFrequentWords(eq("en"), any())).thenReturn(listOf(row("hello", 9)))

        repeat(5) { repository.topWords("en", 2000) }

        verify(frequencyDao, times(1)).getMostFrequentWords(eq("en"), any())
    }

    @Test
    fun `history is kept per language`() = runTest {
        whenever(frequencyDao.getMostFrequentWords(eq("cs"), any())).thenReturn(listOf(row("ahoj", 3)))

        repository.incrementFrequency("recommend", "en")

        val czech = repository.topWords("cs", 2000)
        assertFalse("an English commit is no part of the Czech history", czech.any { it.first == "recommend" })
        assertTrue(czech.any { it.first == "ahoj" })
    }
}
