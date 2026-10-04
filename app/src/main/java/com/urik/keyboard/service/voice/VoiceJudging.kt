package com.urik.keyboard.service.voice

import com.urik.keyboard.data.UserDictionaryRepository
import com.urik.keyboard.data.VoiceCorpusRepository
import com.urik.keyboard.data.database.UserDictionaryKind
import com.urik.keyboard.service.SpellCheckManager

/**
 * Judging one decoded sentence: which of its words are marked for review, and which recognitions an
 * earlier correction of yours replaces outright.
 *
 * Deliberately field-independent. Two reviews run these rules — the dictation review at a text field
 * ([VoiceReviewCoordinator]) and the walk-capture review page, which has no field, no input connection
 * and no keyboard — and a second copy of the rules would drift from the first within a release.
 */
object VoiceJudging {
    /**
     * A judged sentence. [transcript] already carries the automatic replacements; [originals] maps each
     * replaced word's index to the word the recogniser actually produced there (shown blue, ↺ puts it back).
     */
    class Judged(
        val transcript: VoiceTranscript,
        val language: String,
        val confidences: List<Float?>,
        val judgements: List<VoiceJudgement>,
        val originals: Map<Int, String> = emptyMap()
    )

    /**
     * What a reviewed word is marked as. The dictation strip and the walk-capture page must agree about
     * this, so the decision lives here and each renders it in its own way — the strip as a chip colour, the
     * page as the word's colour in the sentence.
     *
     * [CORRECTED] is the one the strip folds into [NONE]: in a field you can see your correction in the
     * text, while the capture page is a list you come back to, where "I already fixed this one" is worth
     * showing.
     */
    enum class WordMark { NONE, LOW_CONFIDENCE, UNKNOWN_WORD, REPLACED, CORRECTED }

    /**
     * The mark for one word of a review. A word you corrected, or one an earlier correction replaced, is
     * never "suspect" any more — what it was judged as before the edit no longer describes it. A judged
     * KNOWN_MISRECOGNITION that was NOT replaced (the phrase pass already rewrote that run) is settled too.
     */
    fun markOf(word: VoiceSessionLedger.Word): WordMark = when {
        word.autoReplaced -> WordMark.REPLACED
        word.corrected -> WordMark.CORRECTED
        word.judgement.reason == SuspectReason.KNOWN_MISRECOGNITION -> WordMark.NONE
        word.judgement.reason == SuspectReason.LOW_CONFIDENCE -> WordMark.LOW_CONFIDENCE
        word.judgement.reason == SuspectReason.UNKNOWN_WORD -> WordMark.UNKNOWN_WORD
        else -> WordMark.NONE
    }

    /**
     * Judge [input]'s words in [language]; a word under [threshold] recogniser confidence is marked
     * uncertain. Never throws — a failure to judge marks nothing rather than losing the sentence.
     */
    suspend fun judge(
        input: VoiceTranscript,
        language: String,
        threshold: Float,
        corpus: VoiceCorpusRepository,
        userDictionary: UserDictionaryRepository,
        spellCheckManager: SpellCheckManager,
        onError: (String, Exception) -> Unit = { _, _ -> }
    ): Judged {
        try {
            corpus.ensureLoaded(language)
        } catch (e: Exception) {
            onError("judge-load", e)
        }
        // Runs of words you once corrected into one ("lukou i ostupu" → one word) are replaced first, the
        // same way as single words below; the result is one word, shown blue, ↺ puts the run back.
        val (phrased, phraseOriginals) = VoiceReviewCoordinator.replacePhrases(input.text) { key ->
            (corpus.knowledge(language, key) as? VoiceKnowledge.Misrecognized)?.correction
        }
        val transcript = if (phraseOriginals.isEmpty()) {
            input
        } else {
            VoiceTranscript(phrased, input.languageCode, input.rawWords, input.rawConfidences, input.samples)
        }
        val words = VoiceTranscript.splitWords(transcript.text)
        val confidences = transcript.wordConfidences()
        val judgements = try {
            val userWords = userDictionary.matchesFor(language.substringBefore("-"))
                .filter { it.kind == UserDictionaryKind.WORD }
                .map { it.value.lowercase() }
                .toHashSet()
            VoiceWordJudge(threshold).judge(
                words = words,
                confidences = confidences,
                language = language,
                knowledge = { core -> corpus.knowledge(language, core) },
                isKnownWord = { core ->
                    core.lowercase() in userWords || spellCheckManager.isKnownWord(core, language)
                }
            )
        } catch (e: Exception) {
            onError("judge", e)
            words.map { VoiceJudgement(null) }
        }
        // A recognition you corrected before is replaced by your correction right away (shown blue; ↺ in
        // the correction box puts the recognised word back).
        val originals = phraseOriginals.toMutableMap()
        val text = StringBuilder(transcript.text)
        val ranges = VoiceReviewCoordinator.wordRanges(transcript.text)
        for (index in ranges.indices.reversed()) {
            if (index in originals) continue
            val judgement = judgements.getOrNull(index) ?: continue
            val replacement = judgement.alternative ?: continue
            if (judgement.reason != SuspectReason.KNOWN_MISRECOGNITION) continue
            val (start, end) = ranges[index]
            val token = transcript.text.substring(start, end)
            val coreStart = start + VoiceWordJudge.coreStartIn(token)
            val coreEnd = coreStart + VoiceWordJudge.coreOf(token).length
            if (coreEnd <= coreStart) continue
            text.replace(coreStart, coreEnd, replacement)
            originals[index] = token
        }
        if (originals.isEmpty()) return Judged(transcript, language, confidences, judgements)
        val replaced = VoiceTranscript(
            text.toString(),
            transcript.languageCode,
            transcript.rawWords,
            transcript.rawConfidences,
            transcript.samples
        )
        return Judged(replaced, language, confidences, judgements, originals)
    }
}
