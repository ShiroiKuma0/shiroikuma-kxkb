package com.urik.keyboard.service.voice

/**
 * What YOU have settled about the individual words of a walk-capture sentence: the word an earlier
 * correction of yours replaced when it was decoded (blue, ↺ puts the recognition back), and the word you
 * corrected here by hand (green).
 *
 * The other two marks a reviewed word can carry are DERIVED and need no memory: the recogniser's own
 * doubt rides on the stored confidences, re-aligned onto whatever the sentence reads as now, and "not a
 * word of this language" is a question the dictionary answers again every time. These two cannot be
 * derived — they are events — and the review page rebuilds every row from the store after each save and
 * each fold of the phone, so unremembered they are gone the moment anything about the sentence is saved:
 * editing one comma cost the whole sentence its blue and green.
 *
 * So they travel with the sentence, word by word, by the same notion of "the same word" the confidences
 * use ([VoiceTranscript.alignByCore] — the case-folded core, so punctuation moved or a capital gained is
 * not a change): a word you did not touch keeps what it was marked as, and only a word you really
 * changed loses it.
 */
object VoiceSettledWords {
    enum class State { REPLACED, CORRECTED }

    /** [heard] is what the recogniser produced at [index] — the word the correction box's ↺ puts back. */
    data class Settled(val index: Int, val heard: String, val state: State)

    /**
     * The settled words of [entry] as it now reads, indexed by the words of
     * [VoiceSessionLedger.Entry.currentText] — which is what the page saves. Words merged away by a
     * multi-word correction are gone from both, so the indices line up.
     *
     * Typed-over corrections are not read here: they are found by the ledger's [VoiceSessionLedger.verify]
     * against a live text field, and the review page has no field to verify against.
     */
    fun of(entry: VoiceSessionLedger.Entry): List<Settled> {
        val out = mutableListOf<Settled>()
        var index = 0
        for (word in entry.words) {
            if (!word.intact) continue
            val state = when {
                word.corrected -> State.CORRECTED
                word.autoReplaced -> State.REPLACED
                else -> null
            }
            if (state != null && word.recognized.isNotEmpty()) out.add(Settled(index, word.recognized, state))
            index++
        }
        return out
    }

    /**
     * [settled], stated against the words of [before], as it stands against the words of [after] — one
     * hand edit of the whole sentence. A word that is still the same word keeps its mark at its new
     * index; a word that changed, or is gone, keeps nothing.
     */
    fun carry(before: String, after: String, settled: List<Settled>): List<Settled> {
        if (settled.isEmpty()) return emptyList()
        val was = settled.associateBy { it.index }
        val matched = VoiceTranscript.alignByCore(
            VoiceTranscript.splitWords(after),
            VoiceTranscript.splitWords(before)
        )
        return matched.indices.mapNotNull { index ->
            val from = matched[index] ?: return@mapNotNull null
            val previous = was[from] ?: return@mapNotNull null
            Settled(index, previous.heard, previous.state)
        }
    }

    /** What the recogniser produced at each settled word, for [VoiceSessionLedger.add]'s `originals`. */
    fun originals(settled: List<Settled>): Map<Int, String> = settled.associate { it.index to it.heard }

    /** The words to put back as corrected by hand, for [VoiceSessionLedger.add]'s `correctedAt`. */
    fun correctedAt(settled: List<Settled>): Set<Int> =
        settled.filter { it.state == State.CORRECTED }.mapTo(HashSet()) { it.index }
}
