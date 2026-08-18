package com.urik.keyboard.service

import com.urik.keyboard.KeyboardConstants.TextProcessingConstants
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.utils.CursorEditingUtils
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.UrlEmailDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SpaceInputHandler(
    private val inputState: InputStateManager,
    private val outputBridge: OutputBridge,
    private val suggestionPipeline: SuggestionPipeline,
    private val autoCorrectionEngine: AutoCorrectionEngine,
    private val swipeSpaceManager: SwipeSpaceManager,
    private val swipeDetector: SwipeDetector,
    private val candidateBarController: CandidateBarController,
    private val languageManager: LanguageManager,
    private val serviceScope: CoroutineScope,
    private val onGetCurrentSettings: () -> KeyboardSettings,
    private val onCheckAutoCapitalization: (textBefore: String) -> Unit,
    private val onJapaneseSpaceAdvance: () -> Unit = {}
) {
    fun handle(literalSpace: Boolean = false) {
        serviceScope.launch {
            try {
                if (inputState.requiresDirectCommit) {
                    outputBridge.sendSpace()
                    return@launch
                }

                if (inputState.isSuggestionsDisabled) {
                    outputBridge.sendSpace()
                    return@launch
                }

                // Japanese: Space ADVANCES the highlighted candidate (same as Tab) — the traditional
                // convert-on-space flow. Enter (確定) commits the highlighted candidate; tapping one commits
                // it directly. Space never inserts a literal space while a reading is composing.
                if (suggestionPipeline.isJapaneseLayout && inputState.displayBuffer.isNotEmpty()) {
                    onJapaneseSpaceAdvance()
                    return@launch
                }

                swipeSpaceManager.clearAutoSpaceFlag()

                if (inputState.displayBuffer.isNotEmpty()) {
                    val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                    val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        inputState.clearInternalStateOnly()
                        outputBridge.finishComposingText()
                    }
                }

                val currentTime = System.currentTimeMillis()
                val timeSinceLastSpace = currentTime - inputState.lastSpaceTime

                if (handleDoubleSpacePeriod(timeSinceLastSpace)) return@launch

                inputState.lastSpaceTime = currentTime

                // A dash is committed TIGHT ("slovo–"); Space right after one is the escape to its SPACED
                // form — the Czech pomlčka "slovo – ". Must be tested before the candidate commit below,
                // since the bar may well be showing next-word predictions at that moment.
                if (!literalSpace && inputState.displayBuffer.isEmpty() && expandDashToSpacedForm()) {
                    return@launch
                }

                // Cluster typing: a plain Space commits the highlighted candidate (the best one by default,
                // advanced by Tab) — both the current word's predictions AND the empty-buffer next-word
                // (bigram) predictions. Long-press Space (literalSpace) skips this and falls through to a
                // literal space — the escape for when you want a space, not the word.
                if (!literalSpace &&
                    inputState.clusterLayoutActive &&
                    inputState.pendingSuggestions.isNotEmpty()
                ) {
                    val idx = inputState.selectedCandidate.coerceIn(0, inputState.pendingSuggestions.size - 1)
                    val candidate = inputState.pendingSuggestions[idx]
                    // Real predictions: Space commits the best (or the Tab-advanced) one — index 0 by default.
                    // Custom-row entries are TAP-ONLY by default: on the custom DEFAULT row (nothing predicted)
                    // selectedCandidate is -1 (no explicit selection), so a plain Space enters a literal space.
                    // But once Tab has EXPLICITLY selected a custom entry (selectedCandidate >= 0), Space commits
                    // THAT entry via the custom path — the only way to enter a custom default-row word. (Bug B.)
                    if (!inputState.isCustomSuggestion(candidate)) {
                        suggestionPipeline.coordinateSuggestionSelection(candidate, onCheckAutoCapitalization)
                        return@launch
                    } else if (inputState.hasExplicitSelection) {
                        suggestionPipeline.coordinateCustomSuggestionSelection(candidate, onCheckAutoCapitalization)
                        return@launch
                    }
                }

                if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                    suggestionPipeline.confirmAndLearnWord(onCheckAutoCapitalization)
                    return@launch
                }

                if (!literalSpace &&
                    !inputState.clusterLayoutActive &&
                    inputState.displayBuffer.isNotEmpty() &&
                    onGetCurrentSettings().spellCheckEnabled &&
                    inputState.displayBuffer.length >= TextProcessingConstants.MIN_SPELL_CHECK_LENGTH
                ) {
                    val textBeforeForUrlCheck = outputBridge.safeGetTextBeforeCursor(100)
                    val isUrlOrEmail =
                        UrlEmailDetector.isUrlOrEmailContext(
                            currentWord = inputState.displayBuffer,
                            textBeforeCursor = textBeforeForUrlCheck,
                            nextChar = " "
                        )

                    if (!isUrlOrEmail) {
                        suggestionPipeline.cancelDebounceJob()
                        val decision = autoCorrectionEngine.decide(
                            buffer = inputState.displayBuffer,
                            spellCheckEnabled = onGetCurrentSettings().spellCheckEnabled,
                            autocorrectionEnabled = onGetCurrentSettings().autocorrectionEnabled,
                            pauseOnMisspelledWord = onGetCurrentSettings().pauseOnMisspelledWord,
                            lastAutocorrection = inputState.lastAutocorrection,
                            textBeforeCursor = textBeforeForUrlCheck,
                            nextChar = " "
                        )
                        when (decision) {
                            is AutocorrectDecision.None -> {
                                inputState.isActivelyEditing = true
                                suggestionPipeline.recordWordUsage(inputState.displayBuffer)
                                outputBridge.beginBatchEdit()
                                try {
                                    applyPronounCorrectionIfNeeded()
                                    swipeDetector.updateLastCommittedWord(inputState.displayBuffer)
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(" ", 1)
                                    inputState.clearInternalStateOnly()
                                    suggestionPipeline.showBigramPredictions()

                                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                    onCheckAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }

                            is AutocorrectDecision.ContractionBypass -> {
                                val displaySuggestions =
                                    suggestionPipeline.storeAndCapitalizeSuggestions(
                                        decision.suggestions,
                                        inputState.isCurrentWordAtSentenceStart
                                    )
                                val originalWord = inputState.displayBuffer
                                inputState.isActivelyEditing = true
                                suggestionPipeline.learnWordAndInvalidateCache(originalWord, InputMethod.TYPED)
                                outputBridge.beginBatchEdit()
                                try {
                                    applyPronounCorrectionIfNeeded()
                                    swipeDetector.updateLastCommittedWord(originalWord)
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(" ", 1)
                                    inputState.clearInternalStateOnly()

                                    if (displaySuggestions.isNotEmpty()) {
                                        inputState.postCommitReplacementState =
                                            PostCommitReplacementState(
                                                originalWord = originalWord,
                                                committedWord = originalWord
                                            )
                                        inputState.pendingSuggestions = displaySuggestions
                                        candidateBarController.updateSuggestions(displaySuggestions)
                                    } else {
                                        suggestionPipeline.showBigramPredictions()
                                    }

                                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                    onCheckAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }

                            is AutocorrectDecision.Pause -> {
                                val displaySuggestions =
                                    suggestionPipeline.storeAndCapitalizeSuggestions(
                                        decision.suggestions,
                                        inputState.isCurrentWordAtSentenceStart
                                    )
                                inputState.spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                                inputState.pendingWordForLearning = inputState.displayBuffer
                                outputBridge.highlightCurrentWord()
                                inputState.pendingSuggestions = displaySuggestions
                                if (displaySuggestions.isNotEmpty()) {
                                    inputState.updateSuggestionDisplay(displaySuggestions)
                                } else {
                                    // Custom-aware clear so the custom default row survives a Pause with no
                                    // spelling suggestions, instead of blanking the bar. (Bug 1.)
                                    inputState.clearSuggestionDisplay()
                                }
                                return@launch
                            }

                            is AutocorrectDecision.Correct -> {
                                val originalWord = inputState.displayBuffer
                                val rawCorrected = decision.suggestion
                                val pronounLang = languageManager.currentLanguage.value.split("-").first()
                                val pronounCorrected = if (pronounLang == "en") {
                                    EnglishPronounCorrection.capitalize(rawCorrected.lowercase()) ?: rawCorrected
                                } else {
                                    rawCorrected
                                }
                                val correctedWord = if (inputState.isCurrentWordAtSentenceStart) {
                                    pronounCorrected.replaceFirstChar { it.uppercaseChar() }
                                } else {
                                    pronounCorrected
                                }
                                inputState.isActivelyEditing = true
                                suggestionPipeline.recordWordUsage(correctedWord)
                                outputBridge.beginBatchEdit()
                                try {
                                    applyPronounCorrectionIfNeeded()
                                    outputBridge.commitText("$correctedWord ", 1)
                                    swipeDetector.updateLastCommittedWord(correctedWord)
                                    inputState.clearInternalStateOnly()
                                    inputState.postCommitReplacementState =
                                        PostCommitReplacementState(
                                            originalWord = originalWord,
                                            committedWord = correctedWord
                                        )
                                    inputState.lastAutocorrection =
                                        LastAutocorrection(
                                            originalTypedWord = originalWord,
                                            correctedWord = correctedWord
                                        )
                                    inputState.pendingSuggestions = listOf(originalWord)
                                    candidateBarController.updateSuggestions(inputState.pendingSuggestions)

                                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                    onCheckAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }

                            is AutocorrectDecision.Suggestions -> {
                                val displaySuggestions =
                                    suggestionPipeline.storeAndCapitalizeSuggestions(
                                        decision.list,
                                        inputState.isCurrentWordAtSentenceStart
                                    )
                                val originalWord = inputState.displayBuffer
                                inputState.isActivelyEditing = true
                                suggestionPipeline.learnWordAndInvalidateCache(originalWord, InputMethod.TYPED)
                                outputBridge.beginBatchEdit()
                                try {
                                    applyPronounCorrectionIfNeeded()
                                    swipeDetector.updateLastCommittedWord(originalWord)
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(" ", 1)
                                    inputState.clearInternalStateOnly()

                                    if (displaySuggestions.isNotEmpty()) {
                                        inputState.postCommitReplacementState =
                                            PostCommitReplacementState(
                                                originalWord = originalWord,
                                                committedWord = originalWord
                                            )
                                        inputState.pendingSuggestions = displaySuggestions
                                        candidateBarController.updateSuggestions(displaySuggestions)
                                    } else {
                                        suggestionPipeline.showBigramPredictions()
                                    }

                                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                    onCheckAutoCapitalization(textBefore)
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }
                        }
                    }
                }

                // Fast add-to-dictionary: a long-press Space commits the LITERAL word verbatim (the escape
                // from auto-correct / from committing a cluster candidate). Learn exactly the literal text
                // being inserted — the displayBuffer, which here IS what reaches the field via
                // finishComposingText() (on a cluster layout this is the chosen literal, not a substituted
                // candidate) — so it's offered next time and not auto-corrected away. Gated to real
                // alphabetic words (>= 2 letters); learnWordAndInvalidateCache itself no-ops when the
                // learning setting is off or the word is already in the dictionary. (Bug 4.)
                val wordToLearn = inputState.displayBuffer
                if (literalSpace && isLearnableWord(wordToLearn)) {
                    suggestionPipeline.learnWordAndInvalidateCache(wordToLearn, InputMethod.TYPED)
                }

                outputBridge.beginBatchEdit()
                try {
                    applyPronounCorrectionIfNeeded()
                    if (inputState.displayBuffer.isNotEmpty()) {
                        swipeDetector.updateLastCommittedWord(
                            inputState.displayBuffer
                        )
                    }
                    outputBridge.finishComposingText()
                    outputBridge.commitText(" ", 1)
                    inputState.clearInternalStateOnly()
                    suggestionPipeline.showBigramPredictions()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    onCheckAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleSpace")
                )
                outputBridge.finishComposingText()
                outputBridge.commitText(" ", 1)
                inputState.clearInternalStateOnly()
            }
        }
    }

    /**
     * Space pressed with the cursor right after a bare em/en dash: rewrite that dash as its SPACED form —
     * a space in front of it and one behind — and leave the cursor ready for the next word.
     *
     * This is the second half of the dash contract. A dash is entered tight, because that is the form that
     * cannot be typed afterwards without editing ("slovo–slovo", "1999–2003"); the spaced pomlčka
     * "slovo – slovo" is then one Space away, and typing the next word straight on keeps the tight form.
     * Czech needs both, English uses the tight one, and Russian's always-spaced dash is written spaced by
     * [NonLetterInputHandler] — there is no bare dash before the cursor there, so this never fires.
     *
     * The leading space is written only when a real word ends in front of the dash (the same rule the
     * openers use), so a dash starting a line — a dialogue dash — is not pushed off the margin. URL and
     * email fields are exempt: spaces would break the address.
     *
     * @return true when the press was consumed by the rewrite.
     */
    private suspend fun expandDashToSpacedForm(): Boolean {
        if (inputState.isUrlOrEmailField) return false
        val before = outputBridge.safeGetTextBeforeCursor(2)
        val dash = before.lastOrNull() ?: return false
        if (!CursorEditingUtils.isDash(dash)) return false

        val leading =
            if (CursorEditingUtils.needsSpaceBeforeOpeningPair(before.dropLast(1))) " " else ""
        outputBridge.beginBatchEdit()
        try {
            outputBridge.deleteSurroundingText(1, 0)
            outputBridge.commitText("$leading$dash ", 1)
            inputState.clearInternalStateOnly()
            // The trailing space is real text, not a deferred separator — the next word must not add another.
            inputState.pendingWordSeparator = false
            // This press was consumed by the rewrite, so it must not arm the double-space period: a second
            // Space would otherwise eat the dash's trailing space and leave "slovo –. ".
            inputState.lastSpaceTime = 0
            suggestionPipeline.showBigramPredictions()
            onCheckAutoCapitalization(outputBridge.safeGetTextBeforeCursor(50))
        } finally {
            outputBridge.endBatchEdit()
        }
        return true
    }

    private fun handleDoubleSpacePeriod(timeSinceLastSpace: Long): Boolean {
        if (timeSinceLastSpace > DOUBLE_TAP_SPACE_THRESHOLD_MS ||
            inputState.spellConfirmationState != SpellConfirmationState.NORMAL ||
            !onGetCurrentSettings().doubleSpacePeriod
        ) {
            return false
        }
        outputBridge.beginBatchEdit()
        try {
            if (inputState.wordState.hasContent && !inputState.requiresDirectCommit) {
                inputState.clearInternalStateOnly()
                outputBridge.finishComposingText()
            }
            outputBridge.deleteSurroundingText(1, 0)
            outputBridge.commitText(". ", 1)
            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            onCheckAutoCapitalization(textBefore)
        } finally {
            outputBridge.endBatchEdit()
        }
        inputState.lastSpaceTime = 0
        return true
    }

    /**
     * A literal-commit candidate worth learning: at least two characters and all letters (so symbols,
     * numbers, and single keystrokes are skipped). The committed surface IS the word here (non-cluster
     * layout), so the raw displayBuffer is the right thing to learn.
     */
    private fun isLearnableWord(word: String): Boolean =
        word.length >= 2 && word.all { it.isLetter() }

    private fun applyPronounCorrectionIfNeeded() {
        val pronounLang = languageManager.currentLanguage.value.split("-").first()
        if (pronounLang == "en" && inputState.displayBuffer.isNotEmpty()) {
            val corrected = EnglishPronounCorrection.capitalize(inputState.displayBuffer.lowercase())
            if (corrected != null && corrected != inputState.displayBuffer) {
                inputState.onPronounCapitalized(corrected)
                outputBridge.setComposingText(corrected, 1)
            }
        }
    }

    private companion object {
        const val DOUBLE_TAP_SPACE_THRESHOLD_MS = 250L
    }
}
