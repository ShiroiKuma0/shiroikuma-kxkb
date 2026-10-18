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
     *
     * [confidences], [judgements] and [originals] are all read BY WORD INDEX of [transcript]`.text` — the
     * ledger builds its words from it, the dictation path hangs its underlines on it — so all three always
     * carry exactly one entry per word of the text as it now stands, never per word of the text before the
     * replacements.
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
     * KNOWN_MISRECOGNITION that was NOT replaced (the phrase pass already rewrote that run, or the sentence
     * is one you reviewed by hand and nothing may rewrite) is settled too.
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
     * Judge [input]'s words in [language] against the keyboard's own stores; a word under [threshold]
     * recogniser confidence is marked uncertain. Never throws — a failure to judge marks nothing rather
     * than losing the sentence.
     *
     * [replace] false judges WITHOUT rewriting anything: the marks are derived, the text is handed back
     * exactly as it came in. That is the mode for a sentence you have already reviewed by hand — your
     * corrections are evidence for the NEXT decode, never a licence to rewrite a sentence you settled.
     */
    suspend fun judge(
        input: VoiceTranscript,
        language: String,
        threshold: Float,
        corpus: VoiceCorpusRepository,
        userDictionary: UserDictionaryRepository,
        spellCheckManager: SpellCheckManager,
        replace: Boolean = true,
        onError: (String, Exception) -> Unit = { _, _ -> }
    ): Judged {
        try {
            corpus.ensureLoaded(language)
        } catch (e: Exception) {
            onError("judge-load", e)
        }
        val userWords = try {
            userDictionary.matchesFor(language.substringBefore("-"))
                .filter { it.kind == UserDictionaryKind.WORD }
                .map { it.value.lowercase() }
                .toHashSet()
        } catch (e: Exception) {
            onError("judge-user-words", e)
            HashSet<String>()
        }
        return judge(
            input = input,
            language = language,
            threshold = threshold,
            knowledge = { key -> corpus.knowledge(language, key) },
            isKnownWord = { core ->
                core.lowercase() in userWords || spellCheckManager.isKnownWord(core, language)
            },
            replace = replace,
            onError = onError
        )
    }

    /**
     * The rules themselves, with the stores behind them reduced to the two questions they answer:
     * [knowledge] what you already settled about a recognition (one word, or a whole run of words
     * lowercased with single spaces), [isKnownWord] whether a word exists in this language at all.
     */
    suspend fun judge(
        input: VoiceTranscript,
        language: String,
        threshold: Float,
        knowledge: (String) -> VoiceKnowledge?,
        isKnownWord: suspend (String) -> Boolean,
        replace: Boolean = true,
        onError: (String, Exception) -> Unit = { _, _ -> }
    ): Judged {
        // Runs of words you once corrected into one ("lukou i ostupu" → one word) are replaced first, the
        // same way as single words below; the result is one word, shown blue, ↺ puts the run back.
        val (phrased, phraseOriginals) = if (replace) {
            VoiceReviewCoordinator.replacePhrases(input.text) { key ->
                (knowledge(key) as? VoiceKnowledge.Misrecognized)?.correction
            }
        } else {
            input.text to emptyMap()
        }
        val transcript = if (phraseOriginals.isEmpty()) {
            input
        } else {
            VoiceTranscript(phrased, input.languageCode, input.rawWords, input.rawConfidences, input.samples)
        }
        val words = VoiceTranscript.splitWords(transcript.text)
        val confidences = transcript.wordConfidences()
        val judgements = try {
            VoiceWordJudge(threshold).judge(
                words = words,
                confidences = confidences,
                language = language,
                knowledge = knowledge,
                isKnownWord = isKnownWord
            )
        } catch (e: Exception) {
            onError("judge", e)
            words.map { VoiceJudgement(null) }
        }
        if (!replace) return Judged(transcript, language, confidences, judgements)
        return replaceKnownMisrecognitions(transcript, language, confidences, judgements, phraseOriginals)
    }

    /**
     * A recognition you corrected before is replaced by your correction right away (shown blue; ↺ in the
     * correction box puts the recognised word back).
     *
     * One forward pass, because a correction need not be one word — "jsemtady" → "jsem tady" makes the
     * sentence a word longer — and everything downstream reads [Judged]'s three lists by word index. They
     * are therefore rebuilt here against the words that now stand: what was heard rides on the FIRST of the
     * words a replacement reads as (that is the one shown blue, and the one ↺ restores), exactly as the
     * phrase pass does it the other way round.
     */
    private fun replaceKnownMisrecognitions(
        transcript: VoiceTranscript,
        language: String,
        confidences: List<Float?>,
        judgements: List<VoiceJudgement>,
        phraseOriginals: Map<Int, String>
    ): Judged {
        val text = transcript.text
        val out = StringBuilder()
        var cursor = 0
        val outConfidences = mutableListOf<Float?>()
        val outJudgements = mutableListOf<VoiceJudgement>()
        val originals = mutableMapOf<Int, String>()
        var replacedAny = false
        for ((index, range) in VoiceReviewCoordinator.wordRanges(text).withIndex()) {
            val (start, end) = range
            val token = text.substring(start, end)
            val judgement = judgements.getOrNull(index) ?: VoiceJudgement(null)
            val confidence = confidences.getOrNull(index)
            // A word the phrase pass already rewrote keeps ITS original and is never rewritten again.
            val phraseOriginal = phraseOriginals[index]
            val coreStart = start + VoiceWordJudge.coreStartIn(token)
            val coreEnd = coreStart + VoiceWordJudge.coreOf(token).length
            val replacement = judgement.alternative
                ?.trim()
                ?.takeIf {
                    phraseOriginal == null &&
                        judgement.reason == SuspectReason.KNOWN_MISRECOGNITION &&
                        it.isNotEmpty() &&
                        coreEnd > coreStart
                }
            if (replacement == null) {
                if (phraseOriginal != null) originals[outJudgements.size] = phraseOriginal
                outConfidences.add(confidence)
                outJudgements.add(judgement)
                continue
            }
            out.append(text, cursor, coreStart).append(replacement)
            cursor = coreEnd
            replacedAny = true
            originals[outJudgements.size] = token
            outConfidences.add(confidence)
            outJudgements.add(judgement)
            // The punctuation that stood around the core stays around the replacement, so the word count
            // follows the replacement's own words.
            val rewritten = token.substring(0, coreStart - start) + replacement + token.substring(coreEnd - start)
            val pieces = VoiceTranscript.splitWords(rewritten).size.coerceAtLeast(1)
            repeat(pieces - 1) {
                outConfidences.add(null)
                outJudgements.add(VoiceJudgement(null))
            }
        }
        // Nothing rewritten here: the text (phrased or not) and its per-word lists stand as they are.
        if (!replacedAny) return Judged(transcript, language, confidences, judgements, originals)
        out.append(text, cursor, text.length)
        val replaced = VoiceTranscript(
            out.toString(),
            transcript.languageCode,
            transcript.rawWords,
            transcript.rawConfidences,
            transcript.samples
        )
        return Judged(replaced, language, outConfidences, outJudgements, originals)
    }
}
