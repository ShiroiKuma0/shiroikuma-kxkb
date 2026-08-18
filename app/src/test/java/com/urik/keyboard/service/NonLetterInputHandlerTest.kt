package com.urik.keyboard.service

import com.urik.keyboard.settings.KeyboardSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class NonLetterInputHandlerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var handler: NonLetterInputHandler
    private lateinit var realInputState: InputStateManager
    private lateinit var mockOutputBridge: OutputBridge
    private lateinit var mockSuggestionPipeline: SuggestionPipeline
    private lateinit var mockAutoCorrectionEngine: AutoCorrectionEngine
    private lateinit var mockSwipeSpaceManager: SwipeSpaceManager
    private lateinit var mockLanguageManager: LanguageManager
    private lateinit var mockCandidateBarController: CandidateBarController
    private lateinit var mockTextInputProcessor: TextInputProcessor
    private lateinit var closeable: AutoCloseable
    private val getCurrentSettingsCalls = mutableListOf<Unit>()
    private val coordinateStateClearCalls = mutableListOf<Unit>()
    private val autoCapArgs = mutableListOf<String>()
    private val disableCapsLockCalls = mutableListOf<Unit>()

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        realInputState = InputStateManager(
            viewCallback = mock(ViewCallback::class.java),
            onShiftStateChanged = {},
            isCapsLockOn = { false },
            cancelDebounceJob = {}
        )
        mockOutputBridge = mock(OutputBridge::class.java)
        mockSuggestionPipeline = mock(SuggestionPipeline::class.java)
        mockAutoCorrectionEngine = mock(AutoCorrectionEngine::class.java)
        mockSwipeSpaceManager = mock(SwipeSpaceManager::class.java)
        mockLanguageManager = mock(LanguageManager::class.java)
        mockCandidateBarController = mock(CandidateBarController::class.java)
        mockTextInputProcessor = mock(TextInputProcessor::class.java)
        whenever(mockOutputBridge.safeGetTextBeforeCursor(any(), any())).thenReturn("")
        whenever(mockOutputBridge.safeGetTextAfterCursor(any(), any())).thenReturn("")
        // currentLanguage = the PRIMARY (settings) language; currentLayoutLanguage = the board on screen.
        // Spacing rules key off the latter, so both are stubbed here and overridden per test.
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockLanguageManager.currentLayoutLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        handler = NonLetterInputHandler(
            inputState = realInputState,
            outputBridge = mockOutputBridge,
            suggestionPipeline = mockSuggestionPipeline,
            autoCorrectionEngine = mockAutoCorrectionEngine,
            textInputProcessor = mockTextInputProcessor,
            swipeSpaceManager = mockSwipeSpaceManager,
            languageManager = mockLanguageManager,
            candidateBarController = mockCandidateBarController,
            serviceScope = testScope,
            onGetCurrentSettings = {
                getCurrentSettingsCalls.add(Unit)
                KeyboardSettings()
            },
            onCoordinateStateClear = { coordinateStateClearCalls.add(Unit) },
            onCheckAutoCapitalization = { autoCapArgs.add(it) },
            onDisableCapsLockAfterPunctuation = { disableCapsLockCalls.add(Unit) }
        )
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun handle_constructsWithFullDependencySet() {
        handler.handle(".")
    }

    @Test
    fun handle_directCommitField_sendsCharacterDirectly() {
        realInputState.isDirectCommitField = true
        handler.handle(".")
        verify(mockOutputBridge).sendCharacter(".")
    }

    @Test
    fun handle_invokesOnGetCurrentSettings_whenDisplayBufferNonEmpty() {
        realInputState.displayBuffer = "hello"
        handler.handle(".")
        testDispatcher.scheduler.advanceUntilIdle()
        assert(getCurrentSettingsCalls.isNotEmpty())
    }

    @Test
    fun `handle isSuggestionsDisabled sends character directly without suggestions`() {
        realInputState.isSuggestionsDisabled = true
        handler.handle(".")
        verify(mockOutputBridge).sendCharacter(".")
        verify(mockSuggestionPipeline, org.mockito.Mockito.never()).showBigramPredictions()
    }

    // ---- Bug A: cluster-layout punctuation commits the highlighted candidate, not the centre letters. ----

    @Test
    fun `cluster punctuation commits selected candidate via pipeline not literal buffer`() {
        realInputState.clusterLayoutActive = true
        realInputState.displayBuffer = "Deh" // the tapped clusters' centre letters
        realInputState.pendingSuggestions = listOf("Yes", "Yeh", "Deg")
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))

        handler.handle(".")
        testDispatcher.scheduler.advanceUntilIdle()

        // The top candidate ("Yes") is committed through the same selection path Space uses — NOT the
        // literal centre-letter buffer ("Deh"), and NOT a spell-based auto-correct of it.
        verifyBlocking(mockSuggestionPipeline) { coordinateSuggestionSelection(eq("Yes"), any(), any()) }
        verifyBlocking(mockAutoCorrectionEngine, never()) {
            decide(any(), any(), any(), any(), anyOrNull(), any(), any())
        }
    }

    @Test
    fun `cluster punctuation honours the Tab-advanced selected candidate`() {
        realInputState.clusterLayoutActive = true
        realInputState.displayBuffer = "Deh"
        realInputState.pendingSuggestions = listOf("Yes", "Yeh", "Deg")
        realInputState.selectedCandidate = 1 // Tab advanced to "Yeh"
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))

        handler.handle("!")
        testDispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(mockSuggestionPipeline) { coordinateSuggestionSelection(eq("Yeh"), any(), any()) }
    }

    @Test
    fun `cluster punctuation appends the mark after committing the candidate`() {
        realInputState.clusterLayoutActive = true
        realInputState.displayBuffer = "Deh"
        realInputState.pendingSuggestions = listOf("Yes")
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        // coordinateSuggestionSelection commits "Yes " (trailing space); the helper turns that into "Yes."
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle(".")
        testDispatcher.scheduler.advanceUntilIdle()

        // The auto-space left by the candidate commit is removed, then the closing mark + a space committed.
        verify(mockOutputBridge).deleteSurroundingText(1, 0)
        verify(mockOutputBridge).commitText(". ", 1)
    }

    // ---- Punctuation typed INSIDE a pair („…“ / “…” / (…) ) defers its trailing space. ----

    @Test
    fun `cluster punctuation before a closing quote drops its trailing space and defers it`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("cs"))
        // The word was already committed with its trailing space suppressed: cursor at „Ahoj|“.
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("j")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("“")

        handler.handle(",")
        testDispatcher.scheduler.advanceUntilIdle()

        // Bare mark — the space must NOT land in front of the closing quote…
        verify(mockOutputBridge).commitText(",", 1)
        verify(mockOutputBridge, never()).commitText(", ", 1)
        // …it is remembered as the separator the next word inserts ahead of itself.
        assert(realInputState.pendingWordSeparator)
    }

    @Test
    fun `cluster punctuation before a closing bracket drops its trailing space and defers it`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("t")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn(")")

        handler.handle(".")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText(".", 1)
        assert(realInputState.pendingWordSeparator)
    }

    @Test
    fun `cluster punctuation in open text keeps its trailing space and defers nothing`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("t")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("")

        handler.handle(".")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText(". ", 1)
        assert(!realInputState.pendingWordSeparator)
    }

    @Test
    fun `a colon straight after a digit holds its space back so a time stays glued`() {
        // Czech 24-hour times: "10" + ":" must give "10:" — the trailing space would split it into "10: 35".
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("cs"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("0")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("")

        handler.handle(":")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText(":", 1)
        // Deferred, not dropped: a WORD after it ("bod 3: text") still gets the separator back.
        assert(realInputState.pendingWordSeparator)
    }

    @Test
    fun `a comma after a digit holds its space back so a decimal stays glued`() {
        // Czech decimals and English thousands separators: "3," runs on as "3,14", "1," as "1,000".
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("cs"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("3")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("")

        handler.handle(",")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText(",", 1)
        assert(realInputState.pendingWordSeparator)
    }

    @Test
    fun `a period after a digit holds its space back - decimal or Czech ordinal, the next word decides`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("cs"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("0")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("")

        handler.handle(".")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText(".", 1)
        assert(realInputState.pendingWordSeparator)
    }

    @Test
    fun `a mark after a word keeps its trailing space`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("cs"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("o")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("")

        handler.handle(":")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText(": ", 1)
        assert(!realInputState.pendingWordSeparator)
    }

    @Test
    fun `a question mark after a digit still takes its space - it never sits inside a number`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("cs"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("0")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("")

        handler.handle("?")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText("? ", 1)
        assert(!realInputState.pendingWordSeparator)
    }

    @Test
    fun `auto-spacing punctuation before a closing quote defers its trailing space`() {
        // "„Ahoj |“" (a literal space before the closer): the mark eats that space and must not re-add one.
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("cs"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(2), any())).thenReturn("j ")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("“")

        handler.handle(",")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).deleteSurroundingText(1, 0)
        verify(mockOutputBridge).commitText(",", 1)
        assert(realInputState.pendingWordSeparator)
    }

    @Test
    fun `ellipsis before a closing quote defers its trailing space`() {
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("cs"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("j")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("“")

        handler.handle("…")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText("…", 1)
        assert(realInputState.pendingWordSeparator)
    }

    // ---- The mirror case: a mark that KEEPS a preceding space must write it when none is there. ----

    @Test
    fun `opener inside quotes writes the preceding space the word commit suppressed`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("cs"))
        // „Ahoj|“ — the word commit suppressed its trailing space, so there is none for "(" to keep.
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("j")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("“")

        handler.handle("(")
        testDispatcher.scheduler.advanceUntilIdle()

        // „Ahoj (|“ — not the glued „Ahoj(|“. An opener joins the FOLLOWING word: no trailing space,
        // and nothing deferred (the next word attaches to the bracket).
        verify(mockOutputBridge).commitText(" (", 1)
        assert(!realInputState.pendingWordSeparator)
    }

    @Test
    fun `opener after an existing auto-space does not double it`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("(")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText("(", 1)
        verify(mockOutputBridge, never()).deleteSurroundingText(1, 0)
    }

    @Test
    fun `opener right after another opener stays glued`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("cs"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("(")

        handler.handle("[")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText("[", 1)
    }

    @Test
    fun `opener in a url field never gains a leading space`() {
        realInputState.clusterLayoutActive = true
        realInputState.isUrlOrEmailField = true
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("m")

        handler.handle("(")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText("(", 1)
    }

    @Test
    fun `em dash inside quotes gets both spaces - the leading one written, the trailing deferred`() {
        realInputState.clusterLayoutActive = true
        // Russian is the language whose dashes are spaced by default (see dashIsSpacedHere).
        whenever(mockLanguageManager.currentLayoutLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("ru"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("о")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("»")

        handler.handle("—")
        testDispatcher.scheduler.advanceUntilIdle()

        // «слово —|» with the trailing space handed to the next word, never stranded before the closer.
        verify(mockOutputBridge).commitText(" —", 1)
        assert(realInputState.pendingWordSeparator)
    }

    @Test
    fun `en dash in Czech closes up - the spaced pomlcka is Space's job, not the dash's`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLayoutLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("cs"))
        // The word before it left the usual auto-space — the tight dash must eat it.
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("–")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).deleteSurroundingText(1, 0)
        verify(mockOutputBridge).commitText("–", 1)
        assert(!realInputState.pendingWordSeparator)
    }

    @Test
    fun `em dash in English closes up - no space before it and none after`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLayoutLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        // The word before it left the usual auto-space — the dash must eat it, not keep it.
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("—")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).deleteSurroundingText(1, 0)
        verify(mockOutputBridge).commitText("—", 1)
        // Nothing was deferred either: the next word starts flush against the dash ("word—word").
        assert(!realInputState.pendingWordSeparator)
    }

    @Test
    fun `en dash in English closes up too - a range stays whole`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLayoutLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("9")

        handler.handle("–")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge, never()).deleteSurroundingText(1, 0)
        verify(mockOutputBridge).commitText("–", 1)
    }

    @Test
    fun `em dash in English inside quotes stays tight and defers nothing`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLayoutLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("d")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("”")

        handler.handle("—")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText("—", 1)
        assert(!realInputState.pendingWordSeparator)
    }

    @Test
    fun `en dash is a spaced dash - it terminates the cluster word and takes both spaces`() {
        realInputState.clusterLayoutActive = true
        realInputState.displayBuffer = "Deh"
        realInputState.pendingSuggestions = listOf("Yes")
        // The Russian BOARD with an English PRIMARY language (setUp's default) — the geometry that broke
        // on device: keying the rule off currentLanguage left the Russian board on the primary's tight dash.
        whenever(mockLanguageManager.currentLayoutLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("ru"))
        // The candidate commit left its auto-space: open text, so the dash keeps it and adds its own.
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("–")
        testDispatcher.scheduler.advanceUntilIdle()

        // The centre letters never take the dash — the candidate is committed first, then " – " spacing.
        verifyBlocking(mockSuggestionPipeline) { coordinateSuggestionSelection(eq("Yes"), any(), any()) }
        verify(mockOutputBridge).commitText("– ", 1)
        verify(mockOutputBridge, never()).deleteSurroundingText(1, 0)
    }

    @Test
    fun `en dash inside quotes writes its leading space and defers the trailing one`() {
        realInputState.clusterLayoutActive = true
        whenever(mockLanguageManager.currentLayoutLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("ru"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("о")
        whenever(mockOutputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("»")

        handler.handle("–")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText(" –", 1)
        assert(realInputState.pendingWordSeparator)
    }

    @Test
    fun `en dash is a cluster terminator so it never joins the centre-letter buffer`() {
        assert(NonLetterInputHandler.isClusterTerminatorChar('–'))
        assert(NonLetterInputHandler.isClusterTerminatorChar('—'))
    }

    // ---- Bug 2: ellipsis gets a trailing space like sentence-ending punctuation. ----

    @Test
    fun `ellipsis on empty buffer commits the mark with a trailing space`() {
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))

        handler.handle("…")
        testDispatcher.scheduler.advanceUntilIdle()

        // No preceding auto-space to eat (buffer empty, nothing before) -> just "… " with one trailing space.
        verify(mockOutputBridge).commitText("… ", 1)
    }

    @Test
    fun `ellipsis after a word commits the word then the mark with a trailing space`() {
        realInputState.displayBuffer = "hello"
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        // A known word: the composing buffer is just recorded and finished (no learning needed).
        mockTextInputProcessor.stub { onBlocking { validateWord(any()) } doReturn true }
        // The composing word is finished, then "… " committed (no preceding space, last char is a letter).
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("o")

        handler.handle("…")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).finishComposingText()
        verify(mockOutputBridge).commitText("… ", 1)
        verify(mockOutputBridge, org.mockito.Mockito.never()).deleteSurroundingText(1, 0)
    }

    @Test
    fun `ellipsis eats a single preceding auto-space so it does not double-space`() {
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        // "word " already in the field (empty buffer): the trailing auto-space is eaten before "… ".
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("…")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).deleteSurroundingText(1, 0)
        verify(mockOutputBridge).commitText("… ", 1)
    }

    @Test
    fun `three-dot token is treated as an ellipsis with a trailing space`() {
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))

        handler.handle("...")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(mockOutputBridge).commitText("... ", 1)
    }

    // ---- Bug 3: hyphen commits the top candidate, then "-" with NO trailing space. ----

    @Test
    fun `hyphen commits the top candidate then appends a hyphen with no space`() {
        realInputState.displayBuffer = "co"
        realInputState.pendingSuggestions = listOf("co-op", "core", "cope")
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        // The candidate commit leaves a trailing auto-space, which the hyphen path then removes.
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("-")
        testDispatcher.scheduler.advanceUntilIdle()

        // The top candidate is committed through the dictionary-selection path, then "-" with no space.
        verifyBlocking(mockSuggestionPipeline) { coordinateSuggestionSelection(eq("co-op"), any(), any()) }
        verify(mockOutputBridge).deleteSurroundingText(1, 0)
        verify(mockOutputBridge).commitText("-", 1)
    }

    @Test
    fun `hyphen commits a custom-row candidate literally then appends a hyphen`() {
        realInputState.displayBuffer = "br"
        realInputState.setCustomSuggestions(listOf("brb"))
        realInputState.pendingSuggestions = listOf("brb")
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("-")
        testDispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(mockSuggestionPipeline) { coordinateCustomSuggestionSelection(eq("brb"), any()) }
        verify(mockOutputBridge).commitText("-", 1)
    }

    @Test
    fun `hyphen with no pending candidate falls through to a literal hyphen`() {
        realInputState.displayBuffer = ""
        realInputState.pendingSuggestions = emptyList()
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))

        handler.handle("-")
        testDispatcher.scheduler.advanceUntilIdle()

        // No candidate to commit: the dedicated hyphen path is skipped (it requires pending suggestions).
        verifyBlocking(mockSuggestionPipeline, never()) { coordinateSuggestionSelection(any(), any(), any()) }
    }

    @Test
    fun `cluster punctuation commits a custom-row entry literally`() {
        realInputState.clusterLayoutActive = true
        realInputState.displayBuffer = "Xyz"
        realInputState.setCustomSuggestions(listOf("brb"))
        realInputState.pendingSuggestions = listOf("brb")
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))

        handler.handle(".")
        testDispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(mockSuggestionPipeline) { coordinateCustomSuggestionSelection(eq("brb"), any()) }
        verifyBlocking(mockSuggestionPipeline, never()) { coordinateSuggestionSelection(any(), any(), any()) }
    }

    // ---- Bug C: a CHARACTER bracket/quote/dash on a cluster word terminates it (no buffer-append). ----

    @Test
    fun `cluster hyphen commits the candidate then a hyphen with no trailing space`() {
        realInputState.clusterLayoutActive = true
        realInputState.displayBuffer = "litd" // centre letters of the tapped clusters
        realInputState.pendingSuggestions = listOf("long")
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("-")
        testDispatcher.scheduler.advanceUntilIdle()

        // The predicted word is committed (NOT the literal "litd"), then "-" with NO trailing space.
        verifyBlocking(mockSuggestionPipeline) { coordinateSuggestionSelection(eq("long"), any(), any()) }
        verify(mockOutputBridge).deleteSurroundingText(1, 0)
        verify(mockOutputBridge).commitText("-", 1)
    }

    @Test
    fun `cluster opening paren commits the candidate then the opener with no trailing space`() {
        realInputState.clusterLayoutActive = true
        realInputState.displayBuffer = "Deh"
        realInputState.pendingSuggestions = listOf("Yes")
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("(")
        testDispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(mockSuggestionPipeline) { coordinateSuggestionSelection(eq("Yes"), any(), any()) }
        // Opener KEEPS the preceding space ("word (") and adds no trailing space.
        verify(mockOutputBridge, never()).deleteSurroundingText(1, 0)
        verify(mockOutputBridge).commitText("(", 1)
    }

    @Test
    fun `cluster closing paren commits the candidate then the closer with a trailing space`() {
        realInputState.clusterLayoutActive = true
        realInputState.displayBuffer = "Deh"
        realInputState.pendingSuggestions = listOf("Yes")
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle(")")
        testDispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(mockSuggestionPipeline) { coordinateSuggestionSelection(eq("Yes"), any(), any()) }
        // Closer ends the group -> trailing space.
        verify(mockOutputBridge).commitText(") ", 1)
    }

    @Test
    fun `cluster em dash commits the candidate then the dash - spaced in Russian`() {
        realInputState.clusterLayoutActive = true
        realInputState.displayBuffer = "Deh"
        realInputState.pendingSuggestions = listOf("Yes")
        whenever(mockLanguageManager.currentLayoutLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("ru"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("—") // U+2014 em dash, typed as a character on a cluster key
        testDispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(mockSuggestionPipeline) { coordinateSuggestionSelection(eq("Yes"), any(), any()) }
        verify(mockOutputBridge).commitText("— ", 1)
    }

    @Test
    fun `cluster em dash commits the candidate then the dash - closed up in English`() {
        realInputState.clusterLayoutActive = true
        realInputState.displayBuffer = "Deh"
        realInputState.pendingSuggestions = listOf("Yes")
        whenever(mockLanguageManager.currentLayoutLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("—")
        testDispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(mockSuggestionPipeline) { coordinateSuggestionSelection(eq("Yes"), any(), any()) }
        // The candidate commit's auto-space is eaten and no new one is added: "Yes—".
        verify(mockOutputBridge).deleteSurroundingText(1, 0)
        verify(mockOutputBridge).commitText("—", 1)
    }

    @Test
    fun `cluster opening curly double quote commits the candidate then the quote with no space`() {
        realInputState.clusterLayoutActive = true
        realInputState.displayBuffer = "Deh"
        realInputState.pendingSuggestions = listOf("Yes")
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("“") // “ opening curly double quote
        testDispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(mockSuggestionPipeline) { coordinateSuggestionSelection(eq("Yes"), any(), any()) }
        verify(mockOutputBridge).commitText("“", 1)
    }

    @Test
    fun `cluster closing curly double quote commits the candidate then the quote with a space`() {
        realInputState.clusterLayoutActive = true
        realInputState.displayBuffer = "Deh"
        realInputState.pendingSuggestions = listOf("Yes")
        whenever(mockLanguageManager.currentLanguage)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow("en"))
        whenever(mockOutputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")

        handler.handle("”") // ” closing curly double quote
        testDispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(mockSuggestionPipeline) { coordinateSuggestionSelection(eq("Yes"), any(), any()) }
        verify(mockOutputBridge).commitText("” ", 1)
    }

    @Test
    fun `isClusterTerminatorChar covers brackets quotes dash but not the apostrophe`() {
        // The chars that must terminate a composing cluster word.
        listOf('-', '…', '—', '(', ')', '[', ']', '{', '}', '"', '“', '”', '.', ',', '?').forEach {
            assert(NonLetterInputHandler.isClusterTerminatorChar(it)) { "expected $it to be a terminator" }
        }
        // The apostrophe (straight + curly) is a contraction char, NOT a terminator.
        assert(!NonLetterInputHandler.isClusterTerminatorChar('\'')) { "straight apostrophe must not terminate" }
        assert(!NonLetterInputHandler.isClusterTerminatorChar('’')) { "curly apostrophe must not terminate" }
        // Ordinary letters are not terminators.
        assert(!NonLetterInputHandler.isClusterTerminatorChar('a'))
    }
}
