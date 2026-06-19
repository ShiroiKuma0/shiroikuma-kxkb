package com.urik.keyboard.service

import com.urik.keyboard.utils.SelectionStateTracker

enum class SpellConfirmationState {
    NORMAL,
    AWAITING_CONFIRMATION
}

data class PostCommitReplacementState(val originalWord: String, val committedWord: String)

data class LastAutocorrection(val originalTypedWord: String, val correctedWord: String)

interface ViewCallback {
    fun clearSuggestions()

    fun updateSuggestions(suggestions: List<String>)

    fun showDegradedIndicator(degraded: Boolean)

    /** Move (or clear, with -1) the cluster-candidate highlight without rebuilding the bar. */
    fun setSelectedSuggestion(index: Int)
}

class InputStateManager(
    private val viewCallback: ViewCallback,
    private val onShiftStateChanged: (Boolean) -> Unit,
    private val isCapsLockOn: () -> Boolean,
    private val cancelDebounceJob: () -> Unit
) {
    @Volatile
    var displayBuffer = ""
        internal set

    @Volatile
    var wordState = WordState()
        internal set

    @Volatile
    var composingRegionStart: Int = -1
        internal set

    private var processingSequence = 0L
    private val processingLock = Any()

    @Volatile
    var isActivelyEditing = false
        internal set

    @Volatile
    var isCurrentWordAtSentenceStart = false
        internal set

    @Volatile
    var isCurrentWordManualShifted = false
        internal set

    @Volatile
    var pendingSuggestions: List<String> = emptyList()
        internal set(value) {
            field = value
            // A fresh suggestion set always re-selects the best (top) candidate for Space-to-commit.
            selectedCandidate = 0
        }

    /**
     * Index of the highlighted candidate in [pendingSuggestions] — the one Space commits in cluster typing.
     * Resets to 0 (the best candidate) on every new suggestion set; Tab advances it. See [clusterLayoutActive].
     *
     * A value of -1 means NO candidate is selected: that's the state of the custom DEFAULT row (nothing
     * predicted), where a plain Space must enter a literal space, not commit a custom entry. Tab then sets a
     * real index (>= 0), at which point Space commits THAT entry. See [hasExplicitSelection]. (Bug B.)
     */
    @Volatile
    var selectedCandidate: Int = 0
        internal set

    /** True once Tab (or any explicit pick) has chosen a candidate — distinguishes the custom default row. */
    val hasExplicitSelection: Boolean
        get() = selectedCandidate >= 0

    /** True while a cluster-key layout is active, gating Space-commits-candidate / Tab-advances behaviour. */
    @Volatile
    var clusterLayoutActive: Boolean = false
        internal set

    @Volatile
    var currentRawSuggestions: List<SpellingSuggestion> = emptyList()
        internal set

    @Volatile
    var spellConfirmationState = SpellConfirmationState.NORMAL
        internal set

    @Volatile
    var pendingWordForLearning: String? = null
        internal set

    @Volatile
    var isShowingBigramPredictions: Boolean = false
        internal set

    @Volatile
    var composingReassertionCount: Int = 0
        internal set

    @Volatile
    var lastKnownCursorPosition: Int = -1
        internal set

    @Volatile
    var lastSpaceTime: Long = 0
        internal set

    @Volatile
    var lastShiftTime: Long = 0
        internal set

    @Volatile
    var lastCommittedWord: String = ""
        internal set

    @Volatile
    var isSecureField: Boolean = false
        internal set

    @Volatile
    var isDirectCommitField: Boolean = false
        internal set

    @Volatile
    var isRawKeyEventField: Boolean = false
        internal set

    @Volatile
    var isTerminalField: Boolean = false
        internal set

    @Volatile
    var isUrlOrEmailField: Boolean = false
        internal set

    @Volatile
    var isSuggestionsDisabled: Boolean = false
        internal set

    @Volatile
    var currentInputAction: com.urik.keyboard.model.KeyboardKey.ActionType =
        com.urik.keyboard.model.KeyboardKey.ActionType.ENTER
        internal set

    @Volatile
    var isAcceleratedDeletion = false
        internal set

    @Volatile
    var postCommitReplacementState: PostCommitReplacementState? = null
        internal set

    @Volatile
    var lastAutocorrection: LastAutocorrection? = null
        internal set

    val selectionStateTracker = SelectionStateTracker()

    /**
     * User-defined custom suggestions (parsed, ordered, de-duplicated). Refreshed from settings.
     * Shown as the default row when nothing is predicted and appended after live predictions otherwise.
     */
    @Volatile
    var customSuggestions: List<String> = emptyList()
        internal set

    /** Fast membership check for the commit path (a custom-row tap must not spell-learn). */
    @Volatile
    private var customSuggestionSet: Set<String> = emptySet()

    /** The real (non-custom) predictions currently shown, so a tap on one of those still learns/records. */
    @Volatile
    private var lastRealPredictions: Set<String> = emptySet()

    fun setCustomSuggestions(entries: List<String>) {
        customSuggestions = entries
        customSuggestionSet = entries.toSet()
    }

    /**
     * True if [word] is a custom-row entry that is NOT one of the live predictions currently shown (so a tap
     * commits it literally, with no spell-learn / bigram recording). A custom entry that coincides with a
     * real prediction is left as a normal prediction so its word stats are still recorded.
     */
    fun isCustomSuggestion(word: String): Boolean =
        word in customSuggestionSet && word !in lastRealPredictions

    /**
     * Set while a Japanese reading is being composed: the candidate list is index-navigated by Space
     * (JapaneseCandidateHandler), so the custom row must not be appended to / shown during composition.
     */
    @Volatile
    var customRowSuppressed: Boolean = false
        internal set

    val requiresDirectCommit: Boolean
        get() = isSecureField || isDirectCommitField

    data class ExpectedTypingOus(val composingStart: Int, val composingEnd: Int, val cursorPosition: Int)

    private val pendingTypingOus = ArrayDeque<ExpectedTypingOus>()

    fun enqueueTypingOus(ous: ExpectedTypingOus) {
        pendingTypingOus.addLast(ous)
    }

    fun tryConsumeTypingOus(cursor: Int, candStart: Int, candEnd: Int): Boolean {
        val expected = pendingTypingOus.firstOrNull() ?: return false
        return if (cursor == expected.cursorPosition &&
            candStart == expected.composingStart &&
            candEnd == expected.composingEnd
        ) {
            pendingTypingOus.removeFirst()
            true
        } else {
            pendingTypingOus.clear()
            false
        }
    }

    fun clearPendingTypingOus() {
        pendingTypingOus.clear()
    }

    fun isComposingCursorAtExpectedEnd(): Boolean = composingRegionStart != -1 &&
        displayBuffer.isNotEmpty() &&
        !isActivelyEditing &&
        lastKnownCursorPosition == composingRegionStart + displayBuffer.length

    fun isKnownCursorTrustworthy(): Boolean = lastKnownCursorPosition != -1 &&
        !isActivelyEditing &&
        pendingTypingOus.isEmpty()

    fun getSequenceAndBuffer(): Pair<Long, String> = synchronized(processingLock) {
        ++processingSequence to displayBuffer
    }

    fun isSequenceCurrent(sequence: Long, bufferSnapshot: String): Boolean = synchronized(processingLock) {
        sequence == processingSequence && displayBuffer == bufferSnapshot
    }

    fun updateDisplayBuffer(newBuffer: String) {
        displayBuffer = newBuffer
    }

    fun onComposingReasserted() {
        composingReassertionCount++
        isActivelyEditing = true
    }

    fun onRecompositionSucceeded(word: String, wordStart: Int) {
        displayBuffer = word
        composingRegionStart = wordStart
    }

    fun onSwipeCommitted() {
        displayBuffer = ""
    }

    fun onPronounCapitalized(capitalizedWord: String) {
        displayBuffer = capitalizedWord
    }

    /**
     * Merge the custom row into a (possibly empty) prediction list for display: nothing-predicted shows
     * the custom row as the default; predictions get the custom entries appended. Suppressed while a
     * Japanese reading is being composed, and skipped when there's no custom row configured. Updates
     * [pendingSuggestions] to the merged list so Space / Tab / tap all operate on what the bar shows.
     */
    private fun mergeCustomRow(predictions: List<String>): List<String> {
        if (customSuggestions.isEmpty() ||
            customRowSuppressed ||
            isSuggestionsDisabled ||
            requiresDirectCommit
        ) {
            return predictions
        }
        return CustomSuggestionRow.merge(predictions, customSuggestions)
    }

    /**
     * Append the custom row to a (possibly large) candidate list for the expandable "more candidates" pane:
     * same gating as the bar (skipped when no custom row, suppressed, suggestions off, or direct-commit), but
     * WITHOUT the bar's tight MAX_MERGED cap — the pane shows every prediction plus every custom entry not
     * already present. Empty predictions return the custom row alone (the empty-buffer default). (Bug C.)
     */
    fun withCustomRow(predictions: List<String>): List<String> {
        if (customSuggestions.isEmpty() ||
            customRowSuppressed ||
            isSuggestionsDisabled ||
            requiresDirectCommit
        ) {
            return predictions
        }
        val seen = predictions.toMutableSet()
        val tail = customSuggestions.filter { seen.add(it) }
        return if (tail.isEmpty()) predictions else predictions + tail
    }

    /** Show the custom row as the default row when the bar would otherwise be cleared blank. */
    private fun showCustomRowOrClear() {
        // No real predictions in this state — the whole row (if any) is the custom default.
        lastRealPredictions = emptySet()
        val merged = mergeCustomRow(emptyList())
        if (merged.isEmpty()) {
            viewCallback.clearSuggestions()
        } else {
            pendingSuggestions = merged
            viewCallback.updateSuggestions(merged)
            // The custom DEFAULT row starts with NO candidate selected (a plain Space = literal space). Tab
            // then explicitly selects one; only then does Space commit it. (Bug B.)
            selectedCandidate = -1
            viewCallback.setSelectedSuggestion(-1)
        }
    }

    fun clearSuggestionDisplay() {
        // An empty prediction list still shows the custom row as the default when one is configured.
        showCustomRowOrClear()
    }

    fun updateSuggestionDisplay(suggestions: List<String>) {
        // [suggestions] are the real predictions; the custom row (if any) is appended after them.
        lastRealPredictions = suggestions.toSet()
        val merged = mergeCustomRow(suggestions)
        if (merged !== suggestions) {
            pendingSuggestions = merged
        }
        viewCallback.updateSuggestions(merged)
    }

    fun showDegradedIndicator(degraded: Boolean) {
        viewCallback.showDegradedIndicator(degraded)
    }

    fun clearInternalStateOnly() {
        synchronized(processingLock) {
            processingSequence++
        }

        cancelDebounceJob()
        clearPendingTypingOus()

        isActivelyEditing = true
        isCurrentWordAtSentenceStart = false
        isCurrentWordManualShifted = false
        customRowSuppressed = false
        displayBuffer = ""
        wordState = WordState()
        pendingSuggestions = emptyList()
        currentRawSuggestions = emptyList()
        isShowingBigramPredictions = false
        spellConfirmationState = SpellConfirmationState.NORMAL
        pendingWordForLearning = null
        postCommitReplacementState = null
        lastAutocorrection = null
        showCustomRowOrClear()
        composingRegionStart = -1
        composingReassertionCount = 0
        lastKnownCursorPosition = -1

        if (!isCapsLockOn()) {
            onShiftStateChanged(false)
        }
    }

    /** Removes [suggestion] from pending, raw, and word-state suggestion lists. Returns the updated pending list. */
    fun removeSuggestionFromState(suggestion: String): List<String> {
        val updatedPending = pendingSuggestions.filter { it != suggestion }
        pendingSuggestions = updatedPending
        currentRawSuggestions = currentRawSuggestions.filter {
            !it.word.equals(suggestion, ignoreCase = true)
        }
        wordState = wordState.copy(
            suggestions = wordState.suggestions.filter {
                !it.word.equals(suggestion, ignoreCase = true)
            }
        )
        return updatedPending
    }

    fun clearBigramPredictions() {
        if (isShowingBigramPredictions) {
            isShowingBigramPredictions = false
            pendingSuggestions = emptyList()
            showCustomRowOrClear()
        }
    }

    fun clearSpellConfirmationFields() {
        spellConfirmationState = SpellConfirmationState.NORMAL
        pendingWordForLearning = null
    }

    fun invalidateComposingState() {
        synchronized(processingLock) {
            processingSequence++
        }

        cancelDebounceJob()
        clearPendingTypingOus()

        isActivelyEditing = true

        customRowSuppressed = false
        displayBuffer = ""
        wordState = WordState()
        pendingSuggestions = emptyList()
        isShowingBigramPredictions = false
        spellConfirmationState = SpellConfirmationState.NORMAL
        pendingWordForLearning = null
        postCommitReplacementState = null
        lastAutocorrection = null
        showCustomRowOrClear()
        composingRegionStart = -1
        lastKnownCursorPosition = -1
        selectionStateTracker.clearExpectedPosition()
    }
}
