package com.urik.keyboard.service

import android.view.inputmethod.EditorInfo
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.ui.keyboard.KeyboardViewModel
import javax.inject.Inject
import javax.inject.Singleton

interface KeyEventHandler {
    fun onLetterInput(char: String, wasAutoShifted: Boolean, wasManualShifted: Boolean)
    fun onNonLetterInput(char: String)
    fun onBackspace()
    fun onSpace()
    fun onEnterAction(imeAction: Int)
    fun onShift()
    fun onCapsLock()
    fun onModeSwitch(mode: KeyboardMode)
    fun onDakuten()
    fun onSmallKana()
    fun onLanguageSwitch()
    fun onNextCandidate()
    fun onCommitCandidate()
    fun onHandakuten()
    fun onEmoji()
    fun onTab()
}

@Singleton
class KeyEventRouter
@Inject
constructor() {
    private var handler: KeyEventHandler? = null
    private var searchInputHandler: (KeyboardKey) -> Boolean = { false }
    private var viewModel: KeyboardViewModel? = null

    // An armed combining diacritic (Czech ´ ˇ ¨ ˚ ¯): the next base letter composes with it. Lives here, on
    // the single dispatch chokepoint, so flick/cluster/plain characters all feed it. Cleared by any action key.
    private var pendingDeadKey: Char? = null

    fun configure(
        handler: KeyEventHandler,
        searchInputHandler: (KeyboardKey) -> Boolean,
        viewModel: KeyboardViewModel
    ) {
        this.handler = handler
        this.searchInputHandler = searchInputHandler
        this.viewModel = viewModel
    }

    fun route(key: KeyboardKey) {
        if (searchInputHandler(key)) return

        when (key) {
            is KeyboardKey.Character -> {
                val char = viewModel?.getCharacterForInput(key) ?: key.value
                // Capture both shift signals BEFORE clearShiftAfterCharacter() wipes the live shift latch:
                // a manual single-shift tap clears isShiftPressed here, so reading it later in onLetterInput
                // would always see false and the candidate bar would commit lowercase mid-line (Bug 2).
                val shiftState = viewModel?.state?.value
                val wasAutoShifted = shiftState?.isAutoShift ?: false
                val wasManualShifted =
                    shiftState != null &&
                        shiftState.isShiftPressed &&
                        !shiftState.isAutoShift &&
                        !shiftState.isCapsLockOn

                // Dead keys (Czech ´ ˇ ¨ ˚ ¯): an armed diacritic composes with this base letter (ˇ+c → č,
                // ´+E → É); a diacritic character arms a new dead key instead of being emitted. If the base
                // has no precomposed form, emit the spacing diacritic and then the base normally.
                //
                // ARMING SPENDS NOTHING (白い熊, 2026-10-20): the diacritic is not the letter being typed, so
                // it must not consume the shift. Clearing it here dropped a shifted cs board to lowercase the
                // moment the ˇ was flicked, the base letter then arrived lowercase, and pressing Shift again
                // to get the capital threw the pending mark away (Action keys clear it) — so "Č" could not be
                // typed at all. The COMPOSED letter spends the shift, like every other letter.
                val pending = pendingDeadKey
                val arming = DeadKeys.combiningFor(char)
                if (pending != null) {
                    pendingDeadKey = null
                    val composed = if (arming == null) DeadKeys.compose(char, pending) else null
                    if (composed != null) {
                        viewModel?.clearShiftAfterCharacter(key)
                        handler?.onLetterInput(composed, wasAutoShifted, wasManualShifted)
                        return
                    }
                    handler?.onNonLetterInput(DeadKeys.spacingFor(pending))
                }
                if (arming != null) {
                    pendingDeadKey = arming
                    return
                }
                viewModel?.clearShiftAfterCharacter(key)

                if (key.type == KeyboardKey.KeyType.LETTER) {
                    handler?.onLetterInput(char, wasAutoShifted, wasManualShifted)
                } else {
                    handler?.onNonLetterInput(char)
                }
            }
            is KeyboardKey.Action -> {
                // Shift and caps lock choose the CASE of the letter the pending mark is waiting for, so they
                // leave it armed (白い熊, 2026-10-20 — pressing Shift after ˇ used to lose the háček).
                // Everything else — space, Enter, backspace, a layer or language switch — ends it.
                if (key.action != KeyboardKey.ActionType.SHIFT && key.action != KeyboardKey.ActionType.CAPS_LOCK) {
                    pendingDeadKey = null
                }
                routeAction(key)
            }
            KeyboardKey.Spacer -> {}
            is KeyboardKey.FlickKey -> {}
        }
    }

    fun routeAction(action: KeyboardKey.Action) {
        when (action.action) {
            KeyboardKey.ActionType.BACKSPACE -> handler?.onBackspace()
            KeyboardKey.ActionType.SPACE -> handler?.onSpace()
            KeyboardKey.ActionType.ENTER,
            KeyboardKey.ActionType.SEARCH,
            KeyboardKey.ActionType.SEND,
            KeyboardKey.ActionType.DONE,
            KeyboardKey.ActionType.GO,
            KeyboardKey.ActionType.NEXT,
            KeyboardKey.ActionType.PREVIOUS -> handler?.onEnterAction(
                when (action.action) {
                    KeyboardKey.ActionType.SEARCH -> EditorInfo.IME_ACTION_SEARCH
                    KeyboardKey.ActionType.SEND -> EditorInfo.IME_ACTION_SEND
                    KeyboardKey.ActionType.DONE -> EditorInfo.IME_ACTION_DONE
                    KeyboardKey.ActionType.GO -> EditorInfo.IME_ACTION_GO
                    KeyboardKey.ActionType.NEXT -> EditorInfo.IME_ACTION_NEXT
                    KeyboardKey.ActionType.PREVIOUS -> EditorInfo.IME_ACTION_PREVIOUS
                    else -> EditorInfo.IME_ACTION_NONE
                }
            )
            KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> handler?.onModeSwitch(KeyboardMode.LETTERS)
            KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> handler?.onModeSwitch(KeyboardMode.NUMBERS)
            KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> handler?.onModeSwitch(KeyboardMode.SYMBOLS)
            KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY -> handler?.onModeSwitch(
                KeyboardMode.SYMBOLS_SECONDARY
            )
            KeyboardKey.ActionType.MODE_SWITCH_NUMPAD -> handler?.onModeSwitch(KeyboardMode.NUMPAD)
            KeyboardKey.ActionType.SHIFT -> handler?.onShift()
            KeyboardKey.ActionType.CAPS_LOCK -> handler?.onCapsLock()
            KeyboardKey.ActionType.LANGUAGE_SWITCH -> handler?.onLanguageSwitch()
            KeyboardKey.ActionType.DAKUTEN -> handler?.onDakuten()
            KeyboardKey.ActionType.SMALL_KANA -> handler?.onSmallKana()
            KeyboardKey.ActionType.NEXT_CANDIDATE -> handler?.onNextCandidate()
            KeyboardKey.ActionType.COMMIT_CANDIDATE -> handler?.onCommitCandidate()
            KeyboardKey.ActionType.HANDAKUTEN -> handler?.onHandakuten()
            KeyboardKey.ActionType.EMOJI -> handler?.onEmoji()
            KeyboardKey.ActionType.TAB -> handler?.onTab()
        }
    }
}
