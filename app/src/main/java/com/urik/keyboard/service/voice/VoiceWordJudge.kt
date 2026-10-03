package com.urik.keyboard.service.voice

/** Why a dictated word is marked for review. */
enum class SuspectReason {
    /** The recogniser itself was unsure: the word's lowest token probability is under the threshold. */
    LOW_CONFIDENCE,

    /** Not a known word of the dictation language (dictionary, learned words, user dictionary). */
    UNKNOWN_WORD,

    /** You corrected this exact recognition before — the correction is offered as the alternative. */
    KNOWN_MISRECOGNITION
}

/** What the voice corpus already knows about a recognised word (case-folded) in one language. */
sealed interface VoiceKnowledge {
    /** You confirmed it (left it standing, or typed it as a correction): never suspect again. */
    data object Confirmed : VoiceKnowledge

    /** The newest evidence is a correction of this recognition to [correction]. */
    data class Misrecognized(val correction: String) : VoiceKnowledge
}

/** The judge's verdict for one word: null [reason] = not suspect. */
data class VoiceJudgement(val reason: SuspectReason?, val alternative: String? = null) {
    val isSuspect: Boolean get() = reason != null
}

/**
 * Decides which dictated words get marked. A word is suspect when the corpus knows it as a previous
 * misrecognition, when it is not a known word of the dictation language, or when the recogniser's own
 * confidence in it is low — unless the corpus knows it as confirmed, which always wins: a word you let
 * stand once is never marked again.
 *
 * Numbers and pure punctuation are never marked, and neither is Japanese (no word boundaries — one
 * "word" would be the whole sentence).
 */
class VoiceWordJudge(private val threshold: Float = DEFAULT_CONFIDENCE_THRESHOLD) {
    suspend fun judge(
        words: List<String>,
        confidences: List<Float?>,
        language: String,
        knowledge: (String) -> VoiceKnowledge?,
        isKnownWord: suspend (String) -> Boolean
    ): List<VoiceJudgement> {
        if (language.substringBefore("-") == "ja") return words.map { NOT_SUSPECT }
        return words.mapIndexed { index, word ->
            val core = coreOf(word)
            if (core.isEmpty() || core.any { it.isDigit() }) return@mapIndexed NOT_SUSPECT
            when (val known = knowledge(core.lowercase())) {
                VoiceKnowledge.Confirmed -> return@mapIndexed NOT_SUSPECT
                is VoiceKnowledge.Misrecognized ->
                    return@mapIndexed VoiceJudgement(SuspectReason.KNOWN_MISRECOGNITION, known.correction)
                null -> Unit
            }
            if (!isKnown(core, isKnownWord)) return@mapIndexed VoiceJudgement(SuspectReason.UNKNOWN_WORD)
            val confidence = confidences.getOrNull(index)
            if (confidence != null && confidence < threshold) {
                return@mapIndexed VoiceJudgement(SuspectReason.LOW_CONFIDENCE)
            }
            NOT_SUSPECT
        }
    }

    /** A hyphenated compound counts as known when it is, or when every part is. */
    private suspend fun isKnown(core: String, isKnownWord: suspend (String) -> Boolean): Boolean {
        if (isKnownWord(core)) return true
        if (!core.contains('-')) return false
        val parts = core.split('-').filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.all { isKnownWord(it) }
    }

    companion object {
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.4f
        private val NOT_SUSPECT = VoiceJudgement(null)

        /**
         * The word without its leading/trailing punctuation ("„Ahoj," → "Ahoj"); inner apostrophes and
         * hyphens stay ("don't", "česko-slovenský").
         */
        fun coreOf(word: String): String {
            var start = 0
            var end = word.length
            while (start < end && !word[start].isLetterOrDigit()) start++
            while (end > start && !word[end - 1].isLetterOrDigit()) end--
            return word.substring(start, end)
        }

        /** Start offset of [coreOf] inside [word] (0 when the word has no core). */
        fun coreStartIn(word: String): Int {
            var start = 0
            while (start < word.length && !word[start].isLetterOrDigit()) start++
            return if (start == word.length) 0 else start
        }
    }
}
