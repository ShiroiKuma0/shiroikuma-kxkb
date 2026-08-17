package com.urik.keyboard.service

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import com.urik.keyboard.KeyboardConstants.TextProcessingConstants
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.utils.CursorEditingUtils
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.UrlEmailDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class NonLetterInputHandler(
    private val inputState: InputStateManager,
    private val outputBridge: OutputBridge,
    private val suggestionPipeline: SuggestionPipeline,
    private val autoCorrectionEngine: AutoCorrectionEngine,
    private val textInputProcessor: TextInputProcessor,
    private val swipeSpaceManager: SwipeSpaceManager,
    private val languageManager: LanguageManager,
    private val candidateBarController: CandidateBarController,
    private val serviceScope: CoroutineScope,
    private val onGetCurrentSettings: () -> KeyboardSettings,
    private val onCoordinateStateClear: () -> Unit,
    private val onCheckAutoCapitalization: (textBefore: String) -> Unit,
    private val onDisableCapsLockAfterPunctuation: () -> Unit
) {
    fun handle(char: String) {
        if (inputState.requiresDirectCommit) {
            outputBridge.sendCharacter(char)
            return
        }

        if (inputState.isSuggestionsDisabled) {
            outputBridge.sendCharacter(char)
            return
        }
        serviceScope.launch {
            try {
                inputState.lastSpaceTime = 0

                // "…"/"-" on a NON-cluster layout: the composing buffer IS the typed word, so commit it
                // literally (ellipsis) or commit the top candidate (hyphen) here. On a CLUSTER layout the
                // buffer is centre-letters, so these fall through to the cluster candidate-commit branch below
                // (which commits the predicted word, not the centres) — gated here on !clusterLayoutActive.
                if ((char == ELLIPSIS || char == THREE_DOTS) && !inputState.clusterLayoutActive) {
                    handleEllipsis(char)
                    return@launch
                }
                if (char == "-" &&
                    !inputState.clusterLayoutActive &&
                    inputState.displayBuffer.isNotEmpty() &&
                    inputState.pendingSuggestions.isNotEmpty()
                ) {
                    handleHyphenCommit()
                    return@launch
                }

                // Auto-spacing: once a word is committed it carries a trailing space ("word "). Typing
                // closing punctuation then eats that space, attaches the mark to the word, and re-adds a
                // trailing space — "word " + "." -> "word. ". Only when nothing is composing (the word is
                // already out) and there really is a word-then-space before the cursor.
                if (char.length == 1 && inputState.displayBuffer.isEmpty() && char.single() in CLOSING_PUNCTUATION) {
                    val before = outputBridge.safeGetTextBeforeCursor(2)
                    if (before.length >= 2 && before.last() == ' ' && !before[before.length - 2].isWhitespace()) {
                        val single = char.single()
                        // …unless the mark is typed INSIDE a pair („word |“): the re-added space would
                        // land in front of the closer. Suppress it and remember it as the next word's
                        // separator, exactly like a word commit does. A mark closing a number ("10 :")
                        // defers its space the same way, so a time comes out as "10:35".
                        val suppressTrailing = nextCharSuppressesTrailingSpace() ||
                            CursorEditingUtils.isNumberInternalMark(single, before[before.length - 2])
                        outputBridge.beginBatchEdit()
                        try {
                            outputBridge.deleteSurroundingText(1, 0)
                            outputBridge.commitText(if (suppressTrailing) char else "$char ", 1)
                            inputState.pendingWordSeparator = suppressTrailing
                            inputState.lastAutocorrection = null
                            if (inputState.postCommitReplacementState != null) {
                                inputState.postCommitReplacementState = null
                                // Route through the custom-aware clear so the custom default row re-shows
                                // (instead of a blank bar) when the post-commit bar is dismissed. (Bug 1.)
                                inputState.clearSuggestionDisplay()
                            }
                            swipeSpaceManager.clearAutoSpaceFlag()
                            if (isSentenceEndingPunctuation(single) && !inputState.requiresDirectCommit) {
                                onDisableCapsLockAfterPunctuation()
                                onCheckAutoCapitalization(outputBridge.safeGetTextBeforeCursor(50))
                            }
                            suggestionPipeline.showBigramPredictions()
                        } finally {
                            outputBridge.endBatchEdit()
                        }
                        return@launch
                    }
                }

                if (char.length == 1) {
                    val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(1)
                    if (swipeSpaceManager.shouldRemoveSpaceForPunctuation(char.single(), textBeforeCursor)) {
                        outputBridge.deleteSurroundingText(1, 0)
                    }
                }

                if (inputState.displayBuffer.isNotEmpty()) {
                    val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                    val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        onCoordinateStateClear()
                    }
                }

                if (inputState.postCommitReplacementState != null) {
                    inputState.postCommitReplacementState = null
                    // Custom-aware clear: dropping the post-commit bar must restore the custom default
                    // row, not blank it, so the row survives ordinary character input. (Bug 1.)
                    inputState.clearSuggestionDisplay()
                }
                inputState.lastAutocorrection = null

                if (inputState.spellConfirmationState == SpellConfirmationState.AWAITING_CONFIRMATION) {
                    outputBridge.beginBatchEdit()
                    try {
                        suggestionPipeline.confirmAndLearnWord(onCheckAutoCapitalization)
                        outputBridge.commitText(char, 1)

                        if (char.length == 1) {
                            val singleChar = char.single()
                            if (isSentenceEndingPunctuation(singleChar) && !inputState.requiresDirectCommit) {
                                onDisableCapsLockAfterPunctuation()
                                val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                onCheckAutoCapitalization(textBefore)
                            }
                        }
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                    return@launch
                }

                // Cluster typing: the composing buffer is the tapped clusters' CENTRE letters (e.g. "Deh"),
                // not a misspelling of the intended word ("Yes"), so the spell-based auto-correct below can't
                // recover it and would commit the literal centres + mark ("Deh."). Mirror Space's cluster
                // path: commit the highlighted candidate first, then append the mark with the right spacing,
                // so "Yes" + "." -> "Yes.". This also covers brackets/quotes/em-dash typed as CHARACTERS on a
                // cluster key (e.g. "-" is a flick-up on the "—j_" key): they must TERMINATE the word and NOT
                // be appended to the centre-letter buffer (which would make "long" + "-" -> garbage "litd-").
                // (Bugs A & C.)
                if (char.length == 1 &&
                    isClusterTerminator(char.single()) &&
                    inputState.clusterLayoutActive
                ) {
                    // Commit the composing word's candidate first IF a word is being composed; otherwise just
                    // enter the mark directly. Either way the mark is entered here (with the right spacing),
                    // never appended to the centre-letter buffer.
                    if (inputState.displayBuffer.isNotEmpty() && inputState.pendingSuggestions.isNotEmpty()) {
                        suggestionPipeline.cancelDebounceJob()
                        val idx = inputState.selectedCandidate.coerceIn(0, inputState.pendingSuggestions.size - 1)
                        val candidate = inputState.pendingSuggestions[idx]
                        // A custom-row entry commits only when Tab EXPLICITLY selected it — same rule as
                        // Space/Enter. Without the gate, composing a word with zero real candidates (the bar
                        // falls back to the custom toolbar) made every punctuation press auto-commit the
                        // toolbar's first entry ("+" before the mark). A real prediction goes through the
                        // dictionary-selection path (spell-learn / bigram) as before.
                        if (inputState.isCustomSuggestion(candidate)) {
                            if (inputState.hasExplicitSelection) {
                                suggestionPipeline.coordinateCustomSuggestionSelection(candidate, onCheckAutoCapitalization)
                            } else {
                                // Nothing selected: finish the literally-typed word as-is; then the mark below.
                                outputBridge.finishComposingText()
                                inputState.clearInternalStateOnly()
                            }
                        } else {
                            suggestionPipeline.coordinateSuggestionSelection(candidate, onCheckAutoCapitalization)
                        }
                    }
                    commitPunctuationAfterClusterCommit(char.single())
                    return@launch
                }

                if (inputState.displayBuffer.isNotEmpty() &&
                    onGetCurrentSettings().spellCheckEnabled &&
                    inputState.displayBuffer.length >= TextProcessingConstants.MIN_SPELL_CHECK_LENGTH
                ) {
                    val textBefore = outputBridge.safeGetTextBeforeCursor(100)
                    val isUrlOrEmail =
                        UrlEmailDetector.isUrlOrEmailContext(
                            currentWord = inputState.displayBuffer,
                            textBeforeCursor = textBefore,
                            nextChar = char
                        )

                    if (!isUrlOrEmail) {
                        val isPunctuation =
                            char.length == 1 && CursorEditingUtils.isPunctuation(char.single())

                        if (isPunctuation) {
                            suggestionPipeline.cancelDebounceJob()
                            // Punctuation closes the word: apply the SAME auto-correct Space would, then the
                            // mark — so "teh." becomes "the." not "teh.". Gated on the auto-correct setting
                            // (decide() returns Correct only when autocorrectionEnabled). No trailing space
                            // is added — just <corrected-word><punctuation>.
                            val decision = autoCorrectionEngine.decide(
                                buffer = inputState.displayBuffer,
                                spellCheckEnabled = onGetCurrentSettings().spellCheckEnabled,
                                autocorrectionEnabled = onGetCurrentSettings().autocorrectionEnabled,
                                pauseOnMisspelledWord = onGetCurrentSettings().pauseOnMisspelledWord,
                                lastAutocorrection = inputState.lastAutocorrection,
                                textBeforeCursor = textBefore,
                                nextChar = char
                            )
                            if (decision is AutocorrectDecision.Correct) {
                                commitCorrectedThenPunctuation(decision.suggestion, char)
                                return@launch
                            }

                            val isValid = textInputProcessor.validateWord(inputState.displayBuffer)
                            if (!isValid) {
                                outputBridge.beginBatchEdit()
                                try {
                                    val pronounLang = languageManager.currentLanguage.value.split("-").first()
                                    if (pronounLang == "en" && inputState.displayBuffer.isNotEmpty()) {
                                        val corrected = EnglishPronounCorrection.capitalize(
                                            inputState.displayBuffer.lowercase()
                                        )
                                        if (corrected != null && corrected != inputState.displayBuffer) {
                                            inputState.onPronounCapitalized(corrected)
                                            outputBridge.setComposingText(corrected, 1)
                                        }
                                    }
                                    suggestionPipeline.learnWordAndInvalidateCache(
                                        inputState.displayBuffer,
                                        InputMethod.TYPED
                                    )
                                    outputBridge.finishComposingText()
                                    outputBridge.commitText(char, 1)

                                    val singleChar = char.single()
                                    if (isSentenceEndingPunctuation(singleChar) &&
                                        !inputState.requiresDirectCommit
                                    ) {
                                        onDisableCapsLockAfterPunctuation()
                                        val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                                        onCheckAutoCapitalization(textBefore)
                                    }

                                    onCoordinateStateClear()
                                    suggestionPipeline.showBigramPredictions()
                                } finally {
                                    outputBridge.endBatchEdit()
                                }
                                return@launch
                            }
                        } else {
                            suggestionPipeline.cancelDebounceJob()
                            val decision = autoCorrectionEngine.decide(
                                buffer = inputState.displayBuffer,
                                spellCheckEnabled = onGetCurrentSettings().spellCheckEnabled,
                                autocorrectionEnabled = onGetCurrentSettings().autocorrectionEnabled,
                                pauseOnMisspelledWord = onGetCurrentSettings().pauseOnMisspelledWord,
                                lastAutocorrection = inputState.lastAutocorrection,
                                textBeforeCursor = textBefore,
                                nextChar = char
                            )
                            when (decision) {
                                is AutocorrectDecision.Pause -> {
                                    inputState.spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION
                                    inputState.pendingWordForLearning = inputState.displayBuffer
                                    outputBridge.highlightCurrentWord()
                                    val suggestions = textInputProcessor.getSuggestions(inputState.displayBuffer)
                                    val displaySuggestions =
                                        suggestionPipeline.storeAndCapitalizeSuggestions(
                                            suggestions,
                                            inputState.isCurrentWordAtSentenceStart
                                        )
                                    inputState.pendingSuggestions = displaySuggestions
                                    // Route through the state funnel so the custom row merges (default when
                                    // empty, appended otherwise) instead of bypassing it via the bar directly.
                                    if (displaySuggestions.isNotEmpty()) {
                                        inputState.updateSuggestionDisplay(displaySuggestions)
                                    } else {
                                        inputState.clearSuggestionDisplay()
                                    }
                                    return@launch
                                }

                                else -> { /* fall through to commit char */ }
                            }
                        }
                    }
                }

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
                    suggestionPipeline.coordinateWordCompletion()
                    suggestionPipeline.showBigramPredictions()
                    outputBridge.commitText(char, 1)

                    if (char.length == 1) {
                        val singleChar = char.single()
                        if (isSentenceEndingPunctuation(singleChar) && !inputState.requiresDirectCommit) {
                            onDisableCapsLockAfterPunctuation()
                            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                            onCheckAutoCapitalization(textBefore)
                        }
                    }
                } finally {
                    outputBridge.endBatchEdit()
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleNonLetterInput")
                )
            }
        }
    }

    /**
     * After a cluster candidate has been committed (which appends a trailing space, like Space does), attach
     * the punctuation [punctuation] with the right spacing: delete that auto-space, commit the mark, and decide
     * the trailing space.
     *  - CLOSING_PUNCTUATION (`.,?!:;`), ellipsis, closing brackets/quotes (`)]}"`…), and the spaced dashes
     *    all get a trailing space (they end a clause/word).
     *  - OPENING brackets/quotes (`([{"`…) join the FOLLOWING word, so NO trailing space.
     *  - The hyphen "-" joins words, so NO trailing space.
     * Re-runs sentence-end auto-cap so the next word capitalises. (Bugs A & C.)
     */
    private suspend fun commitPunctuationAfterClusterCommit(punctuation: Char) {
        outputBridge.beginBatchEdit()
        try {
            // Openers ("word (") and the spaced dashes ("word — ") KEEP the preceding space; everything else
            // (closers "word) ", the hyphen "word-", ellipsis/.,?!:;) attaches to the word, eating that space.
            val keepPreceding = punctuation in OPENING_BRACKETS_QUOTES || punctuation in SPACED_DASHES
            val before = outputBridge.safeGetTextBeforeCursor(1)
            // Inside a pair („Ahoj|“) there IS no preceding space to keep — the word commit suppressed it —
            // so a mark that wants one has to write it itself, or it glues to the word („Ahoj(“, „Ahoj—“).
            val leading = if (keepPreceding && needsLeadingSpace(before)) " " else ""
            if (!keepPreceding && before == " ") {
                outputBridge.deleteSurroundingText(1, 0)
            }
            val wantsTrailingSpace =
                punctuation in CLOSING_PUNCTUATION ||
                    punctuation == '…' ||
                    punctuation in CLOSING_BRACKETS_QUOTES ||
                    punctuation in SPACED_DASHES
            // A mark typed INSIDE a pair („Ahoj|“, “word|”, (word|) ) must not push its space in front of
            // the closer — suppress it exactly like a word commit does and remember it as the following
            // word's separator: „Ahoj|“ + "," gives „Ahoj,|“ (not the stranded-space „Ahoj, |“), and the
            // next word then starts with its separator -> „Ahoj, světe|“. A `:`, `,` or `.` straight after
            // a digit defers its space on the same rule: it may belong inside the number, so "10:" runs on
            // as "10:35" and "3," as "3,14", while a word after it ("bod 3: text", "10. května") still gets
            // the separator back.
            val suppressTrailing = wantsTrailingSpace &&
                (
                    nextCharSuppressesTrailingSpace() ||
                        CursorEditingUtils.isNumberInternalMark(punctuation, before.lastOrNull())
                )
            // Openers and the hyphen attach to what follows -> no trailing space.
            val trailing = if (wantsTrailingSpace && !suppressTrailing) " " else ""
            outputBridge.commitText("$leading$punctuation$trailing", 1)
            inputState.pendingWordSeparator = suppressTrailing
            swipeSpaceManager.clearAutoSpaceFlag()
            inputState.lastAutocorrection = null
            if (isSentenceEndingPunctuation(punctuation) && !inputState.requiresDirectCommit) {
                onDisableCapsLockAfterPunctuation()
                onCheckAutoCapitalization(outputBridge.safeGetTextBeforeCursor(50))
            }
        } finally {
            outputBridge.endBatchEdit()
        }
    }

    private fun isClusterTerminator(c: Char): Boolean = isClusterTerminatorChar(c)

    /**
     * True when the text right after the cursor already continues with whitespace or a mark — the closing
     * half of the pair the user is typing inside („word|“). A punctuation commit there drops its trailing
     * space (which would otherwise sit in front of the closer) and hands it to the next word via
     * [InputStateManager.pendingWordSeparator]; the same rule the word commits use.
     */
    private fun nextCharSuppressesTrailingSpace(): Boolean =
        CursorEditingUtils.trailingSpaceSuppressedByNextChar(
            outputBridge.safeGetTextAfterCursor(1).firstOrNull()
        )

    /**
     * True when a mark that KEEPS a preceding space (an opener, a spaced dash) has none in front of it
     * because the commit before it suppressed one — the „Ahoj|“ + "(" case, which would otherwise glue as
     * „Ahoj(“. The rule itself is [CursorEditingUtils.needsSpaceBeforeOpeningPair], shared with the custom
     * row's cursor pairs. URL and email fields are exempt — a space injected into "example.com/(" would
     * break the address.
     */
    private fun needsLeadingSpace(textBeforeCursor: String): Boolean =
        !inputState.isUrlOrEmailField &&
            CursorEditingUtils.needsSpaceBeforeOpeningPair(textBeforeCursor)

    /**
     * Commit the auto-corrected word immediately followed by [punctuation] (no trailing space), the same
     * correction Space applies via AutocorrectDecision.Correct — but ending the word with the mark. Applies
     * English pronoun ("i" -> "I") and sentence-start capitalisation to the corrected form, records usage,
     * and registers a PostCommitReplacementState so the original word can be restored from the bar.
     */
    private suspend fun commitCorrectedThenPunctuation(rawCorrected: String, punctuation: String) {
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
            outputBridge.finishComposingText()
            outputBridge.commitText("$correctedWord$punctuation", 1)
            swipeSpaceManager.clearAutoSpaceFlag()
            inputState.clearInternalStateOnly()
            // No PostCommitReplacementState here: the bar-revert flow expects a trailing space after the
            // committed word, which the punctuation commit deliberately omits, so it wouldn't match. The
            // user can undo via Backspace instead.

            if (punctuation.length == 1 &&
                isSentenceEndingPunctuation(punctuation.single()) &&
                !inputState.requiresDirectCommit
            ) {
                onDisableCapsLockAfterPunctuation()
                val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                onCheckAutoCapitalization(textBefore)
            }

            suggestionPipeline.showBigramPredictions()
        } finally {
            outputBridge.endBatchEdit()
        }
    }

    /**
     * NON-cluster hyphen: commit the top candidate (the same selection paths Space / closing punctuation use),
     * then append "-" with NO trailing space so the hyphen joins the word to whatever follows. The candidate
     * commit appends an auto-space, so that space is deleted before the "-" is written.
     */
    private suspend fun handleHyphenCommit() {
        suggestionPipeline.cancelDebounceJob()
        val idx = inputState.selectedCandidate.coerceIn(0, inputState.pendingSuggestions.size - 1)
        val candidate = inputState.pendingSuggestions[idx]
        if (inputState.isCustomSuggestion(candidate)) {
            suggestionPipeline.coordinateCustomSuggestionSelection(candidate, onCheckAutoCapitalization)
        } else {
            suggestionPipeline.coordinateSuggestionSelection(candidate, onCheckAutoCapitalization)
        }
        outputBridge.beginBatchEdit()
        try {
            val before = outputBridge.safeGetTextBeforeCursor(1)
            if (before == " ") {
                outputBridge.deleteSurroundingText(1, 0)
            }
            outputBridge.commitText("-", 1)
            swipeSpaceManager.clearAutoSpaceFlag()
            inputState.lastAutocorrection = null
        } finally {
            outputBridge.endBatchEdit()
        }
    }

    /**
     * NON-cluster ellipsis: close any composing word first (committing the literal typed word, learning a
     * non-dictionary one), eat a single preceding auto-space so the mark attaches, commit "<ellipsis> " with
     * one trailing space, and re-run sentence-end auto-cap.
     */
    private suspend fun handleEllipsis(ellipsis: String) {
        if (inputState.displayBuffer.isNotEmpty()) {
            outputBridge.beginBatchEdit()
            try {
                val pronounLang = languageManager.currentLanguage.value.split("-").first()
                if (pronounLang == "en") {
                    val corrected = EnglishPronounCorrection.capitalize(inputState.displayBuffer.lowercase())
                    if (corrected != null && corrected != inputState.displayBuffer) {
                        inputState.onPronounCapitalized(corrected)
                        outputBridge.setComposingText(corrected, 1)
                    }
                }
                if (onGetCurrentSettings().spellCheckEnabled &&
                    !textInputProcessor.validateWord(inputState.displayBuffer)
                ) {
                    suggestionPipeline.learnWordAndInvalidateCache(inputState.displayBuffer, InputMethod.TYPED)
                } else {
                    suggestionPipeline.recordWordUsage(inputState.displayBuffer)
                }
                outputBridge.finishComposingText()
            } finally {
                outputBridge.endBatchEdit()
            }
        }
        outputBridge.beginBatchEdit()
        try {
            val before = outputBridge.safeGetTextBeforeCursor(1)
            if (before == " ") {
                outputBridge.deleteSurroundingText(1, 0)
            }
            // Inside a pair („Ahoj…|“) the trailing space would land before the closer: suppress it and
            // hand it to the next word as its separator, like every other mark and word commit.
            val suppressTrailing = nextCharSuppressesTrailingSpace()
            outputBridge.commitText(if (suppressTrailing) ellipsis else "$ellipsis ", 1)
            inputState.pendingWordSeparator = suppressTrailing
            swipeSpaceManager.clearAutoSpaceFlag()
            inputState.lastAutocorrection = null
            inputState.postCommitReplacementState = null
            if (!inputState.requiresDirectCommit) {
                onDisableCapsLockAfterPunctuation()
                onCheckAutoCapitalization(outputBridge.safeGetTextBeforeCursor(50))
            }
            onCoordinateStateClear()
            suggestionPipeline.showBigramPredictions()
        } finally {
            outputBridge.endBatchEdit()
        }
    }

    private fun isSentenceEndingPunctuation(char: Char): Boolean =
        char == '…' || UCharacter.hasBinaryProperty(char.code, UProperty.S_TERM)

    companion object {
        // Punctuation that attaches to the preceding word, eating an auto-space before it.
        private val CLOSING_PUNCTUATION = setOf('.', ',', '?', '!', ':', ';')

        // The SPACED dashes — em (U+2014) and en (U+2013, the Czech/German "pomlčka" set off by spaces on
        // both sides). They set a clause off, so they keep the space BEFORE them and get one AFTER — unlike
        // the hyphen, which glues words. Neither is caught by isPunctuation (it excludes DASH_PUNCTUATION),
        // so both must be named explicitly, here and in isClusterTerminatorChar.
        private val SPACED_DASHES = setOf('—', '–')

        // Opening brackets/quotes: typed as a CHARACTER, they begin a group and join the FOLLOWING word, so
        // they terminate the composing cluster word but get NO trailing space. Includes the curly opens.
        private val OPENING_BRACKETS_QUOTES = setOf('(', '[', '{', '"', '“', '‘')

        // Closing brackets/quotes: they end a group, so they get a trailing space. (The straight double quote
        // `"` is ambiguous; it's handled as a closer here — matching the common close-then-space expectation.)
        // The closing single quote U+2019 is intentionally NOT here: it's the apostrophe, never a terminator.
        private val CLOSING_BRACKETS_QUOTES = setOf(')', ']', '}', '"', '”')

        // The single ellipsis char (U+2026) and the literal three-dot token, both treated as a sentence end.
        private const val ELLIPSIS = "…"
        private const val THREE_DOTS = "..."

        /**
         * A single char that, on a CLUSTER layout, must terminate the composing word (commit the candidate) and
         * be entered as a standalone mark — never appended to the centre-letter buffer. Covers ordinary
         * punctuation (`isPunctuation`: brackets, the straight/curly double quotes, etc.), the hyphen, the
         * ellipsis, the em dash, and the opening brackets/curly opens. The straight/curly apostrophe
         * (`'` / U+2019) is deliberately excluded — it's a contraction character ("don't"), not a terminator.
         * The service's onLetterInput routing uses this to redirect such a flick to NonLetterInputHandler so it
         * doesn't get appended to the cluster buffer. (Bug C.)
         */
        fun isClusterTerminatorChar(c: Char): Boolean =
            CursorEditingUtils.isPunctuation(c) ||
                c == '-' ||
                c == '…' ||
                c in SPACED_DASHES ||
                c in OPENING_BRACKETS_QUOTES
    }
}
