package com.urik.keyboard.service

import com.urik.keyboard.settings.KeyboardSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the keyboard tells the auto-capitalisation rule after it writes a sentence-ending mark.
 *
 * The rule ([com.urik.keyboard.ui.keyboard.KeyboardViewModel.shouldAutoCapitalize]) arms the next capital
 * for a terminal mark FOLLOWED BY WHITESPACE, and the handler used to hand it a fresh read of the field —
 * taken inside the batch edit it had just written through. An editor that BUFFERS its edits until the batch
 * closes (every Compose text field; the Android Claude app is one) answers such a read with the text as it
 * stood BEFORE the commit, so the mark and its space were invisible and the next sentence was never
 * capitalised in those apps (白い熊, 2026-10-20). What was written is known without asking.
 *
 * Robolectric, because the decision runs through ICU's S_TERM property: with the plain unit-test stubs
 * (`isReturnDefaultValues`) "." is not a sentence terminator at all and the branch is unreachable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoCapReportingTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var handler: NonLetterInputHandler
    private lateinit var inputState: InputStateManager
    private lateinit var outputBridge: OutputBridge
    private lateinit var closeable: AutoCloseable
    private val autoCapArgs = mutableListOf<String>()

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        inputState = InputStateManager(
            viewCallback = mock(ViewCallback::class.java),
            onShiftStateChanged = {},
            isCapsLockOn = { false },
            cancelDebounceJob = {}
        )
        outputBridge = mock(OutputBridge::class.java)
        val languageManager = mock(LanguageManager::class.java)
        whenever(languageManager.currentLanguage).thenReturn(MutableStateFlow("en"))
        whenever(languageManager.currentLayoutLanguage).thenReturn(MutableStateFlow("en"))
        whenever(outputBridge.safeGetTextBeforeCursor(any(), any())).thenReturn("")
        whenever(outputBridge.safeGetTextAfterCursor(any(), any())).thenReturn("")
        handler = NonLetterInputHandler(
            inputState = inputState,
            outputBridge = outputBridge,
            suggestionPipeline = mock(SuggestionPipeline::class.java),
            autoCorrectionEngine = mock(AutoCorrectionEngine::class.java),
            textInputProcessor = mock(TextInputProcessor::class.java),
            swipeSpaceManager = mock(SwipeSpaceManager::class.java),
            languageManager = languageManager,
            candidateBarController = mock(CandidateBarController::class.java),
            serviceScope = testScope,
            onGetCurrentSettings = { KeyboardSettings() },
            onCoordinateStateClear = {},
            onCheckAutoCapitalization = { autoCapArgs.add(it) },
            onDisableCapsLockAfterPunctuation = {}
        )
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun `a period on a cluster board reports the mark and its space, not what the field answers`() {
        inputState.clusterLayoutActive = true
        inputState.displayBuffer = "Deh"
        inputState.pendingSuggestions = listOf("Yes")
        // The candidate commit left its auto-space, which the mark eats.
        whenever(outputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn(" ")
        // And this is what a buffering editor answers while the batch is open: the text before the commit.
        whenever(outputBridge.safeGetTextBeforeCursor(eq(50), any())).thenReturn("Yes")

        handler.handle(".")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(outputBridge).commitText(". ", 1)
        assertEquals("the rule must be told what was written", listOf(". "), autoCapArgs)
    }

    @Test
    fun `a mark whose trailing space was suppressed reports no whitespace, so no capital follows`() {
        // The other half of the same rule: inside a pair („Ahoj.|“) and after a digit ("10.") the space is
        // deferred into the next word's separator, and a deferred space must NOT arm a capital — the Czech
        // ordinal "10. května" depends on that. Reporting what was written gets it right by construction.
        inputState.clusterLayoutActive = true
        whenever(outputBridge.safeGetTextBeforeCursor(eq(1), any())).thenReturn("j")
        whenever(outputBridge.safeGetTextAfterCursor(eq(1), any())).thenReturn("“")
        whenever(outputBridge.safeGetTextBeforeCursor(eq(50), any())).thenReturn("„Ahoj. ")

        handler.handle(".")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(outputBridge).commitText(".", 1)
        assertEquals("no whitespace was written", listOf("."), autoCapArgs)
    }

    @Test
    fun `a period after an already-committed word reports the rewritten tail`() {
        // The other site: nothing composing, the cursor sits after "word ", so the space is deleted and
        // "word. " stands. (白い熊's own case is the cluster one above; this is the flat-board twin.)
        whenever(outputBridge.safeGetTextBeforeCursor(eq(2), any())).thenReturn("s ")
        whenever(outputBridge.safeGetTextBeforeCursor(eq(50), any())).thenReturn("Yes ")

        handler.handle(".")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(outputBridge).commitText(". ", 1)
        assertEquals(listOf(". "), autoCapArgs)
    }
}
