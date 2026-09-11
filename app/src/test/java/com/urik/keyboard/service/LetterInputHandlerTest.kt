package com.urik.keyboard.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class LetterInputHandlerTest {
    private lateinit var handler: LetterInputHandler
    private lateinit var realInputState: InputStateManager
    private lateinit var mockOutputBridge: OutputBridge
    private lateinit var mockSuggestionPipeline: SuggestionPipeline
    private lateinit var mockSwipeSpaceManager: SwipeSpaceManager
    private lateinit var closeable: AutoCloseable
    private val coordinateStateClearCalls = mutableListOf<Unit>()
    private val autoCapArgs = mutableListOf<String>()

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
        mockSwipeSpaceManager = mock(SwipeSpaceManager::class.java)
        handler = LetterInputHandler(
            inputState = realInputState,
            outputBridge = mockOutputBridge,
            suggestionPipeline = mockSuggestionPipeline,
            swipeSpaceManager = mockSwipeSpaceManager,
            onCoordinateStateClear = { coordinateStateClearCalls.add(Unit) },
            onCheckAutoCapitalization = { autoCapArgs.add(it) }
        )
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun handle_constructsWithRealInputStateAndMockedDependencies() {
        assertEquals(0, coordinateStateClearCalls.size)
        assertEquals(0, autoCapArgs.size)
    }

    @Test
    fun handle_directCommitField_sendsCharacterDirectly() {
        realInputState.isDirectCommitField = true
        handler.handle("a")
        verify(mockOutputBridge).sendCharacter("a")
    }

    @Test
    fun handle_normalInput_updatesDisplayBuffer() {
        handler.handle("a")
        assertEquals("a", realInputState.displayBuffer)
    }

    @Test
    fun `handle isSuggestionsDisabled sends character directly without composing`() {
        realInputState.isSuggestionsDisabled = true
        handler.handle("a")
        verify(mockOutputBridge).sendCharacter("a")
        assertEquals("", realInputState.displayBuffer)
    }

    @Test
    fun `pending word separator inserts a space before a new word and is consumed`() {
        // A previous commit suppressed its trailing space (cursor at „word“| before the closing
        // quote): the next word must get the separator, exactly once.
        realInputState.pendingWordSeparator = true
        handler.handle("a")
        verify(mockOutputBridge).commitText(" ", 1)
        assertEquals("a", realInputState.displayBuffer)
        assertEquals(false, realInputState.pendingWordSeparator)
    }

    @Test
    fun `a digit continuing a number drops the deferred separator instead of inserting it`() {
        // "10:" deferred its space (a colon inside a number); the "3" of "10:35" must glue, not take it.
        realInputState.pendingWordSeparator = true
        org.mockito.Mockito.`when`(mockOutputBridge.safeGetTextBeforeCursor(2)).thenReturn("0:")
        handler.handle("3")
        verify(mockOutputBridge, org.mockito.Mockito.never()).commitText(" ", 1)
        assertEquals("3", realInputState.displayBuffer)
        // Dropped, not left armed: the next word must not pick it up either.
        assertEquals(false, realInputState.pendingWordSeparator)
    }

    @Test
    fun `a letter after a number-internal mark still takes the deferred separator`() {
        // "10." + "k" -> "10. k…" (the Czech ordinal), the deferral's other reading.
        realInputState.pendingWordSeparator = true
        org.mockito.Mockito.`when`(mockOutputBridge.safeGetTextBeforeCursor(2)).thenReturn("0.")
        handler.handle("k")
        verify(mockOutputBridge).commitText(" ", 1)
        assertEquals("k", realInputState.displayBuffer)
    }

    @Test
    fun `a digit after a mark that closed a WORD takes the separator - only numbers glue`() {
        // „Ahoj,|“ + "5" -> „Ahoj, 5“: the comma follows a letter, so this is not a number.
        realInputState.pendingWordSeparator = true
        org.mockito.Mockito.`when`(mockOutputBridge.safeGetTextBeforeCursor(2)).thenReturn("j,")
        handler.handle("5")
        verify(mockOutputBridge).commitText(" ", 1)
        assertEquals("5", realInputState.displayBuffer)
    }

    @Test
    fun `no pending word separator means no injected space`() {
        handler.handle("a")
        verify(mockOutputBridge, org.mockito.Mockito.never()).commitText(" ", 1)
        assertEquals("a", realInputState.displayBuffer)
    }

    @Test
    fun `new word right after a closing bracket gets a separator space`() {
        org.mockito.Mockito.`when`(mockOutputBridge.safeGetTextBeforeCursor(2)).thenReturn("t)")
        handler.handle("s")
        verify(mockOutputBridge).commitText(" ", 1)
        assertEquals("s", realInputState.displayBuffer)
    }

    @Test
    fun `new word after an opening quote gets no separator space`() {
        org.mockito.Mockito.`when`(mockOutputBridge.safeGetTextBeforeCursor(2)).thenReturn(" „")
        handler.handle("s")
        verify(mockOutputBridge, org.mockito.Mockito.never()).commitText(" ", 1)
    }
}
