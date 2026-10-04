package com.urik.keyboard.ui.keyboard.components

/** How a word on the dictation review strip is marked. */
enum class VoiceReviewMark { LOW_CONFIDENCE, UNKNOWN_WORD, KNOWN_MISRECOGNITION, NONE }

/** One word on the review strip: [fieldPosition] is where it sits in the text field (a tap goes there). */
data class VoiceReviewWord(
    val label: String,
    val mark: VoiceReviewMark,
    val fieldPosition: Int,
    val alternative: String? = null
)

/** The review strip's content: every dictated word still standing, in order. */
data class VoiceReview(val words: List<VoiceReviewWord>)

sealed interface VoiceReviewAction {
    /** ✓ — accept: the dictated words left standing are right; the strip closes. */
    data object Accept : VoiceReviewAction

    /** ✕ — close the review without learning from this dictation. */
    data object Discard : VoiceReviewAction

    data class Word(val fieldPosition: Int) : VoiceReviewAction
}

/**
 * The default colours of the four review marks — what a keyboard uses until it is given its own.
 *
 * Overridable per keyboard in the kxkb UI page (Rows → Suggestion bar) as style look knobs, so 白い熊 can
 * pick marks that read well to their own eyes rather than to ours (白い熊, 2026-10-04).
 */
object VoiceReviewColors {
    /** The recogniser's own confidence was below the threshold. */
    const val UNSURE = 0xFFFF6E6E.toInt()

    /** Not a known word of the dictation language. */
    const val UNKNOWN = 0xFFFFB74D.toInt()

    /** An earlier correction of yours replaced what was heard. */
    const val REPLACED = 0xFF4FC3F7.toInt()

    /** You corrected it here (the walk-capture page only — in a field the text itself shows it). */
    const val CORRECTED = 0xFF99DD66.toInt()
}
