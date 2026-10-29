package com.urik.keyboard.service

import android.content.Context
import android.content.res.AssetManager
import android.graphics.PointF
import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ManagedCache
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The word the taps spell IN FULL leads every word they merely BEGIN (白い熊, 2026-10-29): one tap on the
 * `(io` key IS "I", three on `;l:` `aev` `btn` ARE "let". Both cases came off the phone with the full word
 * LAST — "in it is on I" and "let's language lens languages let" — because the history, learned and
 * completion ladders match a band-constrained PREFIX and rank purely on how often a word has been typed,
 * a number that saturates just under 1.0 while the dictionary walk that found the full word tops out at
 * 0.99.
 *
 * The bands are 白い熊's own 3+2 cluster board (`en_3p2_5r4c`), so the buffers are the real centre letters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FullWordLeadsPredictionTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var assetManager: AssetManager
    private lateinit var languageManager: LanguageManager
    private lateinit var wordLearningEngine: WordLearningEngine
    private lateinit var wordFrequencyRepository: WordFrequencyRepository
    private lateinit var cacheMemoryManager: CacheMemoryManager
    private lateinit var blacklistRepository: BlacklistRepository
    private lateinit var wordNormalizer: WordNormalizer
    private lateinit var spellCheckManager: SpellCheckManager

    /**
     * Frequencies in the real `en.urik` band: the one-tap rivals of "i" are among the commonest words in
     * English, and "language" is far commoner than "let" — which is exactly why a rule, not a weight, is
     * what puts the tapped word first. "lets" is here with "let's" twenty times above it, so the bar
     * promotes it to the contraction ([SpellCheckManager.getDominantContractionForm]) just as on the phone.
     */
    private val testDictionary =
        """
        i 25000000
        in 29000000
        is 18000000
        it 20000000
        on 15000000
        ok 400000
        lab 29819
        language 4000000
        languages 900000
        lens 90000
        let 2500000
        let's 1800000
        lets 60000
        """.trimIndent()

    /** The real `en_3p2_5r4c` bands of the keys these buffers touch. */
    private val boardBands = mapOf(
        'i' to "(io",
        'l' to ";l:",
        'e' to "aev",
        't' to "btn",
        'w' to "mwk",
        'h' to "sh)"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mock()
        assetManager = mock()
        languageManager = mock()
        cacheMemoryManager = mock()

        wordNormalizer = mock()
        whenever(wordNormalizer.stripDiacritics(any())).thenAnswer { it.arguments[0] as String }
        whenever(wordNormalizer.canonicalizeApostrophes(any())).thenAnswer { it.arguments[0] as String }

        wordLearningEngine =
            mock {
                onBlocking { isWordLearned(any()) } doReturn false
                onBlocking { areWordsLearned(any()) } doReturn emptyMap()
                onBlocking { getSimilarLearnedWordsWithFrequency(any(), any(), any()) } doReturn emptyList()
                onBlocking { getLearnedWordsForLanguage(any()) } doReturn emptyList()
                onBlocking { removeWord(any()) } doReturn Result.success(false)
            }

        wordFrequencyRepository =
            mock {
                onBlocking { getFrequency(any(), any()) } doReturn 0
                onBlocking { getFrequencies(any(), any()) } doReturn emptyMap()
                onBlocking { topWords(any(), any()) } doReturn emptyList()
                onBlocking { forget(any(), any()) } doReturn false
            }

        blacklistRepository = mock { onBlocking { getAll() } doReturn emptySet() }

        val suggestionCache = ManagedCache<String, List<SpellingSuggestion>>("t_sug", 500, null)
        val dictionaryCache = ManagedCache<String, Boolean>("t_dict", 1000, null)
        whenever(
            cacheMemoryManager.createCache<String, List<SpellingSuggestion>>(
                eq("spell_suggestions"),
                eq(500),
                anyOrNull()
            )
        ).thenReturn(suggestionCache)
        whenever(
            cacheMemoryManager.createCache<String, Boolean>(eq("dictionary_cache"), eq(1000), anyOrNull())
        ).thenReturn(dictionaryCache)

        val languageFlow = MutableStateFlow("en")
        whenever(languageManager.currentLanguage).thenReturn(languageFlow)
        whenever(languageManager.currentLayoutLanguage).thenReturn(languageFlow)
        whenever(languageManager.activeLanguages).thenReturn(MutableStateFlow(listOf("en")))
        whenever(languageManager.effectiveDictionaryLanguages).thenReturn(MutableStateFlow(listOf("en")))
        whenever(languageManager.keyPositions).thenReturn(MutableStateFlow<Map<Char, PointF>>(emptyMap()))

        whenever(context.assets).thenReturn(assetManager)
        val urikBytes = TestUrikBuilder.buildUrikFromText(testDictionary)
        whenever(assetManager.open("dictionaries/en.urik")).thenAnswer { ByteArrayInputStream(urikBytes) }

        spellCheckManager =
            SpellCheckManager(
                context = context,
                languageManager = languageManager,
                wordLearningEngine = wordLearningEngine,
                wordFrequencyRepository = wordFrequencyRepository,
                cacheMemoryManager = cacheMemoryManager,
                blacklistRepository = blacklistRepository,
                ioDispatcher = testDispatcher,
                wordNormalizer = wordNormalizer
            )
        spellCheckManager.setClusterBands(boardBands)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private suspend fun typed(vararg history: Pair<String, Int>) {
        whenever(wordFrequencyRepository.topWords(eq("en"), any()))
            .thenReturn(history.sortedByDescending { it.second })
        whenever(wordFrequencyRepository.getFrequencies(any(), eq("en")))
            .thenReturn(history.toMap())
    }

    private suspend fun bar(buffer: String): List<String> =
        spellCheckManager.getSpellingSuggestionsWithConfidence(buffer).map { it.word }

    @Test
    fun `one tap on the io key leads with the word it spells`() = runTest {
        // The row 白い熊 reported: "in it is on I" — every rival a word typed far more often, and every one
        // of them a guess at a SECOND tap that has not happened.
        typed("in" to 40, "it" to 30, "is" to 20, "on" to 15)

        val words = bar("i")

        assertEquals("the tapped word must lead, got: $words", "i", words.first())
        assertTrue("the predictions must still be offered, got: $words", "in" in words)
    }

    @Test
    fun `three taps lead with the word they spell, not the words they begin`() = runTest {
        // "let's language lens languages let" — the contraction is the promoted "lets", the rest the
        // band-prefix reach of 白い熊's own history.
        typed("language" to 30, "lets" to 20, "lens" to 12, "languages" to 8)

        val words = bar("let")

        assertEquals("the tapped word must lead, got: $words", "let", words.first())
        assertTrue("the longer predictions must survive, got: $words", "language" in words)
        assertTrue("the promoted contraction too, got: $words", "let's" in words)
    }

    @Test
    fun `the held-back predictions keep the order they had`() = runTest {
        typed("language" to 30, "lens" to 12, "languages" to 8)

        val words = bar("let")

        assertTrue(
            "your own counts still rank the predictions among themselves, got: $words",
            words.indexOf("language") < words.indexOf("lens") &&
                words.indexOf("lens") < words.indexOf("languages")
        )
    }

    @Test
    fun `only the best word the taps spell displaces the predictions`() = runTest {
        // The narrow reading of the rule, and the reason for it: those three taps spell six real words on
        // the real `en.urik` ("let lab lan len lat leb"), so putting every one of them ahead of the row
        // would bury the word 白い熊 actually types behind five rarities. Only "let" moves.
        typed("language" to 30)

        val words = bar("let")

        assertEquals("let", words.first())
        assertTrue(
            "a rare word of the same length stays where its own standing put it, got: $words",
            words.indexOf("lab") > words.indexOf("language")
        )
    }

    @Test
    fun `a strongly preferred form still leads the word the taps spell`() = runTest {
        // 白い熊, 2026-10-20: two taps on `(io` `mwk` MEAN "I'm", though those same taps spell "ok". The
        // rule below it only reorders the rest — "ok" now leads the word the taps merely begin.
        typed("implement" to 6)

        val words = bar("iw")

        assertEquals("I'm still leads, got: $words", "I'm", words.first())
        assertTrue(
            "but the word the taps spell comes next, got: $words",
            words.indexOf("ok") in 1 until (words.indexOf("implement").takeIf { it >= 0 } ?: words.size)
        )
    }

    @Test
    fun `a buffer with a typed apostrophe is left to its own ladder`() = runTest {
        // There the form the taps MEAN is longer than the taps: i-t-'-h is "it's", and holding every longer
        // form back would bury it under the possessive "it'" the walk synthesizes at exactly four taps.
        typed("in" to 9, "it" to 2)

        val words = bar("it'h")

        assertEquals("it's must still lead, got: $words", "it's", words.first())
    }
}
