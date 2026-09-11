package com.urik.keyboard.service

import com.urik.keyboard.utils.CursorEditingUtils
import com.urik.keyboard.utils.ErrorLogger

class LetterInputHandler(
    private val inputState: InputStateManager,
    private val outputBridge: OutputBridge,
    private val suggestionPipeline: SuggestionPipeline,
    private val swipeSpaceManager: SwipeSpaceManager,
    private val onCoordinateStateClear: () -> Unit,
    private val onCheckAutoCapitalization: (textBefore: String) -> Unit
) {
    fun handle(char: String) {
        try {
            inputState.lastSpaceTime = 0

            if (inputState.requiresDirectCommit) {
                outputBridge.sendCharacter(char)
                return
            }

            if (inputState.isSuggestionsDisabled) {
                outputBridge.sendCharacter(char)
                return
            }

            if (inputState.displayBuffer.isNotEmpty() && inputState.wordState.isFromSwipe) {
                outputBridge.beginBatchEdit()
                try {
                    outputBridge.finishComposingText()
                    outputBridge.commitText(" ", 1)

                    onCoordinateStateClear()
                    swipeSpaceManager.clearAutoSpaceFlag()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    onCheckAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            }

            val fastPath = inputState.isComposingCursorAtExpectedEnd()

            if (!fastPath && inputState.displayBuffer.isNotEmpty()) {
                val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    onCoordinateStateClear()
                }
            }

            if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                outputBridge.clearSpellConfirmationState()
            }

            inputState.postCommitReplacementState = null
            inputState.lastAutocorrection = null

            var cachedAbsoluteCursorPos: Int? = null

            if (!fastPath && inputState.composingRegionStart != -1 && inputState.displayBuffer.isNotEmpty()) {
                val absoluteCursorPos = outputBridge.safeGetCursorPosition()
                cachedAbsoluteCursorPos = absoluteCursorPos
                val cursorOffsetInWord = (absoluteCursorPos - inputState.composingRegionStart).coerceIn(
                    0,
                    inputState.displayBuffer.length
                )
                val charsAfterCursorInWord = inputState.displayBuffer.length - cursorOffsetInWord

                val textBeforePart = outputBridge.safeGetTextBeforeCursor(
                    cursorOffsetInWord
                ).takeLast(cursorOffsetInWord)
                val textAfterPart = if (charsAfterCursorInWord > 0) {
                    outputBridge.safeGetTextAfterCursor(
                        charsAfterCursorInWord
                    ).take(charsAfterCursorInWord)
                } else {
                    ""
                }
                val actualComposingText = textBeforePart + textAfterPart

                if (actualComposingText != inputState.displayBuffer) {
                    inputState.composingRegionStart = -1
                    inputState.clearPendingTypingOus()
                }
            }

            val cursorPosInWord =
                if (fastPath) {
                    inputState.displayBuffer.length
                } else if (inputState.composingRegionStart != -1 && inputState.displayBuffer.isNotEmpty()) {
                    val absoluteCursorPos = cachedAbsoluteCursorPos ?: outputBridge.safeGetCursorPosition()
                    CursorEditingUtils.calculateCursorPositionInWord(
                        absoluteCursorPos,
                        inputState.composingRegionStart,
                        inputState.displayBuffer.length
                    )
                } else {
                    inputState.displayBuffer.length
                }

            val isStartingNewWord = inputState.displayBuffer.isEmpty()

            // Two geometries need an auto-inserted separator before this new word: the previous
            // commit suppressed its trailing space (cursor at „word“| BEFORE the closing mark), or
            // the cursor sits right AFTER a closing bracket/quote ("(test)|" via arrow-out).
            if (isStartingNewWord) {
                val textBefore = outputBridge.safeGetTextBeforeCursor(2)
                // A digit continuing the number before the cursor ("10:" + "3") drops the separator
                // the mark deferred instead of inserting it — the deferral exists for exactly this key.
                val pendingSeparator = inputState.consumePendingWordSeparator() &&
                    !CursorEditingUtils.continuesNumber(char, textBefore)
                val needsSeparator = pendingSeparator ||
                    CursorEditingUtils.needsSpaceAfterClosingPair(textBefore)
                if (needsSeparator) {
                    // The space MUST be pre-announced: its unannounced selection update hit the
                    // composing-reassert branch, which SET THE CURSOR BACK one position mid-word —
                    // the next letter then inserted at index 0 and words came out reversed.
                    val posAfterSpace = if (inputState.isKnownCursorTrustworthy()) {
                        inputState.lastKnownCursorPosition + 1
                    } else {
                        outputBridge.safeGetCursorPosition() + 1
                    }
                    outputBridge.commitText(" ", 1)
                    inputState.lastKnownCursorPosition = posAfterSpace
                    inputState.enqueueTypingOus(
                        InputStateManager.ExpectedTypingOus(
                            composingStart = -1,
                            composingEnd = -1,
                            cursorPosition = posAfterSpace
                        )
                    )
                }
            }

            inputState.displayBuffer =
                if (isStartingNewWord) {
                    char
                } else {
                    StringBuilder(inputState.displayBuffer)
                        .insert(cursorPosInWord, char)
                        .toString()
                }

            val newCursorPositionInText = cursorPosInWord + char.length

            if (isStartingNewWord) {
                inputState.composingRegionStart = if (inputState.isKnownCursorTrustworthy()) {
                    inputState.lastKnownCursorPosition
                } else {
                    outputBridge.safeGetCursorPosition()
                }
            }

            val needsCursorRepositioning =
                inputState.composingRegionStart != -1 &&
                    newCursorPositionInText != inputState.displayBuffer.length

            if (inputState.composingRegionStart != -1) {
                val expectedComposingEnd = inputState.composingRegionStart + inputState.displayBuffer.length
                inputState.enqueueTypingOus(
                    InputStateManager.ExpectedTypingOus(
                        composingStart = inputState.composingRegionStart,
                        composingEnd = expectedComposingEnd,
                        cursorPosition = if (needsCursorRepositioning) {
                            inputState.composingRegionStart + newCursorPositionInText
                        } else {
                            expectedComposingEnd
                        }
                    )
                )
            } else {
                inputState.isActivelyEditing = true
            }

            if (needsCursorRepositioning) {
                outputBridge.beginBatchEdit()
                try {
                    outputBridge.setComposingText(inputState.displayBuffer, 1)
                    inputState.composingReassertionCount = 0
                    val absoluteCursorPosition = inputState.composingRegionStart + newCursorPositionInText
                    outputBridge.setSelection(absoluteCursorPosition, absoluteCursorPosition)
                } finally {
                    outputBridge.endBatchEdit()
                }
            } else {
                outputBridge.setComposingText(inputState.displayBuffer, 1)
                inputState.composingReassertionCount = 0
            }

            inputState.wordState =
                inputState.wordState.copy(
                    buffer = inputState.displayBuffer,
                    graphemeCount = inputState.displayBuffer.length
                )

            if (inputState.isUrlOrEmailField) {
                return
            }

            suggestionPipeline.requestSuggestions(
                buffer = inputState.displayBuffer,
                inputMethod = InputMethod.TYPED
            )
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "handleLetterInput", "char" to char)
            )
            onCoordinateStateClear()
            outputBridge.commitText(char, 1)
        }
    }
}
