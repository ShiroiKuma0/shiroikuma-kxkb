package com.urik.keyboard.service

import com.urik.keyboard.data.WordFrequencyRepository
import com.urik.keyboard.utils.CaseTransformer
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.KanaTransformUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SuggestionPipeline(
    private val host: SuggestionPipelineHost,
    private val state: InputStateManager,
    private val outputBridge: OutputBridge,
    private val textInputProcessor: TextInputProcessor,
    private val spellCheckManager: SpellCheckManager,
    private val wordLearningEngine: WordLearningEngine,
    private val wordFrequencyRepository: WordFrequencyRepository,
    private val languageManager: LanguageManager,
    private val caseTransformer: CaseTransformer,
    private val scriptConverterRegistry: ScriptConverterRegistry,
    private val serviceScope: CoroutineScope
) {
    private var suggestionDebounceJob: Job? = null
    private val suggestionDebounceDelay = SUGGESTION_DEBOUNCE_MS
    var isJapaneseLayout: Boolean = false
        private set

    fun setJapaneseLayout(japanese: Boolean) {
        isJapaneseLayout = japanese
    }

    fun requestSuggestions(buffer: String, inputMethod: InputMethod) {
        if (state.isSuggestionsDisabled) return
        state.showDegradedIndicator(spellCheckManager.isDegradedMode)
        if (isJapaneseLayout) {
            requestJapaneseSuggestions(buffer)
            return
        }
        val (currentSequence, bufferSnapshot) = state.getSequenceAndBuffer()

        suggestionDebounceJob?.cancel()
        suggestionDebounceJob =
            serviceScope.launch(Dispatchers.Default) {
                try {
                    delay(suggestionDebounceDelay)

                    val result = textInputProcessor.processWordInput(bufferSnapshot, inputMethod)

                    withContext(Dispatchers.Main) {
                        if (state.isSequenceCurrent(currentSequence, bufferSnapshot)) {
                            when (result) {
                                is ProcessingResult.Success -> {
                                    state.wordState = result.wordState
                                    if (result.wordState.suggestions.isNotEmpty() && host.showSuggestions()) {
                                        val displaySuggestions =
                                            storeAndCapitalizeSuggestions(
                                                result.wordState.suggestions,
                                                state.isCurrentWordAtSentenceStart
                                            )
                                        state.pendingSuggestions = displaySuggestions
                                        state.updateSuggestionDisplay(displaySuggestions)
                                    } else {
                                        state.pendingSuggestions = emptyList()
                                        state.clearSuggestionDisplay()
                                    }
                                }

                                is ProcessingResult.Error -> {
                                    state.pendingSuggestions = emptyList()
                                    state.clearSuggestionDisplay()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "SuggestionPipeline",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "requestSuggestions")
                    )
                }
            }
    }

    fun coordinateStateTransition(newWordState: WordState) {
        if (state.isSuggestionsDisabled) return
        state.wordState = newWordState

        if (newWordState.suggestions.isNotEmpty()) {
            val filteredSuggestions =
                newWordState.suggestions.filterNot { suggestion ->
                    suggestion.word.equals(state.displayBuffer, ignoreCase = true)
                }

            val displaySuggestions =
                storeAndCapitalizeSuggestions(filteredSuggestions, state.isCurrentWordAtSentenceStart)
            state.pendingSuggestions = displaySuggestions
            state.updateSuggestionDisplay(displaySuggestions)
        } else {
            state.pendingSuggestions = emptyList()
            state.clearSuggestionDisplay()
        }
    }

    fun storeAndCapitalizeSuggestions(
        suggestions: List<SpellingSuggestion>,
        isSentenceStart: Boolean = false
    ): List<String> {
        // Inject English contractions only when ENGLISH is the language being typed (the layout language),
        // not the primary language — otherwise typing Czech "id" surfaced the English contraction "I'd".
        val withContractions =
            Contractions.injectForWord(suggestions, state.displayBuffer, host.currentLayoutLanguage())
        state.currentRawSuggestions = withContractions
        return capitalizeSuggestions(withContractions, isSentenceStart)
    }

    /**
     * The keyboard casing state to apply to suggestions, normalised so the bar shows EXACTLY what a commit
     * will insert. Manual shift on the current word is honoured even after the live shift latch has cleared;
     * everything else (caps-lock, auto-shift) is read straight from the keyboard state. Used for both the
     * displayed bar strings and the committed form so the two can never diverge.
     */
    private fun effectiveKeyboardState(): com.urik.keyboard.model.KeyboardState {
        val keyboardState = host.getKeyboardState()
        return if (state.isCurrentWordManualShifted && !keyboardState.isShiftPressed && !keyboardState.isCapsLockOn) {
            keyboardState.copy(isShiftPressed = true, isAutoShift = false)
        } else {
            keyboardState
        }
    }

    fun capitalizeSuggestions(suggestions: List<SpellingSuggestion>, isSentenceStart: Boolean = false): List<String> {
        val lang = host.currentLayoutLanguage().split("-").first()
        if (lang in CASELESS_LANGUAGES) {
            return suggestions.map { it.word }
        }
        // The bar must show the case that will actually be committed: manual shift / caps-lock AND the
        // sentence-start auto-capital all apply here, exactly as recaseForCommit applies them on selection.
        // Both paths share effectiveKeyboardState() + isCurrentWordAtSentenceStart so display == commit.
        val sentenceStart = isSentenceStart || state.isCurrentWordAtSentenceStart
        return caseTransformer.applyCasingToSuggestions(suggestions, effectiveKeyboardState(), sentenceStart)
    }

    /**
     * The full candidate list for the expandable "more candidates" pane. For a typed word it re-queries the
     * cluster DAWG for many candidates; for the empty-buffer next-word state it returns the current bigram
     * list. Cased like the bar (dictionary case under auto-caps; the commit re-cases on selection).
     */
    fun expandedClusterCandidates(maxResults: Int = 48): List<String> {
        val buffer = state.displayBuffer
        // Empty buffer: the pending row IS the (bigram / custom default) list — already merged. For a typed
        // word, re-query the DAWG, then append the custom row AFTER the predictions so the pane shows the
        // same predictions-then-custom ordering as the bar. (Bug C — custom entries in the expand pane.)
        if (buffer.isEmpty()) return state.withCustomRow(state.pendingSuggestions)
        // The cluster candidates belong to the active LAYOUT language (the bands are its), NOT the primary
        // language — using the primary made a Czech expand-pane list English words. (Cross-language pollution.)
        val lang = host.currentLayoutLanguage().split("-").first()
        val words = spellCheckManager.clusterCandidatesFor(buffer, lang, maxResults)
        if (words.isEmpty()) return state.withCustomRow(state.pendingSuggestions)
        val predictions =
            capitalizeSuggestions(words.map { SpellingSuggestion(it, 0.0, 0, "cluster") }).distinct()
        return state.withCustomRow(predictions)
    }

    /**
     * Re-case a chosen candidate for commit. The bar already shows the committed form (capitalizeSuggestions
     * uses the same effectiveKeyboardState() + isCurrentWordAtSentenceStart), so this is normally idempotent;
     * it stays as a safety net for the manual-shift latch having cleared between display and selection.
     */
    /**
     * Capitalise a standalone English pronoun ("i" -> "I", "i'm" -> "I'm", …) on an English layout; any other
     * word, or a non-English layout, is returned unchanged. Used so a cluster candidate commit matches the
     * casing the non-cluster auto-correct path already produces. (Bug D.)
     */
    private fun applyPronounCorrection(word: String): String {
        val lang = host.currentLayoutLanguage().split("-").first()
        if (lang != "en") return word
        return EnglishPronounCorrection.capitalize(word.lowercase()) ?: word
    }

    private fun recaseForCommit(displayed: String): String {
        if (displayed.isEmpty()) return displayed
        val lang = host.currentLayoutLanguage().split("-").first()
        if (lang in CASELESS_LANGUAGES) return displayed
        // Re-derive from the raw suggestion (preserving its preserveCase flag) so a learned/proper-noun
        // candidate is cased on commit exactly as it was shown in the bar.
        val raw = state.currentRawSuggestions.firstOrNull { it.word.equals(displayed, ignoreCase = true) }
        val suggestion = raw ?: SpellingSuggestion(displayed, 0.0, 0)
        return caseTransformer.applyCasing(suggestion, effectiveKeyboardState(), state.isCurrentWordAtSentenceStart)
    }

    fun showBigramPredictions() {
        if (state.requiresDirectCommit ||
            state.isSuggestionsDisabled ||
            !host.showSuggestions() ||
            state.lastCommittedWord.isBlank()
        ) {
            return
        }

        serviceScope.launch {
            try {
                // Next-word (bigram) prediction needs a real PRECEDING word still in the field. At the very
                // start of input / on an empty line there's no preceding word — lastCommittedWord can still
                // hold a stale value from an earlier line, which would wrongly resurrect a bigram that a plain
                // Space would then commit. Gate on actual non-blank text before the cursor; when there's none,
                // show the custom default row instead of a bigram. (Bug A.)
                // Use the CURRENT LINE only (text after the last newline): after Enter the field still holds the
                // previous line's text, so a whole-buffer check wouldn't see that this line is empty.
                val textBeforeForGate = outputBridge.safeGetTextBeforeCursor(200).substringAfterLast('\n')
                if (textBeforeForGate.isBlank()) {
                    withContext(Dispatchers.Main) {
                        if (state.displayBuffer.isEmpty()) {
                            state.isShowingBigramPredictions = false
                            state.pendingSuggestions = emptyList()
                            state.clearSuggestionDisplay()
                        }
                    }
                    return@launch
                }

                // Read next-word predictions under the LAYOUT language (what's being typed) — the same tag
                // recordWordUsage writes bigrams under. Using the primary language here meant typing Czech
                // surfaced stale English bigrams and never the Czech ones just recorded. (Mirror of the
                // learning/usage layout-language fix.)
                val currentLanguage = languageManager.currentLayoutLanguage.value
                val bigramCount =
                    if (state.clusterLayoutActive) SpellCheckManager.CLUSTER_BAR_POOL else host.effectiveSuggestionCount()
                val allPredictions =
                    wordFrequencyRepository.getBigramPredictions(
                        state.lastCommittedWord,
                        currentLanguage,
                        bigramCount
                    )

                val predictions = allPredictions.filter { !spellCheckManager.isWordBlacklisted(it) }

                if (predictions.isNotEmpty() && state.displayBuffer.isEmpty()) {
                    val suggestionObjects =
                        predictions.mapIndexed { index, word ->
                            SpellingSuggestion(
                                word = word,
                                confidence = 0.85 - index * 0.02,
                                ranking = index,
                                source = "bigram",
                                preserveCase = false
                            )
                        }
                    val bigramSentenceStart = host.shouldAutoCapitalize(textBeforeForGate)
                    val displayPredictions = storeAndCapitalizeSuggestions(suggestionObjects, bigramSentenceStart)
                    withContext(Dispatchers.Main) {
                        if (state.displayBuffer.isEmpty()) {
                            state.isShowingBigramPredictions = true
                            state.pendingSuggestions = displayPredictions
                            state.updateSuggestionDisplay(displayPredictions)
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "showBigramPredictions")
                )
            }
        }
    }

    suspend fun learnWordAndInvalidateCache(word: String, inputMethod: InputMethod): Boolean = try {
        val settings = textInputProcessor.getCurrentSettings()
        if (!settings.isWordLearningEnabled) {
            return true
        }

        recordWordUsage(word)

        val isInDictionary = spellCheckManager.isWordInDictionary(word)
        if (isInDictionary) {
            return true
        }

        val learnResult = wordLearningEngine.learnWord(word, inputMethod)
        if (learnResult.isSuccess) {
            spellCheckManager.invalidateWordCache(word)
            spellCheckManager.removeFromBlacklist(word)
            textInputProcessor.invalidateWord(word)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SuggestionPipeline",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("operation" to "learnWordAndInvalidateCache")
        )
        false
    }

    internal fun recordWordUsage(word: String) {
        try {
            // Record under the LAYOUT language (the language actually being typed), NOT the primary language.
            // The cluster/learned suggestion paths query per active language, so usage stored under the primary
            // (e.g. "en" while typing Czech) would never boost the Czech candidates — your typed words would
            // never climb. (currentLanguage = primary; currentLayoutLanguage = what you're typing now.)
            val currentLanguage = languageManager.currentLayoutLanguage.value
            wordFrequencyRepository.incrementFrequency(word, currentLanguage)
            // Promote a matching user-dictionary entry so words/shortcuts/readings you reuse climb to #1.
            spellCheckManager.recordUserDictionaryUse(currentLanguage, word)

            if (state.lastCommittedWord.isNotBlank()) {
                wordFrequencyRepository.recordBigram(state.lastCommittedWord, word, currentLanguage)
            }
            state.lastCommittedWord = word
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SuggestionPipeline",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "recordWordUsage")
            )
        }
    }

    suspend fun confirmAndLearnWord(checkAutoCapitalization: (String) -> Unit) {
        withContext(Dispatchers.Main) {
            val wordToLearn = state.pendingWordForLearning

            state.isActivelyEditing = true

            if (wordToLearn != null) {
                learnWordAndInvalidateCache(wordToLearn, InputMethod.TYPED)
            }

            outputBridge.beginBatchEdit()
            try {
                val lang = host.currentLayoutLanguage().split("-").first()
                if (lang == "en" && state.displayBuffer.isNotEmpty()) {
                    val corrected = EnglishPronounCorrection.capitalize(state.displayBuffer.lowercase())
                    if (corrected != null && corrected != state.displayBuffer) {
                        state.onPronounCapitalized(corrected)
                        outputBridge.setComposingText(corrected, 1)
                    }
                }
                if (state.displayBuffer.isNotEmpty()) {
                    outputBridge.updateLastCommittedWord(state.displayBuffer)
                }
                outputBridge.finishComposingText()
                outputBridge.commitText(" ")
                state.clearInternalStateOnly()
                showBigramPredictions()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "confirmAndLearnWord")
                )
                outputBridge.finishComposingText()
                outputBridge.commitText(" ")
                state.clearInternalStateOnly()
            } finally {
                outputBridge.endBatchEdit()
            }

            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            checkAutoCapitalization(textBefore)
        }
    }

    suspend fun coordinateSuggestionSelection(suggestion: String, checkAutoCapitalization: (String) -> Unit) {
        withContext(Dispatchers.Main) {
            try {
                val actualCursorPos = outputBridge.safeGetCursorPosition()

                if (state.composingRegionStart != -1 && state.displayBuffer.isNotEmpty()) {
                    @Suppress("UnnecessaryParentheses")
                    val expectedCursorRange =
                        state.composingRegionStart..(state.composingRegionStart + state.displayBuffer.length)
                    if (actualCursorPos !in expectedCursorRange) {
                        outputBridge.invalidateComposingStateOnCursorJump()
                        return@withContext
                    }
                }

                state.isActivelyEditing = true

                recordWordUsage(suggestion)
                // Standalone English pronoun: a cluster candidate of "i" (or "i'm"/"i'll"/…) must commit as
                // "I". The non-cluster Space path applies this via its AutocorrectDecision branches, but a
                // cluster commit goes straight through here, so apply pronoun correction to the recased form
                // (no-op for any other word / non-English layout). (Bug D.)
                val committed = applyPronounCorrection(recaseForCommit(suggestion))

                // Japanese text has no inter-word spaces: committing a converted/selected Japanese candidate
                // (the first Space accepts the highlighted conversion, a tap accepts a tapped one) must insert
                // the surface ONLY, with no trailing " ". Every other (Latin / cluster) path still appends a
                // space so words stay separated. The trailing space is the only Japanese difference here; the
                // cursor math below accounts for it via [trailingSpaceLen]. (Japanese FIX 1.)
                val trailing = if (isJapaneseLayout) "" else " "
                val trailingSpaceLen = trailing.length

                outputBridge.beginBatchEdit()
                try {
                    outputBridge.commitText("$committed$trailing")

                    val expectedNewPosition =
                        if (state.composingRegionStart != -1) {
                            state.composingRegionStart + committed.length + trailingSpaceLen
                        } else {
                            actualCursorPos + committed.length + trailingSpaceLen
                        }
                    state.selectionStateTracker.setExpectedPositionAfterOperation(expectedNewPosition)
                    state.lastKnownCursorPosition = expectedNewPosition

                    outputBridge.coordinateStateClear()
                    showBigramPredictions()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    checkAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "coordinateSuggestionSelection")
                )
                outputBridge.coordinateStateClear()
            }
        }
    }

    /**
     * Commit a tapped custom-row entry: insert its literal text verbatim (no re-casing, no spell-learning,
     * no bigram recording — it isn't a dictionary word). Any in-progress composing region is finished first
     * so the entry appends after the typed buffer rather than replacing it. Mirrors the post-commit cleanup
     * of a normal selection so Space/tap stay consistent and the custom default row re-shows afterwards.
     */
    suspend fun coordinateCustomSuggestionSelection(entry: String, checkAutoCapitalization: (String) -> Unit) {
        withContext(Dispatchers.Main) {
            try {
                state.isActivelyEditing = true
                outputBridge.beginBatchEdit()
                try {
                    outputBridge.finishComposingText()
                    outputBridge.commitText(entry, 1)
                    state.clearInternalStateOnly()
                    showBigramPredictions()
                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    checkAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "coordinateCustomSuggestionSelection")
                )
                outputBridge.coordinateStateClear()
            }
        }
    }

    /**
     * Commit a special-token result: [prefix] + [suffix], leaving the cursor BETWEEN them for a cursor pair
     * like `(…)`. An empty [suffix] is a plain commit (a date template or a literal). The pair is committed as
     * one string, then the caret is walked back over [suffix] via [moveCursorLeft] (a real LEFT keypress) —
     * `commitText(_, 0)` is not honoured by every editor, but caret-left navigation is. Mirrors
     * [coordinateCustomSuggestionSelection]'s finish-composing → commit → state-clear flow.
     */
    suspend fun coordinateSpecialCommit(
        prefix: String,
        suffix: String,
        moveCursorLeft: () -> Unit,
        checkAutoCapitalization: (String) -> Unit
    ) {
        withContext(Dispatchers.Main) {
            try {
                state.isActivelyEditing = true
                outputBridge.beginBatchEdit()
                try {
                    outputBridge.finishComposingText()
                    outputBridge.commitText(prefix + suffix, 1)
                    state.clearInternalStateOnly()
                    showBigramPredictions()
                    checkAutoCapitalization(outputBridge.safeGetTextBeforeCursor(50))
                } finally {
                    outputBridge.endBatchEdit()
                }
                repeat(suffix.length) { moveCursorLeft() }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "coordinateSpecialCommit")
                )
                outputBridge.coordinateStateClear()
            }
        }
    }

    /** Run a special-token editor action ([performAction] = the service dispatcher) after finishing any word. */
    suspend fun coordinateSpecialAction(performAction: () -> Unit) {
        withContext(Dispatchers.Main) {
            try {
                state.isActivelyEditing = true
                outputBridge.finishComposingText()
                state.clearInternalStateOnly()
                performAction()
                showBigramPredictions()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "coordinateSpecialAction")
                )
                outputBridge.coordinateStateClear()
            }
        }
    }

    suspend fun coordinatePostCommitReplacement(
        selectedSuggestion: String,
        replacementState: PostCommitReplacementState,
        checkAutoCapitalization: (String) -> Unit
    ) {
        withContext(Dispatchers.Main) {
            try {
                val deleteLength = replacementState.committedWord.length + 1
                val textBefore = outputBridge.safeGetTextBeforeCursor(deleteLength)
                val expectedText = replacementState.committedWord + " "
                if (textBefore != expectedText) {
                    state.postCommitReplacementState = null
                    state.clearSuggestionDisplay()
                    return@withContext
                }

                state.isActivelyEditing = true

                outputBridge.beginBatchEdit()
                try {
                    outputBridge.deleteSurroundingText(deleteLength, 0)
                    outputBridge.commitText("$selectedSuggestion ", 1)

                    val isAutocorrectUndo = replacementState.committedWord != replacementState.originalWord
                    if (isAutocorrectUndo) {
                        learnWordAndInvalidateCache(selectedSuggestion, InputMethod.TYPED)
                        // Frequency under the LAYOUT language (the one being typed), consistent with recordWordUsage.
                        val currentLanguage = languageManager.currentLayoutLanguage.value
                        wordFrequencyRepository.incrementFrequency(selectedSuggestion, currentLanguage)
                        wordFrequencyRepository.incrementFrequency(selectedSuggestion, currentLanguage)
                    } else {
                        recordWordUsage(selectedSuggestion)
                    }
                    outputBridge.updateLastCommittedWord(selectedSuggestion)
                    state.postCommitReplacementState = null
                    state.pendingSuggestions = emptyList()
                    state.clearSuggestionDisplay()
                    showBigramPredictions()

                    val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                    checkAutoCapitalization(textBefore)
                } finally {
                    outputBridge.endBatchEdit()
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "coordinatePostCommitReplacement")
                )
                state.postCommitReplacementState = null
                state.clearSuggestionDisplay()
            }
        }
    }

    suspend fun coordinateWordCompletion() {
        withContext(Dispatchers.Main) {
            try {
                state.isActivelyEditing = true
                outputBridge.coordinateStateClear()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "coordinateWordCompletion")
                )
                outputBridge.coordinateStateClear()
            }
        }
    }

    private fun requestJapaneseSuggestions(hiraganaBuffer: String) {
        // A Japanese reading is being composed: its candidate list is index-navigated by Space
        // (JapaneseCandidateHandler), so the custom row must not be appended to / overwrite it here.
        state.customRowSuppressed = hiraganaBuffer.isNotEmpty()
        suggestionDebounceJob?.cancel()
        suggestionDebounceJob = serviceScope.launch {
            try {
                delay(suggestionDebounceDelay)

                // Look up the converter by the active layout language (e.g. "ja"), not the primary
                // language: when ja is a non-primary active language, currentLanguage() is the primary
                // (e.g. "en"), forLanguage() returns null, and only kana candidates would be offered.
                val conversionLanguage = host.currentLayoutLanguage()
                val rawCandidates = scriptConverterRegistry
                    .forLanguage(conversionLanguage)
                    ?.getCandidates(hiraganaBuffer, conversionLanguage)
                    ?: emptyList()
                val conversionCandidates = rawCandidates
                    .map { candidate ->
                        SpellingSuggestion(
                            word = candidate.surface,
                            confidence = candidate.frequency.toDouble(),
                            ranking = 0,
                            source = candidate.source
                        )
                    }
                    .filter { !spellCheckManager.isWordBlacklisted(it.word) }

                val dictCompletions = if (host.showSuggestions()) {
                    spellCheckManager.getSpellingSuggestionsWithConfidence(hiraganaBuffer)
                        .filter { it.source == "completion" }
                } else {
                    emptyList()
                }

                // The user dictionary's registered readings (e.g. しろいくま→白い熊) LEAD the conversion list,
                // highest-frequency first — offered as #1 from the very first kana a prefix matches.
                // (Unified Japanese: registrations now live in the user dictionary.)
                val userCandidates = spellCheckManager.japaneseUserCandidates(hiraganaBuffer)
                    .map { SpellingSuggestion(word = it, confidence = 0.0, ranking = 0, source = "learned") }
                    .filter { !spellCheckManager.isWordBlacklisted(it.word) }

                val hiraganaCandidate = SpellingSuggestion(
                    word = hiraganaBuffer,
                    confidence = -1.0,
                    ranking = 0,
                    source = "reading"
                )
                val katakanaCandidate = SpellingSuggestion(
                    word = KanaTransformUtils.toKatakana(hiraganaBuffer),
                    confidence = -2.0,
                    ranking = 0,
                    source = "katakana"
                )

                // The plain typed reading (its hiragana) is the always-available base candidate: it must NEVER be
                // pushed out of the row by conversions/learned entries, otherwise a mis-learned surface (BUG A)
                // could leave the row with no way back to the literal reading. Reserve it a guaranteed slot, fill
                // the remaining slots with conversions/completions, then append it (and the katakana form when it
                // still fits). distinctBy collapses any conversion that happens to equal the kana. (BUG A.)
                val cap = host.effectiveSuggestionCount()
                val conversions = (userCandidates + conversionCandidates + dictCompletions)
                    .distinctBy { it.word }
                    .filter { it.word != hiraganaCandidate.word }
                val reservedForBase = if (cap > 0) 1 else 0
                val combined = (
                    conversions.take((cap - reservedForBase).coerceAtLeast(0)) +
                        hiraganaCandidate +
                        katakanaCandidate
                    )
                    .distinctBy { it.word }
                    .take(cap)

                // Trailing inline-registration affordance: a special "＋登録" candidate at the end of the row.
                // Tapping it opens the reading→surface registration dialog (handled in the service) rather than
                // committing — the easy way to teach an unknown reading like しろいくま → 白い熊. Marked with a
                // dedicated source so the commit path can recognise and intercept it. Skipped if the label
                // would collide with a real candidate. (Japanese FIX 2.)
                val registerLabel = host.japaneseRegisterLabel()
                val registerCandidate =
                    if (hiraganaBuffer.isNotEmpty() && combined.none { it.word == registerLabel }) {
                        SpellingSuggestion(
                            word = registerLabel,
                            confidence = -100.0,
                            ranking = 0,
                            source = JA_REGISTER_SOURCE
                        )
                    } else {
                        null
                    }
                val withRegister = combined + listOfNotNull(registerCandidate)

                withContext(Dispatchers.Main) {
                    if (withRegister.isNotEmpty()) {
                        state.pendingSuggestions = withRegister.map { it.word }
                        state.currentRawSuggestions = withRegister
                        state.updateSuggestionDisplay(withRegister.map { it.word })
                    } else {
                        state.pendingSuggestions = emptyList()
                        state.clearSuggestionDisplay()
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SuggestionPipeline",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "requestJapaneseSuggestions")
                )
            }
        }
    }

    fun cancelDebounceJob() {
        suggestionDebounceJob?.cancel()
    }

    /**
     * True if [suggestion] is the trailing Japanese registration affordance ("＋登録") rather than a real
     * conversion candidate — matched by its source so the service can intercept the tap and open the
     * registration dialog instead of committing it. (Japanese FIX 2.)
     */
    fun isJapaneseRegisterAffordance(suggestion: String): Boolean =
        isJapaneseLayout &&
            state.currentRawSuggestions.any { it.word == suggestion && it.source == JA_REGISTER_SOURCE }

    companion object {
        /** Source tag marking the trailing "＋登録" registration affordance in the Japanese candidate row. */
        const val JA_REGISTER_SOURCE = "ja_register"

        private const val SUGGESTION_DEBOUNCE_MS = 10L
        private val CASELESS_LANGUAGES = setOf("ar", "fa", "ja")
    }
}
