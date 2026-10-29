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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A word you have typed is a word you have typed (白い熊, 2026-10-20): which store happens to hold it is not
 * something a typist should be able to feel.
 *
 * The bands here are 白い熊's own 3+2 cluster board (`en_3p2_5r4c`), so the buffers are the real ones: `r`
 * rides the `qfr` key, `o` the `iox` key, `m` the `mwk` key, `n` the `btn` key — which is why typing
 * "recommendation" shows "feciwwetdeti…" in the field. Before this, the bar was EMPTY at six, seven and
 * eight taps however often the word had been typed: a dictionary word is never written to the learned store
 * (the one path that completes past the taps), and the DAWG walk emits only words of exactly the tapped
 * length, so "recommend" could not appear until the ninth tap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TypedHistoryPredictionTest {
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
     * `rec` is a real word of exactly three taps and carries the frequency of the commonest English words —
     * the only band in which the old probationary rung actually lost, since a cluster candidate's score
     * tops out at 0.99 and a word typed once used to score 0.96.
     */
    private val testDictionary =
        """
        rec 20000000
        reco 900
        recommend 500
        recommendation 300
        recommended 280
        felix 200
        floss 50
        hello 1000
        """.trimIndent()

    private val boardBands = mapOf(
        'f' to "qfr",
        'e' to "aev",
        'c' to "cls",
        'i' to "iox",
        'w' to "mwk",
        't' to "btn",
        'd' to "gdy"
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
    }

    private suspend fun bar(buffer: String): List<String> =
        spellCheckManager.getSpellingSuggestionsWithConfidence(buffer).map { it.word }

    @Test
    fun `a dictionary word you have typed is offered long before its own length`() = runTest {
        typed("recommend" to 4)

        // Six taps: r-e-c-o-m-m, the field showing "feciww".
        val words = bar("feciww")

        assertTrue("your own word must be offered from six taps, got: $words", "recommend" in words)
        assertEquals("and lead the row, got: $words", "recommend", words.first())
    }

    @Test
    fun `without that history the same six taps answer nothing`() = runTest {
        // The state this change exists to end: no dictionary word is six letters long here, the walk emits
        // only words of the tapped length, and the learned store never holds a word the dictionary knows.
        val words = bar("feciww")

        assertFalse("nothing should reach the bar yet, got: $words", "recommend" in words)
    }

    @Test
    fun `a word typed once leads every other prediction, under the word the taps spell`() = runTest {
        // 白い熊, 2026-10-20: the ladder has no probationary rung — a typo costs one long-press, while a
        // word you really use cost a row of taps every time it sat second. The rival at these three taps is
        // the band's own exact-length word at the frequency of the commonest English words — and since
        // 2026-10-29 that one word is the exception: "rec" is what three taps SPELL, "recommend" a guess at
        // the six that have not happened, so the full word leads and your own word follows it
        // (SpellCheckManager.holdBackBeyondTaps).
        typed("recommend" to 1)

        val words = bar("fec")

        assertEquals("the word the taps spell leads, got: $words", "rec", words.first())
        assertEquals("your own word comes straight after it, got: $words", "recommend", words[1])
    }

    @Test
    fun `the word you type most often leads`() = runTest {
        typed("recommend" to 2, "recommendation" to 9, "recommended" to 3)

        val words = bar("feciww")

        assertEquals("ranking follows your own counts, got: $words", "recommendation", words.first())
        assertTrue("recommend must still be offered, got: $words", "recommend" in words)
        val longerIsHigher = words.indexOf("recommendation") < words.indexOf("recommend")
        assertTrue("nine beats two, got: $words", longerIsHigher)
    }

    @Test
    fun `a word you told the keyboard to forget never comes back through your history`() = runTest {
        typed("recommend" to 12)
        spellCheckManager.blacklistSuggestion("recommend")

        val words = bar("feciww")

        assertFalse("a blocked word stays blocked, got: $words", "recommend" in words)
    }

    @Test
    fun `history reaches only the cluster boards`() = runTest {
        // A flat board publishes no ambiguous bands: there the typed word already leads the bar, and lifting
        // history to the top of it would start auto-replacing what was typed.
        spellCheckManager.setClusterBands(emptyMap())
        typed("recommend" to 12)

        val words = bar("feciww")

        assertFalse("no band, no band-prefix prediction, got: $words", "recommend" in words)
    }

    @Test
    fun `long-pressing a candidate of your own forgets it without blocking the word`() = runTest {
        whenever(wordFrequencyRepository.forget(eq("recommend"), eq("en"))).thenReturn(true)

        spellCheckManager.removeSuggestion("recommend")

        verify(wordFrequencyRepository).forget("recommend", "en")
        assertFalse(
            "forgetting your own history must not blacklist the word",
            spellCheckManager.isWordBlacklisted("recommend")
        )
        verify(blacklistRepository, never()).add(any())
    }

    @Test
    fun `long-pressing a candidate with no history of yours blocks it as before`() = runTest {
        spellCheckManager.removeSuggestion("hello")

        verify(wordFrequencyRepository).forget("hello", "en")
        assertTrue(
            "a candidate the dictionary alone offered is still blocked",
            spellCheckManager.isWordBlacklisted("hello")
        )
    }

    @Test
    fun `the second long-press blocks what the first one forgot`() = runTest {
        whenever(wordFrequencyRepository.forget(eq("recommend"), eq("en"))).thenReturn(true)
        spellCheckManager.removeSuggestion("recommend")
        assertFalse(spellCheckManager.isWordBlacklisted("recommend"))

        // Nothing of yours stands behind it any more, so the press now means the other thing.
        whenever(wordFrequencyRepository.forget(eq("recommend"), eq("en"))).thenReturn(false)
        spellCheckManager.removeSuggestion("recommend")

        assertTrue(spellCheckManager.isWordBlacklisted("recommend"))
    }
}
