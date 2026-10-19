package com.urik.keyboard.service.voice

/**
 * One decoded dictation chunk as the engine delivered it.
 *
 * [text] is the engine's cleaned transcription (trimmed, first letter capitalised, timestamps stripped).
 * [rawWords] / [rawConfidences] are the words of the UNCLEANED decode with each word's lowest token
 * probability (null when the engine could not compute them); [wordConfidences] maps them onto the
 * whitespace words of [text]. [samples] is the audio the chunk was decoded from (16 kHz mono float,
 * peak-normalised), kept so a correction can be stored together with the voice that produced it.
 */
class VoiceTranscript(
    val text: String,
    val languageCode: String?,
    val rawWords: List<String>?,
    val rawConfidences: FloatArray?,
    val samples: FloatArray?
) {
    /** One confidence per whitespace word of [text] (null where no raw word could be matched). */
    fun wordConfidences(): List<Float?> = alignConfidences(splitWords(text), rawWords, rawConfidences)

    companion object {
        /** Whitespace-separated words, punctuation still attached ("Ahoj," stays one word). */
        fun splitWords(text: String): List<String> = text.split(WHITESPACE).filter { it.isNotEmpty() }

        private val WHITESPACE = Regex("\\s+")

        /**
         * Match the cleaned words to the raw decode's words by a longest-common-subsequence over the
         * case-folded word core (punctuation stripped — the engine's clean-up capitalises and drops "...",
         * and spoken punctuation moves marks between words), so a dropped or merged word costs only its
         * own confidence, never the alignment of the rest.
         */
        fun alignConfidences(words: List<String>, raw: List<String>?, confidences: FloatArray?): List<Float?> {
            if (raw == null || confidences == null || raw.size != confidences.size) return words.map { null }
            return alignByCore(words, raw).map { j -> j?.let { confidences[it] } }
        }

        /**
         * The matching itself: for each word of [words], the index in [against] it is the same word as, or
         * null where it has none. "The same word" is the case-folded core, so punctuation moved or a
         * capital gained still matches — which is what lets one hand edit of a sentence cost only the
         * words it really changed ([com.urik.keyboard.service.voice.VoiceSettledWords] carries the word
         * marks across the same way these confidences travel).
         */
        fun alignByCore(words: List<String>, against: List<String>): List<Int?> {
            fun key(w: String) = VoiceWordJudge.coreOf(w).lowercase()
            val a = words.map(::key)
            val b = against.map(::key)
            val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
            for (i in a.indices.reversed()) {
                for (j in b.indices.reversed()) {
                    lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
            val result = MutableList<Int?>(words.size) { null }
            var i = 0
            var j = 0
            while (i < a.size && j < b.size) {
                when {
                    a[i] == b[j] -> {
                        result[i] = j
                        i++
                        j++
                    }
                    lcs[i + 1][j] >= lcs[i][j + 1] -> i++
                    else -> j++
                }
            }
            return result
        }
    }
}
