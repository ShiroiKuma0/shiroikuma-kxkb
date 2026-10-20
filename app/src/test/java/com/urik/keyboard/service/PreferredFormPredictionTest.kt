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
 * The strongly preferred forms (白い熊, 2026-10-20): on 白い熊's own en 3+2 cluster board, two taps on the
 * `(io` and `mwk` keys ARE "I'm" and three on `(io` `btn` `sh)` ARE "it's" — whatever else the bands allow.
 *
 * Neither can come out of the dictionary walk, because the apostrophe is on no band: those taps spell the
 * bare words "im" and "ith", and the contraction has to be surfaced from the curated table instead. It was,
 * but one rung too low — a dictionary word 白い熊 has typed outranks anything ranked on dictionary frequency
 * ([SpellCheckManager.learnedHeavyConfidence]), so once typed history began predicting, "implement" led the
 * two taps and "other" the three. And with the apostrophe actually TAPPED, the possessive synthesized over
 * every head the taps allow put the much commoner "in" in front of the real thing: "in's" before "it's".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreferredFormPredictionTest {
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
     * The frequencies are the real `en.urik` ones, so the rivals are as strong here as on the phone: "it"
     * and "in" are among the commonest words in English and "it's" is three orders of magnitude rarer, which
     * is exactly why evidence has to beat frequency on these taps.
     */
    private val testDictionary =
        """
        it 13300606
        in 7226600
        on 4811817
        ok 391852
        its 185913
        other 588500
        implement 1319
        it's 2780
        i'm 1616
        ins 820
        ith 296
        im 6712
        """.trimIndent()

    /** The keys as they are labelled on the board, each mapped by the letter that lands in the buffer. */
    private val boardBands = mapOf(
        'i' to "(io",
        'w' to "mwk",
        't' to "btn",
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
            }

        wordFrequencyRepository =
            mock {
                onBlocking { getFrequency(any(), any()) } doReturn 0
                onBlocking { getFrequencies(any(), any()) } doReturn emptyMap()
                onBlocking { topWords(any(), any()) } doReturn emptyList()
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
        whenever(wordFrequencyRepository.getFrequencies(any(), eq("en"))).thenReturn(history.toMap())
    }

    private suspend fun bar(buffer: String): List<String> =
        spellCheckManager.getSpellingSuggestionsWithConfidence(buffer).map { it.word }

    @Test
    fun `two taps on the io and mwk keys lead with I'm`() = runTest {
        // The buffer is the keys' centre letters, so it reads "iw" — and the word 白い熊 has typed matches
        // those bands as a prefix, which is what used to take the row.
        typed("implement" to 6)

        val words = bar("iw")

        assertEquals("I'm must lead, got: $words", "I'm", words.first())
    }

    @Test
    fun `three taps on io btn and sh lead with it's`() = runTest {
        typed("other" to 11)

        val words = bar("ith")

        assertEquals("it's must lead, got: $words", "it's", words.first())
    }

    @Test
    fun `a typed apostrophe leads with the real contraction, not a possessive hung on another head`() = runTest {
        // "in" is the commoner word and the one 白い熊 has typed more often, so every ladder that ranks by a
        // count put its possessive first. "it's" is what the taps say.
        typed("in" to 9, "it" to 2)

        val words = bar("it'h")

        assertEquals("it's must lead, got: $words", "it's", words.first())
        assertTrue(
            "a possessive on another head may still be offered, but under it, got: $words",
            words.indexOf("in's") < 0 || words.indexOf("in's") > words.indexOf("it's")
        )
    }

    @Test
    fun `the preferred form is offered once, with its own capital`() = runTest {
        typed("implement" to 6)

        val words = bar("iw")

        assertEquals("exactly one I'm, got: $words", 1, words.count { it.equals("I'm", ignoreCase = true) })
        assertTrue("the capital-I form is the one offered, got: $words", "I'm" in words)
    }

    @Test
    fun `the dictionary words those taps spell are still offered`() = runTest {
        // A preferred form leads the row; it does not empty it. Two taps that could be "ok" must still
        // offer "ok", or the preference would cost the words the walk really found.
        typed()

        val words = bar("iw")

        assertEquals("I'm", words.first())
        assertTrue("the walk's own words must survive, got: $words", "ok" in words)
    }

    @Test
    fun `a word you told the keyboard to forget is not offered as a preferred form`() = runTest {
        spellCheckManager.blacklistSuggestion("it's")

        val words = bar("ith")

        assertTrue("a blocked form stays blocked, got: $words", "it's" !in words)
    }
}
