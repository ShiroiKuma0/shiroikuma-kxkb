package com.urik.keyboard.service.voice

/**
 * The languages dictation and the walk-capture review speak of, and what they are called.
 *
 * Two places name them — the mic key's long-press flash and the capture page's language pill — and a second
 * copy of the mapping would drift from the first within a release.
 */
object VoiceLanguages {
    /**
     * The pair a captured sentence may be decoded as, in the order the pill shows them.
     *
     * Not every language Whisper knows: this code is also the `language` field kxkb hands 言語島 in the
     * sister-app contract (§2), which decides the translation's source language and its voice, so the set
     * is the one both sides agreed on. The dictation flip ([VoiceInputController.resolveLanguage]) swaps
     * between the same two.
     */
    val CAPTURE_PAIR = listOf("en", "cs")

    /** [code]'s own name for itself — a language is best recognised in its own spelling. */
    fun displayName(code: String): String = when (base(code)) {
        "en" -> "English"
        "cs" -> "čeština"
        "ru" -> "русский"
        "ja" -> "日本語"
        else -> code
    }

    /** The other half of the capture pair — what tapping the pill switches to. */
    fun otherOfPair(code: String): String = if (base(code) == CAPTURE_PAIR[1]) CAPTURE_PAIR[0] else CAPTURE_PAIR[1]

    /**
     * [code] as a capture language, or `en`. The field it lands in drives a decode and, through the
     * contract, a translation direction and a voice — so an unknown code is normalised where it enters
     * rather than carried to the far side of the wire, and the review page can then set it to the other.
     */
    fun captureOrDefault(code: String): String = base(code).takeIf { it in CAPTURE_PAIR } ?: CAPTURE_PAIR[0]

    private fun base(code: String): String = code.substringBefore("-").lowercase()
}
