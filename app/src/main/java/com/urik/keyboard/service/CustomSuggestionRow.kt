package com.urik.keyboard.service

/**
 * Custom suggestion row — a user-defined set of short words/phrases/symbols shown in the candidate bar.
 *
 * Re-derived in spirit from the design-reference fork's Multiling-style "topBar" (static candidates shown
 * when nothing is typed). Here it serves two roles:
 *   1. Default row when nothing is predicted yet (empty input buffer) — instead of a blank bar.
 *   2. Appended to the END of the prediction candidates when predictions are already being offered.
 *
 * Each entry is a literal string committed verbatim on tap (no spell-learning, no bigram recording).
 * Entries are newline-separated in the stored setting; blanks are dropped, order is preserved, and the
 * merged list is deduplicated and capped so the candidate pool never grows unbounded.
 */
object CustomSuggestionRow {
    /** Upper bound on the merged candidate pool (matches the cluster bar pool size). */
    const val MAX_MERGED = 16

    /** Parse the raw newline-separated setting into a clean, ordered, de-duplicated entry list. */
    fun parse(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Merge custom entries into the live prediction list:
     *   - predictions empty  -> the custom entries become the row (the empty-buffer default).
     *   - predictions present -> custom entries are appended after them.
     * In both cases entries already present in [predictions] are dropped (a custom entry never hijacks or
     * duplicates a real prediction) and the result is capped at [MAX_MERGED].
     */
    fun merge(predictions: List<String>, custom: List<String>): List<String> {
        if (custom.isEmpty()) return predictions
        val seen = predictions.toMutableSet()
        val tail = custom.filter { seen.add(it) }
        if (tail.isEmpty()) return predictions
        return (predictions + tail).take(MAX_MERGED)
    }
}
