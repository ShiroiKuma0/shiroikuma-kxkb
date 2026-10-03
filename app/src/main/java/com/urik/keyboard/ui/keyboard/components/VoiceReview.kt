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
