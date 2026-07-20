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
