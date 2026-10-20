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

    // ---- Czech dead keys: the mark waits for a letter, and the letter's CASE is not its business ----

    private fun caron() = KeyboardKey.Character("ˇ", KeyboardKey.KeyType.LETTER)

    private fun letter(value: String, face: String): KeyboardKey.Character {
        val key = KeyboardKey.Character(value, KeyboardKey.KeyType.LETTER)
        whenever(mockViewModel.getCharacterForInput(key)).thenReturn(face)
        return key
    }

    @Test
    fun `arming a diacritic does not spend the shift, so a shifted board types the capital`() {
        // 白い熊, 2026-10-20: this is the whole "Č" complaint. The flick onto ˇ used to run
        // clearShiftAfterCharacter, the board dropped to lowercase, and the base letter arrived as "c".
        whenever(mockViewModel.state).thenReturn(MutableStateFlow(KeyboardState(isShiftPressed = true)))
        whenever(mockViewModel.getCharacterForInput(caron())).thenReturn("ˇ")

        router.route(caron())

        verify(mockViewModel, never()).clearShiftAfterCharacter(any())
        verify(mockHandler, never()).onNonLetterInput(any())

        router.route(letter("c", "C"))

        verify(mockHandler).onLetterInput(eq("Č"), any(), any())
        // The composed letter spends it, exactly as an ordinary letter would.
        verify(mockViewModel).clearShiftAfterCharacter(any())
    }

    @Test
    fun `pressing Shift after a diacritic keeps the mark armed`() {
        // The other half of it: Shift chooses the CASE of the letter the mark is waiting for, so it cannot
        // be the thing that throws the mark away.
        whenever(mockViewModel.getCharacterForInput(caron())).thenReturn("ˇ")
        router.route(caron())

        router.route(KeyboardKey.Action(KeyboardKey.ActionType.SHIFT))
        router.route(letter("c", "C"))

        verify(mockHandler).onLetterInput(eq("Č"), any(), any())
    }

    @Test
    fun `caps lock after a diacritic keeps the mark armed`() {
        whenever(mockViewModel.getCharacterForInput(caron())).thenReturn("ˇ")
        router.route(caron())

        router.route(KeyboardKey.Action(KeyboardKey.ActionType.CAPS_LOCK))
        router.route(letter("c", "C"))

        verify(mockHandler).onLetterInput(eq("Č"), any(), any())
    }

    @Test
    fun `an unshifted diacritic still composes the lowercase letter`() {
        whenever(mockViewModel.getCharacterForInput(caron())).thenReturn("ˇ")
        router.route(caron())

        router.route(letter("c", "c"))

        verify(mockHandler).onLetterInput(eq("č"), any(), any())
    }

    @Test
    fun `Space ends a pending diacritic`() {
        whenever(mockViewModel.getCharacterForInput(caron())).thenReturn("ˇ")
        router.route(caron())

        router.route(KeyboardKey.Action(KeyboardKey.ActionType.SPACE))
        router.route(letter("c", "c"))

        verify(mockHandler).onLetterInput(eq("c"), any(), any())
        verify(mockHandler, never()).onLetterInput(eq("č"), any(), any())
    }

    @Test
    fun `a base with no precomposed form emits the mark and then itself`() {
        whenever(mockViewModel.getCharacterForInput(caron())).thenReturn("ˇ")
        router.route(caron())

        router.route(letter("q", "q"))

        verify(mockHandler).onNonLetterInput("ˇ")
        verify(mockHandler).onLetterInput(eq("q"), any(), any())
    }
}
