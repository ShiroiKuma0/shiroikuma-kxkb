package com.urik.keyboard.settings.voice

/**
 * The order of the control pills under a walk row — ONE order, shown under every line (白い熊, 2026-10-20).
 *
 * Each row offers only the controls that apply to it: a sentence has Transcribe, Edit, Copy and Share, a
 * long recording has Cut into sentences, and a row whose recording has already been kept as an OGG has no
 * "Save as OGG" at all. So the stored order is the order of EVERY control, and a row renders the subset it
 * has in that one sequence — which is what makes dragging a pill in one row change where it sits in all of
 * them.
 *
 * The ids are stored, so they are permanent: a renamed id would read as an unknown one and the control
 * would fall back to its default place. "Send to 白い熊 自由作業盤" is deliberately not here — it keeps the
 * line of its own it was given when the row's controls were first made to wrap, being the one action that
 * cannot be taken back.
 */
object VoiceCaptureControls {
    const val LANGUAGE = "language"
    const val PLAY = "play"
    const val TRANSCRIBE = "transcribe"
    const val EDIT = "edit"
    const val COPY = "copy"
    const val SHARE = "share"
    const val DELETE = "delete"
    const val SAVE_OGG = "ogg"
    const val SPLIT = "split"

    /**
     * The order a fresh install shows. Keeping the recording comes AFTER Delete (白い熊, 2026-10-20) — it is
     * the end of what can be done with a row, not one of the things done to its text — with Cut into
     * sentences, the other thing a recording can become, beside it.
     */
    val DEFAULT: List<String> = listOf(LANGUAGE, PLAY, TRANSCRIBE, EDIT, COPY, SHARE, DELETE, SAVE_OGG, SPLIT)

    private const val SEPARATOR = ","

    /**
     * The stored order, made whole: unknown ids are dropped (a control this build no longer has) and known
     * ids the stored order does not mention are appended in [DEFAULT] order (a control a LATER build added —
     * it must appear somewhere, and the end is the one place that cannot be mistaken for a considered
     * position). A blank or absent value is simply [DEFAULT].
     */
    fun parse(stored: String?): List<String> {
        if (stored.isNullOrBlank()) return DEFAULT
        val known = DEFAULT.toSet()
        val seen = LinkedHashSet<String>()
        for (id in stored.split(SEPARATOR)) {
            val trimmed = id.trim()
            if (trimmed in known) seen.add(trimmed)
        }
        if (seen.isEmpty()) return DEFAULT
        return seen.toList() + DEFAULT.filterNot { it in seen }
    }

    fun encode(order: List<String>): String = order.joinToString(SEPARATOR)

    /**
     * [dragged] moved, and nothing else: [shown] is the row's own controls in the order the drop left them,
     * so where [dragged] belongs globally is read off its new neighbour there — immediately before the shown
     * control it now precedes, or after the last of them when it was dropped at the end.
     *
     * Only the dragged id changes place. Rewriting the whole sequence from [shown] instead would drag the
     * controls that row does NOT offer along with it: a long row showing four pills would decide where Copy
     * and Share sit for every sentence row, from a line they never appear on.
     */
    fun moveWithin(order: List<String>, dragged: String, shown: List<String>): List<String> {
        val at = shown.indexOf(dragged)
        if (at < 0 || dragged !in order) return order
        val rest = order.filterNot { it == dragged }
        val anchor = shown.drop(at + 1).firstOrNull { it in rest }
        val target = if (anchor != null) {
            rest.indexOf(anchor)
        } else {
            // Last among the ones this row shows — which is not the end of the whole order: the controls
            // this row has no pill for keep their places behind it.
            shown.lastOrNull { it != dragged && it in rest }
                ?.let { rest.indexOf(it) + 1 }
                ?: 0
        }
        return rest.toMutableList().apply { add(target, dragged) }
    }
}
