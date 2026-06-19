package com.urik.keyboard.service

import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.ui.keyboard.KeyboardViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class KeyEventRouterTest {
    private lateinit var mockViewModel: KeyboardViewModel
    private lateinit var mockHandler: KeyEventHandler
    private lateinit var router: KeyEventRouter

    @Before
    fun setup() {
        mockViewModel = mock()
        mockHandler = mock()
        whenever(mockViewModel.state).thenReturn(MutableStateFlow(KeyboardState()))
        router = KeyEventRouter()
        router.configure(handler = mockHandler, searchInputHandler = { false }, viewModel = mockViewModel)
    }

    @Test
    fun `route Character LETTER type calls onLetterInput with resolved char`() {
        whenever(
            mockViewModel.getCharacterForInput(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        ).thenReturn("a")
        router.route(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        verify(mockHandler).onLetterInput(eq("a"), any(), any())
        verify(mockHandler, never()).onNonLetterInput("a")
    }

    @Test
    fun `route Character SYMBOL type calls onNonLetterInput`() {
        whenever(
            mockViewModel.getCharacterForInput(KeyboardKey.Character("!", KeyboardKey.KeyType.SYMBOL))
        ).thenReturn("!")
        router.route(KeyboardKey.Character("!", KeyboardKey.KeyType.SYMBOL))
        verify(mockHandler).onNonLetterInput("!")
        verify(mockHandler, never()).onLetterInput(eq("!"), any(), any())
    }

    @Test
    fun `route Action BACKSPACE calls onBackspace`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE))
        verify(mockHandler).onBackspace()
    }

    @Test
    fun `route Action SPACE calls onSpace`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.SPACE))
        verify(mockHandler).onSpace()
    }

    @Test
    fun `route Action SHIFT calls onShift`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.SHIFT))
        verify(mockHandler).onShift()
    }

    @Test
    fun `route Action CAPS_LOCK calls onCapsLock`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.CAPS_LOCK))
        verify(mockHandler).onCapsLock()
    }

    @Test
    fun `route Action MODE_SWITCH_LETTERS calls onModeSwitch with LETTERS`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_LETTERS))
        verify(mockHandler).onModeSwitch(KeyboardMode.LETTERS)
    }

    @Test
    fun `route Action MODE_SWITCH_NUMBERS calls onModeSwitch with NUMBERS`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_NUMBERS))
        verify(mockHandler).onModeSwitch(KeyboardMode.NUMBERS)
    }

    @Test
    fun `route Action DAKUTEN calls onDakuten`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.DAKUTEN))
        verify(mockHandler).onDakuten()
    }

    @Test
    fun `route Action SMALL_KANA calls onSmallKana`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.SMALL_KANA))
        verify(mockHandler).onSmallKana()
    }

    @Test
    fun `route Action LANGUAGE_SWITCH calls onLanguageSwitch`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.LANGUAGE_SWITCH))
        verify(mockHandler).onLanguageSwitch()
    }

    @Test
    fun `route returns early when searchInputHandler intercepts`() {
        router.configure(handler = mockHandler, searchInputHandler = { true }, viewModel = mockViewModel)
        whenever(
            mockViewModel.getCharacterForInput(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        ).thenReturn("a")
        router.route(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        verify(mockHandler, never()).onLetterInput(eq("a"), any(), any())
        verify(mockHandler, never()).onNonLetterInput("a")
    }

    @Test
    fun `route Action TAB calls onTab`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.TAB))
        verify(mockHandler).onTab()
    }

    @Test
    fun `route Action ENTER calls onEnterAction`() {
        router.route(KeyboardKey.Action(KeyboardKey.ActionType.ENTER))
        verify(mockHandler).onEnterAction(android.view.inputmethod.EditorInfo.IME_ACTION_NONE)
    }

    @Test
    fun `route LETTER passes wasAutoShifted=true when isAutoShift was set before clearShift`() {
        whenever(
            mockViewModel.state
        ).thenReturn(MutableStateFlow(KeyboardState(isAutoShift = true, isShiftPressed = true)))
        whenever(
            mockViewModel.getCharacterForInput(KeyboardKey.Character("l", KeyboardKey.KeyType.LETTER))
        ).thenReturn("L")
        router.route(KeyboardKey.Character("l", KeyboardKey.KeyType.LETTER))
        // Auto-shift is NOT a manual shift, so wasManualShifted must stay false.
        verify(mockHandler).onLetterInput(eq("L"), eq(true), eq(false))
    }

    @Test
    fun `route LETTER passes wasAutoShifted=false when isAutoShift was not set`() {
        whenever(
            mockViewModel.getCharacterForInput(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        ).thenReturn("a")
        router.route(KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER))
        verify(mockHandler).onLetterInput(eq("a"), eq(false), eq(false))
    }

    @Test
    fun `route LETTER passes wasManualShifted=true when shift is engaged without auto-shift or caps`() {
        // Captured BEFORE clearShiftAfterCharacter() wipes the latch — the crux of Bug 2.
        whenever(
            mockViewModel.state
        ).thenReturn(MutableStateFlow(KeyboardState(isShiftPressed = true, isAutoShift = false, isCapsLockOn = false)))
        whenever(
            mockViewModel.getCharacterForInput(KeyboardKey.Character("h", KeyboardKey.KeyType.LETTER))
        ).thenReturn("H")
        router.route(KeyboardKey.Character("h", KeyboardKey.KeyType.LETTER))
        verify(mockHandler).onLetterInput(eq("H"), eq(false), eq(true))
    }

    @Test
    fun `route LETTER passes wasManualShifted=false when caps-lock is on`() {
        whenever(
            mockViewModel.state
        ).thenReturn(MutableStateFlow(KeyboardState(isShiftPressed = false, isAutoShift = false, isCapsLockOn = true)))
        whenever(
            mockViewModel.getCharacterForInput(KeyboardKey.Character("h", KeyboardKey.KeyType.LETTER))
        ).thenReturn("H")
        router.route(KeyboardKey.Character("h", KeyboardKey.KeyType.LETTER))
        verify(mockHandler).onLetterInput(eq("H"), eq(false), eq(false))
    }
}
