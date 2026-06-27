@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.view.inputmethod.InputConnection
import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.data.database.LearnedWord
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.utils.CaseTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SuggestionPipelineTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockIc: InputConnection
    private lateinit var mockSwipeDetector: SwipeDetector
    private lateinit var mockSwipeSpaceManager: SwipeSpaceManager
    private lateinit var mockTextInputProcessor: TextInputProcessor
    private lateinit var mockSpellCheckManager: SpellCheckManager
    private lateinit var mockWordLearningEngine: WordLearningEngine
    private lateinit var mockWordFrequencyRepository: WordFrequencyRepository
    private lateinit var mockLanguageManager: LanguageManager
    private lateinit var mockCaseTransformer: CaseTransformer
    private lateinit var mockScriptConverterRegistry: ScriptConverterRegistry

    private lateinit var inputState: InputStateManager
    private lateinit var outputBridge: OutputBridge
    private lateinit var pipeline: SuggestionPipeline

    private var capturedSuggestions: List<String> = emptyList()
    private var suggestionsCleared = false
    private var capturedDegradedIndicator: Boolean? = null

    @Before
    fun setup() = runBlocking {
        Dispatchers.setMain(testDispatcher)

        mockIc = mock()
        mockSwipeDetector = mock()
        mockSwipeSpaceManager = mock()
        mockTextInputProcessor = mock()
        mockSpellCheckManager = mock()
        mockWordLearningEngine = mock()
        mockWordFrequencyRepository = mock()
        mockLanguageManager = mock()
        mockCaseTransformer = mock()
        mockScriptConverterRegistry = mock()

        whenever(mockIc.beginBatchEdit()).thenReturn(true)
        whenever(mockIc.endBatchEdit()).thenReturn(true)
        whenever(mockIc.commitText(any(), any())).thenReturn(true)
        whenever(mockIc.deleteSurroundingText(any(), any())).thenReturn(true)
        whenever(mockIc.finishComposingText()).thenReturn(true)
        whenever(mockLanguageManager.currentLanguage).thenReturn(
            kotlinx.coroutines.flow.MutableStateFlow("en")
        )
        whenever(mockWordLearningEngine.learnWord(any(), any())).thenReturn(Result.success(null as LearnedWord?))
        whenever(mockSpellCheckManager.japaneseUserCandidates(any())).thenReturn(emptyList())

        val viewCallback = object : ViewCallback {
            override fun clearSuggestions() {
                suggestionsCleared = true
            }

            override fun updateSuggestions(suggestions: List<String>) {
                capturedSuggestions = suggestions
            }

            override fun setSelectedSuggestion(index: Int) {}

            override fun showDegradedIndicator(degraded: Boolean) {
                capturedDegradedIndicator = degraded
            }
        }

        inputState = InputStateManager(
            viewCallback = viewCallback,
            onShiftStateChanged = {},
            isCapsLockOn = { false },
            cancelDebounceJob = {}
        )

        outputBridge = OutputBridge(
            state = inputState,
            swipeDetector = mockSwipeDetector,
            swipeSpaceManager = mockSwipeSpaceManager,
            icProvider = { mockIc }
        )

        pipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = FakeSuggestionPipelineHost()
        )
    }

    private class FakeSuggestionPipelineHost : SuggestionPipelineHost {
        override fun showSuggestions(): Boolean = true
        override fun effectiveSuggestionCount(): Int = 3
        override fun getKeyboardState(): KeyboardState = KeyboardState()
        override fun shouldAutoCapitalize(text: String): Boolean = false
        override fun currentLanguage(): String = "en"
        override fun currentLayoutLanguage(): String = "en"
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `coordinatePostCommitReplacement learns word on autocorrect undo`() = runTest(testDispatcher) {
        val replacementState = PostCommitReplacementState(
            originalWord = "teh",
            committedWord = "the"
        )
        whenever(mockIc.getTextBeforeCursor(4, 0)).thenReturn("the ")
        whenever(mockTextInputProcessor.getCurrentSettings()).thenReturn(
            com.urik.keyboard.settings.KeyboardSettings()
        )
        whenever(mockSpellCheckManager.isWordInDictionary(any())).thenReturn(false)

        pipeline.coordinatePostCommitReplacement(
            selectedSuggestion = "teh",
            replacementState = replacementState,
            checkAutoCapitalization = {}
        )

        verify(mockWordLearningEngine).learnWord("teh", InputMethod.TYPED)
        verify(mockWordFrequencyRepository, times(3)).incrementFrequency("teh", "en")
    }

    @Test
    fun `coordinatePostCommitReplacement does not learn on non-autocorrect replacement`() = runTest(testDispatcher) {
        val replacementState = PostCommitReplacementState(
            originalWord = "hello",
            committedWord = "hello"
        )
        whenever(mockIc.getTextBeforeCursor(6, 0)).thenReturn("hello ")
        whenever(mockTextInputProcessor.getCurrentSettings()).thenReturn(
            com.urik.keyboard.settings.KeyboardSettings()
        )

        pipeline.coordinatePostCommitReplacement(
            selectedSuggestion = "help",
            replacementState = replacementState,
            checkAutoCapitalization = {}
        )

        verify(mockWordLearningEngine, never()).learnWord(any(), any())
    }

    @Test
    fun `coordinatePostCommitReplacement clears postCommitReplacementState`() = runTest(testDispatcher) {
        inputState.postCommitReplacementState = PostCommitReplacementState("teh", "the")
        whenever(mockIc.getTextBeforeCursor(4, 0)).thenReturn("the ")
        whenever(mockTextInputProcessor.getCurrentSettings()).thenReturn(
            com.urik.keyboard.settings.KeyboardSettings()
        )
        whenever(mockSpellCheckManager.isWordInDictionary(any())).thenReturn(false)

        pipeline.coordinatePostCommitReplacement(
            selectedSuggestion = "teh",
            replacementState = inputState.postCommitReplacementState!!,
            checkAutoCapitalization = {}
        )

        assertNull(inputState.postCommitReplacementState)
    }

    @Test
    fun `coordinatePostCommitReplacement aborts on stale text`() = runTest(testDispatcher) {
        val replacementState = PostCommitReplacementState(
            originalWord = "teh",
            committedWord = "the"
        )
        whenever(mockIc.getTextBeforeCursor(4, 0)).thenReturn("oops")

        pipeline.coordinatePostCommitReplacement(
            selectedSuggestion = "teh",
            replacementState = replacementState,
            checkAutoCapitalization = {}
        )

        verify(mockIc, never()).deleteSurroundingText(any(), any())
        verify(mockWordLearningEngine, never()).learnWord(any(), any())
    }

    @Test
    fun `requestSuggestions forwards isDegradedMode to ViewCallback`() = runTest(testDispatcher) {
        whenever(mockSpellCheckManager.isDegradedMode).thenReturn(true)

        pipeline.requestSuggestions(buffer = "test", inputMethod = InputMethod.TYPED)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, capturedDegradedIndicator)

        whenever(mockSpellCheckManager.isDegradedMode).thenReturn(false)

        pipeline.requestSuggestions(buffer = "test", inputMethod = InputMethod.TYPED)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, capturedDegradedIndicator)
    }

    @Test
    fun `coordinateSuggestionSelection records word usage`() = runTest(testDispatcher) {
        inputState.displayBuffer = "helo"
        inputState.composingRegionStart = 0
        whenever(mockIc.getTextBeforeCursor(any(), any())).thenReturn("helo")
        whenever(mockIc.commitText(any(), any())).thenReturn(true)
        whenever(mockIc.finishComposingText()).thenReturn(true)

        val extractedText = android.view.inputmethod.ExtractedText().apply {
            startOffset = 0
            selectionStart = 4
        }
        whenever(mockIc.getExtractedText(any(), eq(0))).thenReturn(extractedText)

        pipeline.coordinateSuggestionSelection("hello", checkAutoCapitalization = {})

        verify(mockWordFrequencyRepository).incrementFrequency("hello", "en")
        assertEquals("hello", inputState.lastCommittedWord)
    }

    // ---- Japanese FIX 1: a Japanese candidate commits with NO trailing space. ----

    @Test
    fun `coordinateSuggestionSelection commits a Japanese candidate without a trailing space`() =
        runTest(testDispatcher) {
            val japanesePipeline = SuggestionPipeline(
                state = inputState,
                outputBridge = outputBridge,
                textInputProcessor = mockTextInputProcessor,
                spellCheckManager = mockSpellCheckManager,
                wordLearningEngine = mockWordLearningEngine,
                wordFrequencyRepository = mockWordFrequencyRepository,
                languageManager = mockLanguageManager,
                caseTransformer = mockCaseTransformer,
                scriptConverterRegistry = mockScriptConverterRegistry,
                serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
                host = FakeJapanesePipelineHost()
            )
            japanesePipeline.setJapaneseLayout(true)

            inputState.displayBuffer = "とうきょう"
            inputState.composingRegionStart = 0
            whenever(mockIc.getTextBeforeCursor(any(), any())).thenReturn("とうきょう")
            // ja is caseless, so recaseForCommit echoes the surface unchanged.

            japanesePipeline.coordinateSuggestionSelection("東京", checkAutoCapitalization = {})
            testDispatcher.scheduler.advanceUntilIdle()

            verify(mockIc).commitText("東京", 1)
        }

    @Test
    fun `coordinateSuggestionSelection still appends a space for a non-Japanese commit`() =
        runTest(testDispatcher) {
            // Same call without the Japanese layout flag keeps the Latin trailing-space behaviour.
            inputState.displayBuffer = "hel"
            inputState.composingRegionStart = 0
            whenever(mockIc.getTextBeforeCursor(any(), any())).thenReturn("hel")
            whenever(
                mockCaseTransformer.applyCasing(
                    any<SpellingSuggestion>(),
                    any<KeyboardState>(),
                    any<Boolean>(),
                    any<java.util.Locale>()
                )
            ).thenAnswer { inv -> (inv.arguments[0] as SpellingSuggestion).word }

            pipeline.coordinateSuggestionSelection("hello", checkAutoCapitalization = {})
            testDispatcher.scheduler.advanceUntilIdle()

            verify(mockIc).commitText("hello ", 1)
        }

    // ---- Japanese FIX 2: the trailing "＋登録" registration affordance. ----

    @Test
    fun `requestJapaneseSuggestions appends the register affordance after the candidates`() =
        runTest(testDispatcher) {
            val japanesePipeline = SuggestionPipeline(
                state = inputState,
                outputBridge = outputBridge,
                textInputProcessor = mockTextInputProcessor,
                spellCheckManager = mockSpellCheckManager,
                wordLearningEngine = mockWordLearningEngine,
                wordFrequencyRepository = mockWordFrequencyRepository,
                languageManager = mockLanguageManager,
                caseTransformer = mockCaseTransformer,
                scriptConverterRegistry = mockScriptConverterRegistry,
                serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
                host = FakeJapanesePipelineHost()
            )
            japanesePipeline.setJapaneseLayout(true)

            val mockConverter = mock<ScriptConverter>()
            whenever(mockScriptConverterRegistry.forLanguage("ja")).thenReturn(mockConverter)
            whenever(mockConverter.getCandidates("か", "ja")).thenReturn(
                listOf(ConversionCandidate(surface = "化", reading = "か", frequency = 19992, source = "dictionary"))
            )
            whenever(mockSpellCheckManager.getSpellingSuggestionsWithConfidence("か")).thenReturn(emptyList())

            inputState.updateDisplayBuffer("か")
            japanesePipeline.requestSuggestions("か", InputMethod.TYPED)
            testDispatcher.scheduler.advanceUntilIdle()

            // The affordance is the LAST entry, and isJapaneseRegisterAffordance recognises it (and only it).
            assertEquals("＋登録", capturedSuggestions.last())
            assert(japanesePipeline.isJapaneseRegisterAffordance("＋登録"))
            assert(!japanesePipeline.isJapaneseRegisterAffordance("化"))
        }

    @Test
    fun `requestJapaneseSuggestions always keeps the plain reading even when many conversions are offered`() =
        runTest(testDispatcher) {
            // BUG A: a mis-learned surface (or simply many conversions) must never push the literal typed
            // reading out of the row. Offer far more conversions than the cap and assert the hiragana reading
            // survives.
            val japanesePipeline = SuggestionPipeline(
                state = inputState,
                outputBridge = outputBridge,
                textInputProcessor = mockTextInputProcessor,
                spellCheckManager = mockSpellCheckManager,
                wordLearningEngine = mockWordLearningEngine,
                wordFrequencyRepository = mockWordFrequencyRepository,
                languageManager = mockLanguageManager,
                caseTransformer = mockCaseTransformer,
                scriptConverterRegistry = mockScriptConverterRegistry,
                serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
                host = FakeJapanesePipelineHost()
            )
            japanesePipeline.setJapaneseLayout(true)

            val mockConverter = mock<ScriptConverter>()
            whenever(mockScriptConverterRegistry.forLanguage("ja")).thenReturn(mockConverter)
            // 20 high-frequency conversions for しろい — far more than effectiveSuggestionCount (5).
            val manyConversions = (1..20).map {
                ConversionCandidate(surface = "白$it", reading = "しろい", frequency = 20000L - it, source = "dictionary")
            }
            whenever(mockConverter.getCandidates("しろい", "ja")).thenReturn(manyConversions)
            whenever(mockSpellCheckManager.getSpellingSuggestionsWithConfidence("しろい")).thenReturn(emptyList())

            inputState.updateDisplayBuffer("しろい")
            japanesePipeline.requestSuggestions("しろい", InputMethod.TYPED)
            testDispatcher.scheduler.advanceUntilIdle()

            assert(capturedSuggestions.contains("しろい")) {
                "The plain typed reading must always remain in the row, was: $capturedSuggestions"
            }
        }

    // ---- Bug D: a standalone English pronoun "i" cluster candidate commits as "I". ----

    @Test
    fun `coordinateSuggestionSelection capitalizes a standalone i pronoun to I`() = runTest(testDispatcher) {
        inputState.displayBuffer = "i"
        inputState.composingRegionStart = 0
        whenever(mockIc.getTextBeforeCursor(any(), any())).thenReturn("i")
        // recaseForCommit echoes the lowercase "i"; the pronoun correction then capitalizes it to "I".
        whenever(
            mockCaseTransformer.applyCasing(
                any<SpellingSuggestion>(),
                any<KeyboardState>(),
                any<Boolean>(),
                any<java.util.Locale>()
            )
        ).thenAnswer { inv -> (inv.arguments[0] as SpellingSuggestion).word }

        pipeline.coordinateSuggestionSelection("i", checkAutoCapitalization = {})
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockIc).commitText("I ", 1)
    }

    @Test
    fun `coordinateSuggestionSelection leaves a non-pronoun word unchanged`() = runTest(testDispatcher) {
        inputState.displayBuffer = "hel"
        inputState.composingRegionStart = 0
        whenever(mockIc.getTextBeforeCursor(any(), any())).thenReturn("hel")
        whenever(
            mockCaseTransformer.applyCasing(
                any<SpellingSuggestion>(),
                any<KeyboardState>(),
                any<Boolean>(),
                any<java.util.Locale>()
            )
        ).thenAnswer { inv -> (inv.arguments[0] as SpellingSuggestion).word }

        pipeline.coordinateSuggestionSelection("hello", checkAutoCapitalization = {})
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockIc).commitText("hello ", 1)
    }

    // ---- Bug A: no next-word (bigram) prediction at the start of input / on an empty line. ----

    @Test
    fun `showBigramPredictions shows the custom row not a bigram when there is no preceding word`() =
        runTest(testDispatcher) {
            // A stale lastCommittedWord from an earlier line would otherwise resurrect a bigram, but the field
            // is empty before the cursor (line start) -> no bigram; the custom default row is shown instead.
            inputState.lastCommittedWord = "hello"
            inputState.setCustomSuggestions(listOf("brb", "omw"))
            whenever(mockIc.getTextBeforeCursor(any(), any())).thenReturn("")
            whenever(mockWordFrequencyRepository.getBigramPredictions(any(), any(), any()))
                .thenReturn(setOf("world"))

            pipeline.showBigramPredictions()
            testDispatcher.scheduler.advanceUntilIdle()

            // The bigram was never fetched; the bar shows the custom default row.
            verify(mockWordFrequencyRepository, never()).getBigramPredictions(any(), any(), any())
            assertEquals(listOf("brb", "omw"), capturedSuggestions)
        }

    @Test
    fun `showBigramPredictions emits the bigram when a preceding word is present`() = runTest(testDispatcher) {
        inputState.lastCommittedWord = "hello"
        whenever(mockIc.getTextBeforeCursor(any(), any())).thenReturn("hello ")
        whenever(mockWordFrequencyRepository.getBigramPredictions(any(), any(), any()))
            .thenReturn(setOf("world"))
        whenever(mockSpellCheckManager.isWordBlacklisted(any())).thenReturn(false)
        whenever(mockCaseTransformer.applyCasingToSuggestions(any(), any(), any(), any()))
            .thenAnswer { inv ->
                @Suppress("UNCHECKED_CAST")
                (inv.arguments[0] as List<SpellingSuggestion>).map { it.word }
            }

        pipeline.showBigramPredictions()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockWordFrequencyRepository).getBigramPredictions(any(), any(), any())
        assertEquals(listOf("world"), capturedSuggestions)
    }

    @Test
    fun `capitalizeSuggestions skips capitalization for Arabic`() {
        val arabicPipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = object : SuggestionPipelineHost {
                override fun showSuggestions() = true
                override fun effectiveSuggestionCount() = 3
                override fun getKeyboardState() = KeyboardState()
                override fun shouldAutoCapitalize(text: String) = false
                override fun currentLanguage() = "ar"
                override fun currentLayoutLanguage() = "ar"
            }
        )
        val suggestions = listOf(
            SpellingSuggestion("مرحبا", 0.9, 0, "dictionary", preserveCase = false)
        )

        val result = arabicPipeline.capitalizeSuggestions(suggestions, isSentenceStart = true)

        assertEquals(listOf("مرحبا"), result)
    }

    @Test
    fun `capitalizeSuggestions skips capitalization for Persian`() {
        val faPipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = object : SuggestionPipelineHost {
                override fun showSuggestions() = true
                override fun effectiveSuggestionCount() = 3
                override fun getKeyboardState() = KeyboardState()
                override fun shouldAutoCapitalize(text: String) = false
                override fun currentLanguage() = "fa"
                override fun currentLayoutLanguage() = "fa"
            }
        )
        val suggestions = listOf(
            SpellingSuggestion("سلام", 0.9, 0, "dictionary", preserveCase = false)
        )

        val result = faPipeline.capitalizeSuggestions(suggestions, isSentenceStart = true)

        assertEquals(listOf("سلام"), result)
    }

    @Test
    fun `capitalizeSuggestions skips capitalization for Japanese`() {
        val jaPipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = object : SuggestionPipelineHost {
                override fun showSuggestions() = true
                override fun effectiveSuggestionCount() = 3
                override fun getKeyboardState() = KeyboardState()
                override fun shouldAutoCapitalize(text: String) = false
                override fun currentLanguage() = "ja"
                override fun currentLayoutLanguage() = "ja"
            }
        )
        val suggestions = listOf(
            SpellingSuggestion("こんにちは", 0.9, 0, "dictionary", preserveCase = false)
        )

        val result = jaPipeline.capitalizeSuggestions(suggestions, isSentenceStart = true)

        assertEquals(listOf("こんにちは"), result)
    }

    @Test
    fun `capitalizeSuggestions capitalizes for English at sentence start`() {
        val suggestions = listOf(
            SpellingSuggestion("hello", 0.9, 0, "dictionary", preserveCase = false)
        )
        whenever(mockCaseTransformer.applyCasingToSuggestions(any(), any(), any(), any()))
            .thenReturn(listOf("Hello"))

        val result = pipeline.capitalizeSuggestions(suggestions, isSentenceStart = true)

        assertEquals(listOf("Hello"), result)
    }

    /**
     * Fix 2: under auto-capitalization at sentence start (NO manual shift), the bar must already show the
     * Capitalized form that the commit will insert — display and commit must not diverge. Uses a real
     * CaseTransformer so the actual casing is exercised end to end.
     */
    @Test
    fun `capitalizeSuggestions shows capitalized form at sentence start with real transformer`() {
        val realPipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = CaseTransformer(),
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = FakeSuggestionPipelineHost()
        )
        inputState.isCurrentWordAtSentenceStart = true
        val suggestions = listOf(SpellingSuggestion("hello", 0.9, 0, "dictionary", preserveCase = false))

        val result = realPipeline.capitalizeSuggestions(suggestions)

        assertEquals(listOf("Hello"), result)
    }

    /**
     * Fix 2: when the user pressed Shift then typed (manual shift recorded on the current word) but the live
     * shift latch has since cleared, the bar must still reflect the shift and show the Capitalized form.
     */
    @Test
    fun `capitalizeSuggestions reflects manual shift after latch cleared`() {
        val realPipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = CaseTransformer(),
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = FakeSuggestionPipelineHost()
        )
        inputState.isCurrentWordManualShifted = true
        inputState.isCurrentWordAtSentenceStart = false
        val suggestions = listOf(SpellingSuggestion("hello", 0.9, 0, "dictionary", preserveCase = false))

        val result = realPipeline.capitalizeSuggestions(suggestions)

        assertEquals(listOf("Hello"), result)
    }

    private class FakeJapanesePipelineHost : SuggestionPipelineHost {
        override fun showSuggestions(): Boolean = true
        override fun effectiveSuggestionCount(): Int = 5
        override fun getKeyboardState(): KeyboardState = KeyboardState()
        override fun shouldAutoCapitalize(text: String): Boolean = false
        override fun currentLanguage(): String = "ja"
        override fun currentLayoutLanguage(): String = "ja"
    }

    @Test
    fun `requestJapaneseSuggestions appends hiragana reading and katakana as last candidates`() =
        runTest(testDispatcher) {
            val japanesePipeline = SuggestionPipeline(
                state = inputState,
                outputBridge = outputBridge,
                textInputProcessor = mockTextInputProcessor,
                spellCheckManager = mockSpellCheckManager,
                wordLearningEngine = mockWordLearningEngine,
                wordFrequencyRepository = mockWordFrequencyRepository,
                languageManager = mockLanguageManager,
                caseTransformer = mockCaseTransformer,
                scriptConverterRegistry = mockScriptConverterRegistry,
                serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
                host = FakeJapanesePipelineHost()
            )
            japanesePipeline.setJapaneseLayout(true)

            val mockConverter = mock<ScriptConverter>()
            whenever(mockScriptConverterRegistry.forLanguage("ja")).thenReturn(mockConverter)
            whenever(mockConverter.getCandidates("か", "ja")).thenReturn(
                listOf(ConversionCandidate(surface = "化", reading = "か", frequency = 19992, source = "dictionary"))
            )
            whenever(mockSpellCheckManager.getSpellingSuggestionsWithConfidence("か")).thenReturn(emptyList())

            inputState.updateDisplayBuffer("か")
            japanesePipeline.requestSuggestions("か", InputMethod.TYPED)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf("化", "か", "カ"), capturedSuggestions.take(3))
        }

    @Test
    fun `requestJapaneseSuggestions converts using layout language when primary language differs`() =
        runTest(testDispatcher) {
            // Regression: with ja active but not primary (the normal case once the language cap is
            // lifted), the converter must be looked up by the layout language ("ja"), not the primary
            // ("en"). Previously this returned no converter, so only kana candidates were offered.
            val japanesePipeline = SuggestionPipeline(
                state = inputState,
                outputBridge = outputBridge,
                textInputProcessor = mockTextInputProcessor,
                spellCheckManager = mockSpellCheckManager,
                wordLearningEngine = mockWordLearningEngine,
                wordFrequencyRepository = mockWordFrequencyRepository,
                languageManager = mockLanguageManager,
                caseTransformer = mockCaseTransformer,
                scriptConverterRegistry = mockScriptConverterRegistry,
                serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
                host = object : SuggestionPipelineHost {
                    override fun showSuggestions() = true
                    override fun effectiveSuggestionCount() = 5
                    override fun getKeyboardState() = KeyboardState()
                    override fun shouldAutoCapitalize(text: String) = false
                    override fun currentLanguage() = "en"
                    override fun currentLayoutLanguage() = "ja"
                }
            )
            japanesePipeline.setJapaneseLayout(true)

            val mockConverter = mock<ScriptConverter>()
            whenever(mockScriptConverterRegistry.forLanguage("ja")).thenReturn(mockConverter)
            whenever(mockConverter.getCandidates("か", "ja")).thenReturn(
                listOf(ConversionCandidate(surface = "化", reading = "か", frequency = 19992, source = "dictionary"))
            )
            whenever(mockSpellCheckManager.getSpellingSuggestionsWithConfidence("か")).thenReturn(emptyList())

            inputState.updateDisplayBuffer("か")
            japanesePipeline.requestSuggestions("か", InputMethod.TYPED)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf("化", "か", "カ"), capturedSuggestions.take(3))
        }

    @Test
    fun `requestJapaneseSuggestions katakana slot reflects full buffer`() = runTest(testDispatcher) {
        val japanesePipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = FakeJapanesePipelineHost()
        )
        japanesePipeline.setJapaneseLayout(true)

        val mockConverter = mock<ScriptConverter>()
        whenever(mockScriptConverterRegistry.forLanguage("ja")).thenReturn(mockConverter)
        whenever(mockConverter.getCandidates("がっこう", "ja")).thenReturn(
            listOf(ConversionCandidate(surface = "学校", reading = "がっこう", frequency = 50000, source = "dictionary"))
        )
        whenever(mockSpellCheckManager.getSpellingSuggestionsWithConfidence("がっこう")).thenReturn(emptyList())

        inputState.updateDisplayBuffer("がっこう")
        japanesePipeline.requestSuggestions("がっこう", InputMethod.TYPED)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("学校", "がっこう", "ガッコウ"), capturedSuggestions.take(3))
    }

    @Test
    fun `requestJapaneseSuggestions deduplicates if hiragana matches a kanji candidate`() = runTest(testDispatcher) {
        val japanesePipeline = SuggestionPipeline(
            state = inputState,
            outputBridge = outputBridge,
            textInputProcessor = mockTextInputProcessor,
            spellCheckManager = mockSpellCheckManager,
            wordLearningEngine = mockWordLearningEngine,
            wordFrequencyRepository = mockWordFrequencyRepository,
            languageManager = mockLanguageManager,
            caseTransformer = mockCaseTransformer,
            scriptConverterRegistry = mockScriptConverterRegistry,
            serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            host = FakeJapanesePipelineHost()
        )
        japanesePipeline.setJapaneseLayout(true)

        val mockConverter = mock<ScriptConverter>()
        whenever(mockScriptConverterRegistry.forLanguage("ja")).thenReturn(mockConverter)
        whenever(mockConverter.getCandidates("か", "ja")).thenReturn(
            listOf(ConversionCandidate(surface = "か", reading = "か", frequency = 1000, source = "dictionary"))
        )
        whenever(mockSpellCheckManager.getSpellingSuggestionsWithConfidence("か")).thenReturn(emptyList())

        inputState.updateDisplayBuffer("か")
        japanesePipeline.requestSuggestions("か", InputMethod.TYPED)
        testDispatcher.scheduler.advanceUntilIdle()

        val suggestions = capturedSuggestions
        assertEquals(1, suggestions.count { it == "か" })
        assert(suggestions.contains("カ")) { "katakana カ must be present" }
    }

    @Test
    fun `requestJapaneseSuggestions excludes blacklisted conversion candidate but keeps hiragana and katakana`() =
        runTest(testDispatcher) {
            val japanesePipeline = SuggestionPipeline(
                state = inputState,
                outputBridge = outputBridge,
                textInputProcessor = mockTextInputProcessor,
                spellCheckManager = mockSpellCheckManager,
                wordLearningEngine = mockWordLearningEngine,
                wordFrequencyRepository = mockWordFrequencyRepository,
                languageManager = mockLanguageManager,
                caseTransformer = mockCaseTransformer,
                scriptConverterRegistry = mockScriptConverterRegistry,
                serviceScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
                host = FakeJapanesePipelineHost()
            )
            japanesePipeline.setJapaneseLayout(true)

            val mockConverter = mock<ScriptConverter>()
            whenever(mockScriptConverterRegistry.forLanguage("ja")).thenReturn(mockConverter)
            whenever(mockConverter.getCandidates("か", "ja")).thenReturn(
                listOf(ConversionCandidate(surface = "化", reading = "か", frequency = 19992, source = "dictionary"))
            )
            whenever(mockSpellCheckManager.getSpellingSuggestionsWithConfidence("か")).thenReturn(emptyList())
            whenever(mockSpellCheckManager.isWordBlacklisted("化")).thenReturn(true)

            inputState.updateDisplayBuffer("か")
            japanesePipeline.requestSuggestions("か", InputMethod.TYPED)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(false, capturedSuggestions.contains("化"))
            assertEquals(listOf("か", "カ"), capturedSuggestions.take(2))
        }

    @Test
    fun `requestSuggestions isSuggestionsDisabled emits no suggestions`() = runTest(testDispatcher) {
        inputState.isSuggestionsDisabled = true
        pipeline.requestSuggestions("hello", InputMethod.TYPED)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockTextInputProcessor, never()).processWordInput(any(), any())
        assertEquals(emptyList<String>(), capturedSuggestions)
    }

    @Test
    fun `coordinateStateTransition isSuggestionsDisabled does not update suggestions`() {
        inputState.isSuggestionsDisabled = true
        val wordState = WordState(
            buffer = "hello",
            suggestions = listOf(SpellingSuggestion("hello", 0.9, 0, "dict", preserveCase = false))
        )

        pipeline.coordinateStateTransition(wordState)

        assertEquals(emptyList<String>(), capturedSuggestions)
    }

    @Test
    fun `showBigramPredictions isSuggestionsDisabled emits no predictions`() = runTest(testDispatcher) {
        inputState.isSuggestionsDisabled = true
        inputState.lastCommittedWord = "hello"
        whenever(mockWordFrequencyRepository.getBigramPredictions(any(), any(), any()))
            .thenReturn(setOf("world"))

        pipeline.showBigramPredictions()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<String>(), capturedSuggestions)
    }

    // ---- Bug C: the expandable "more candidates" pane includes the custom row after the predictions. ----

    @Test
    fun `expandedClusterCandidates appends the custom row after the DAWG predictions`() {
        inputState.displayBuffer = "Deh"
        inputState.setCustomSuggestions(listOf("brb", "omw"))
        whenever(mockSpellCheckManager.clusterCandidatesFor(eq("Deh"), eq("en"), any()))
            .thenReturn(listOf("Yes", "Yeh", "Deg"))
        // Real transformer would re-case; the mock just echoes the words so the assertion is exact.
        whenever(mockCaseTransformer.applyCasingToSuggestions(any(), any(), any(), any()))
            .thenAnswer { inv ->
                @Suppress("UNCHECKED_CAST")
                (inv.arguments[0] as List<SpellingSuggestion>).map { it.word }
            }

        val pane = pipeline.expandedClusterCandidates()

        assertEquals(listOf("Yes", "Yeh", "Deg", "brb", "omw"), pane)
    }

    @Test
    fun `expandedClusterCandidates on empty buffer is the pending row plus the custom row`() {
        inputState.displayBuffer = ""
        inputState.setCustomSuggestions(listOf("brb"))
        inputState.pendingSuggestions = listOf("the", "to")

        val pane = pipeline.expandedClusterCandidates()

        assertEquals(listOf("the", "to", "brb"), pane)
    }

    @Test
    fun `expandedClusterCandidates without a custom row returns predictions unchanged`() {
        inputState.displayBuffer = "Deh"
        whenever(mockSpellCheckManager.clusterCandidatesFor(eq("Deh"), eq("en"), any()))
            .thenReturn(listOf("Yes", "Yeh"))
        whenever(mockCaseTransformer.applyCasingToSuggestions(any(), any(), any(), any()))
            .thenAnswer { inv ->
                @Suppress("UNCHECKED_CAST")
                (inv.arguments[0] as List<SpellingSuggestion>).map { it.word }
            }

        val pane = pipeline.expandedClusterCandidates()

        assertEquals(listOf("Yes", "Yeh"), pane)
    }
}
