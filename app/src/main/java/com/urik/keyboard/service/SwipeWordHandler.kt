package com.urik.keyboard.service

import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.utils.CaseTransformer
import com.urik.keyboard.utils.ErrorLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SwipeWordHandler(
    private val inputState: InputStateManager,
    private val outputBridge: OutputBridge,
    private val suggestionPipeline: SuggestionPipeline,
    private val textInputProcessor: TextInputProcessor,
    private val wordLearningEngine: WordLearningEngine,
    private val languageManager: LanguageManager,
    private val caseTransformer: CaseTransformer,
    private val swipeSpaceManager: SwipeSpaceManager,
    private val swipeDetector: SwipeDetector,
    private val serviceScope: CoroutineScope,
    private val onGetKeyboardState: () -> KeyboardState,
    private val onCoordinateStateClear: () -> Unit,
    private val onCheckAutoCapitalization: (textBefore: String) -> Unit,
    private val onDisableShiftAfterSwipe: () -> Unit
) {
    /**
     * Pre-announce the selection update our setComposingText will trigger, exactly like typed
     * input does — without it, onUpdateSelection can classify the swipe's own composing update as
     * a non-sequential cursor jump (stale bookkeeping after an app-driven input restart, e.g.
     * Enter in some editors) and invalidate the composing state, wiping the just-published
     * candidates off the bar.
     */
    private fun announceExpectedSelection(displayWord: String) {
        if (inputState.composingRegionStart == -1) return
        val expectedEnd = inputState.composingRegionStart + displayWord.length
        // The tracker-level announcement is what actually defuses the jump classifier: composing a
        // whole word moves the cursor by more than JUMP_THRESHOLD in one hop, and on a fresh line
        // (no previous composing region — e.g. right after Enter) that read as a non-sequential
        // jump and wiped the composing state + candidates. Same pattern as the backspace word
        // delete and suggestion replacement.
        inputState.selectionStateTracker.setExpectedPositionAfterOperation(expectedEnd)
        inputState.enqueueTypingOus(
            InputStateManager.ExpectedTypingOus(
                composingStart = inputState.composingRegionStart,
                composingEnd = expectedEnd,
                cursorPosition = expectedEnd
            )
        )
    }

    /**
     * Show the swipe's own ranked candidates in the bar — the composed word first, alternates
     * after, exactly like cluster typing. Tapping one replaces the composing word via the normal
     * suggestion flow; Space commits the composed word as usual.
     */
    private fun publishSwipeCandidates(displayWord: String, alternates: List<String>, isSentenceStart: Boolean) {
        val casedAlternates = alternates.map { alt ->
            if (isSentenceStart) alt.replaceFirstChar { it.uppercaseChar() } else alt
        }
        val bar = (listOf(displayWord) + casedAlternates).distinct()
        inputState.pendingSuggestions = bar
        inputState.updateSuggestionDisplay(bar)
    }

    fun handle(rankedWords: List<String>) {
        val validatedWord = rankedWords.firstOrNull().orEmpty()
        val alternates = rankedWords.drop(1)
        try {
            inputState.clearBigramPredictions()

            if (inputState.isRawKeyEventField) return

            if (inputState.requiresDirectCommit || inputState.isSuggestionsDisabled) {
                if (!inputState.isSecureField && !inputState.isTerminalField) {
                    outputBridge.beginBatchEdit()
                    try {
                        val textBefore = outputBridge.safeGetTextBeforeCursor(1)
                        if (textBefore.isNotEmpty() && !swipeSpaceManager.isWhitespace(textBefore)) {
                            outputBridge.commitText(" ", 1)
                            swipeSpaceManager.markAutoSpaceInserted()
                        }
                        val keyboardState = onGetKeyboardState()
                        val isSentenceStart = keyboardState.isAutoShift
                        val isManualShifted =
                            keyboardState.isShiftPressed && !keyboardState.isAutoShift && !keyboardState.isCapsLockOn
                        val effectiveState =
                            if (isManualShifted) {
                                keyboardState.copy(isShiftPressed = true, isAutoShift = false)
                            } else {
                                keyboardState
                            }
                        if (keyboardState.isShiftPressed && !keyboardState.isCapsLockOn) {
                            onDisableShiftAfterSwipe()
                        }
                        val currentLanguage = languageManager.currentLanguage.value.split("-").first()
                        val displayWord = computeSwipeDisplayWord(
                            validatedWord = validatedWord,
                            learnedOriginalCase = null,
                            currentLanguage = currentLanguage,
                            keyboardState = effectiveState,
                            isSentenceStart = isSentenceStart
                        )
                        outputBridge.commitText(displayWord, 1)
                        val textAfterCommit = outputBridge.safeGetTextBeforeCursor(50)
                        onCheckAutoCapitalization(textAfterCommit)
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                } else {
                    outputBridge.commitText(validatedWord, 1)
                }
                return
            }

            if (inputState.displayBuffer.isNotEmpty()) {
                outputBridge.beginBatchEdit()
                try {
                    val pronounLang = languageManager.currentLanguage.value.split("-").first()
                    if (pronounLang == "en" && inputState.displayBuffer.isNotEmpty()) {
                        val corrected = EnglishPronounCorrection.capitalize(inputState.displayBuffer.lowercase())
                        if (corrected != null && corrected != inputState.displayBuffer) {
                            inputState.onPronounCapitalized(corrected)
                            outputBridge.setComposingText(corrected, 1)
                        }
                    }
                    swipeDetector.updateLastCommittedWord(inputState.displayBuffer)
                    outputBridge.commitText("${inputState.displayBuffer} ", 1)

                    onCoordinateStateClear()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    onCheckAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            }

            if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                outputBridge.clearSpellConfirmationState()
            }

            if (validatedWord.isEmpty()) return

            // Separator geometries (see LetterInputHandler): a previous commit suppressed its
            // trailing space (cursor at „word“|), or the cursor sits right after a closing
            // bracket/quote ("(test)|") — the swiped word needs its separator inserted first.
            // Pre-announced like every other text operation, else the space's selection update
            // desyncs the composing bookkeeping (the typed-path bug reversed letters).
            if (inputState.consumePendingWordSeparator() ||
                com.urik.keyboard.utils.CursorEditingUtils.needsSpaceAfterClosingPair(
                    outputBridge.safeGetTextBeforeCursor(2)
                )
            ) {
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

            val keyboardState = onGetKeyboardState()
            val isSentenceStart = keyboardState.isAutoShift
            val isManualShifted =
                keyboardState.isShiftPressed && !keyboardState.isAutoShift && !keyboardState.isCapsLockOn
            inputState.isCurrentWordAtSentenceStart = isSentenceStart
            inputState.isCurrentWordManualShifted = isManualShifted

            val effectiveState =
                if (isManualShifted) {
                    keyboardState.copy(isShiftPressed = true, isAutoShift = false)
                } else {
                    keyboardState
                }

            if (keyboardState.isShiftPressed && !keyboardState.isCapsLockOn) {
                onDisableShiftAfterSwipe()
            }

            serviceScope.launch {
                val swipeScriptCode = textInputProcessor.currentScriptCode
                try {
                    val currentLanguage =
                        languageManager.currentLanguage.value
                            .split("-")
                            .first()

                    val learnedOriginalCase =
                        wordLearningEngine.getLearnedWordOriginalCase(validatedWord, currentLanguage)

                    val displayWord =
                        computeSwipeDisplayWord(
                            validatedWord = validatedWord,
                            learnedOriginalCase = learnedOriginalCase,
                            currentLanguage = currentLanguage,
                            keyboardState = effectiveState,
                            isSentenceStart = isSentenceStart
                        )

                    val result =
                        textInputProcessor.processWordInput(validatedWord, InputMethod.SWIPED)

                    when (result) {
                        is ProcessingResult.Success -> {
                            withContext(Dispatchers.Main) {
                                inputState.isActivelyEditing = true
                                outputBridge.beginBatchEdit()
                                try {
                                    outputBridge.commitPreviousSwipeAndInsertSpace()
                                    outputBridge.setComposingText(displayWord, 1)
                                    inputState.composingRegionStart =
                                        outputBridge.safeGetCursorPosition() - displayWord.length
                                    inputState.displayBuffer = displayWord
                                    announceExpectedSelection(displayWord)
                                    suggestionPipeline.coordinateStateTransition(result.wordState)
                                    // The bar shows the swipe's OWN ranked candidates — composed word
                                    // first, cluster-style (coordinateStateTransition would have shown
                                    // spell-check neighbours and even filters the composed word out).
                                    publishSwipeCandidates(displayWord, alternates, isSentenceStart)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                if (result.shouldHighlight) {
                                    inputState.spellConfirmationState =
                                        SpellConfirmationState.AWAITING_CONFIRMATION
                                    inputState.pendingWordForLearning = result.wordState.buffer
                                    outputBridge.highlightCurrentWord()
                                }
                            }
                        }

                        is ProcessingResult.Error -> {
                            withContext(Dispatchers.Main) {
                                inputState.isActivelyEditing = true
                                outputBridge.beginBatchEdit()
                                try {
                                    outputBridge.commitPreviousSwipeAndInsertSpace()
                                    outputBridge.setComposingText(displayWord, 1)
                                    inputState.composingRegionStart =
                                        outputBridge.safeGetCursorPosition() - displayWord.length
                                    inputState.displayBuffer = displayWord
                                    inputState.wordState =
                                        WordState(
                                            buffer = displayWord,
                                            normalizedBuffer = validatedWord.lowercase(),
                                            isFromSwipe = true,
                                            graphemeCount = displayWord.length,
                                            scriptCode = swipeScriptCode
                                        )
                                    announceExpectedSelection(displayWord)
                                    publishSwipeCandidates(displayWord, alternates, isSentenceStart)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "UrikInputMethodService",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "handleSwipeWord_commitWord")
                    )
                    withContext(Dispatchers.Main) {
                        val fallbackSuggestion =
                            SpellingSuggestion(
                                word = validatedWord,
                                confidence = 1.0,
                                ranking = 0,
                                source = "swipe",
                                preserveCase = false
                            )
                        val fallbackDisplay = caseTransformer.applyCasing(
                            fallbackSuggestion,
                            effectiveState,
                            isSentenceStart
                        )
                        inputState.isActivelyEditing = true
                        outputBridge.beginBatchEdit()
                        try {
                            outputBridge.commitPreviousSwipeAndInsertSpace()
                            outputBridge.setComposingText(fallbackDisplay, 1)
                            inputState.composingRegionStart =
                                outputBridge.safeGetCursorPosition() - fallbackDisplay.length
                            inputState.displayBuffer = fallbackDisplay
                            inputState.wordState =
                                WordState(
                                    buffer = fallbackDisplay,
                                    normalizedBuffer = validatedWord.lowercase(),
                                    isFromSwipe = true,
                                    graphemeCount = fallbackDisplay.length,
                                    scriptCode = swipeScriptCode
                                )
                            announceExpectedSelection(fallbackDisplay)
                        } finally {
                            outputBridge.endBatchEdit()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "handleSwipeWord")
            )
            onCoordinateStateClear()
            outputBridge.setComposingText(validatedWord, 1)
        }
    }

    private fun computeSwipeDisplayWord(
        validatedWord: String,
        learnedOriginalCase: String?,
        currentLanguage: String,
        keyboardState: KeyboardState,
        isSentenceStart: Boolean = false
    ): String {
        val normalizedWord = validatedWord.lowercase()

        if (currentLanguage == "en") {
            val englishPronounForm = getEnglishPronounIForm(normalizedWord)
            if (englishPronounForm != null) {
                return englishPronounForm
            }
        }

        val wordToUse = learnedOriginalCase ?: validatedWord
        val preserveCase = learnedOriginalCase != null

        val suggestion =
            SpellingSuggestion(
                word = wordToUse,
                confidence = 1.0,
                ranking = 0,
                source = if (preserveCase) "learned" else "swipe",
                preserveCase = preserveCase
            )

        return caseTransformer.applyCasing(suggestion, keyboardState, isSentenceStart)
    }

    private fun getEnglishPronounIForm(normalizedWord: String): String? =
        EnglishPronounCorrection.capitalize(normalizedWord)
}
