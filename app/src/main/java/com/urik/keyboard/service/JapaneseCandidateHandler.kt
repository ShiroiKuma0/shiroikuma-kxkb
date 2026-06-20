package com.urik.keyboard.service

class JapaneseCandidateHandler(
    private val inputState: InputStateManager,
    private val outputBridge: OutputBridge,
    private val onCommit: (String) -> Unit,
    // The trailing "＋登録" registration affordance is part of the displayed candidate row but is an action,
    // not a conversion candidate: Space-cycling must skip it and it must never be committed as text. The
    // service supplies this predicate (matched by the candidate's source). (Japanese FIX 2.)
    private val isRegisterAffordance: (String) -> Boolean = { false }
) {
    private var currentCandidateIndex = 0

    fun onNextCandidate() {
        val candidates = inputState.pendingSuggestions
        if (candidates.isEmpty()) return
        // Advance to the next candidate, skipping the registration affordance so Space only ever lands on a
        // real conversion candidate (wrapping back to the start). If every entry is the affordance there's
        // nothing to cycle to, so bail.
        var next = currentCandidateIndex
        for (step in 1..candidates.size) {
            val idx = (currentCandidateIndex + step) % candidates.size
            if (!isRegisterAffordance(candidates[idx])) {
                next = idx
                break
            }
        }
        if (next == currentCandidateIndex) return
        currentCandidateIndex = next
        outputBridge.setComposingText(candidates[currentCandidateIndex], 1)
    }

    fun onCommitCandidate() {
        val candidates = inputState.pendingSuggestions
        if (candidates.isEmpty()) return
        val toCommit = candidates[currentCandidateIndex]
        // Never auto-commit the registration affordance (e.g. via Enter while it happens to be highlighted) —
        // it isn't text. Reset and bail; a deliberate tap on it is routed to the dialog by the service.
        if (isRegisterAffordance(toCommit)) {
            reset()
            return
        }
        reset()
        onCommit(toCommit)
    }

    fun reset() {
        currentCandidateIndex = 0
    }
}
