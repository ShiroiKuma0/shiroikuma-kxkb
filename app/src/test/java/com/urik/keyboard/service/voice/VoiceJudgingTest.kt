package com.urik.keyboard.service.voice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VoiceJudging.judge] end to end — the composition the two reviews actually run, which no test covered
 * while each of them built its ledger by hand.
 *
 * Two things are pinned here. A replacement need not be one word, and everything downstream reads the
 * judged lists BY WORD INDEX, so the lists must describe the words that now stand. And a sentence already
 * settled by hand is judged for its marks alone — the walk-capture page re-judges every row after every
 * save, and a correction made in one sentence must never reach into another.
 */
class VoiceJudgingTest {
    private fun transcript(text: String, vararg confidences: Float) = VoiceTranscript(
        text = text,
        languageCode = "cs",
        rawWords = VoiceTranscript.splitWords(text),
        rawConfidences = if (confidences.isEmpty()) null else confidences,
        samples = null
    )

    private suspend fun judge(
        input: VoiceTranscript,
        knowledge: Map<String, VoiceKnowledge> = emptyMap(),
        replace: Boolean = true
    ): VoiceJudging.Judged = VoiceJudging.judge(
        input = input,
        language = "cs",
        threshold = 0.4f,
        knowledge = { key -> knowledge[key] },
        isKnownWord = { true },
        replace = replace
    )

    /** The judged sentence as the reviews see it: one ledger entry built from all three lists. */
    private fun ledgerOf(judged: VoiceJudging.Judged): VoiceSessionLedger.Entry = VoiceSessionLedger().add(
        utteranceId = "u1",
        language = judged.language,
        committedText = judged.transcript.text,
        fieldStart = 0,
        confidences = judged.confidences,
        judgements = judged.judgements,
        samples = null,
        originals = judged.originals
    )

    @Test
    fun `a correction of two words keeps every later word's own marking`() = runTest {
        // "dnes" is the unsure word; the replacement before it makes the sentence a word longer.
        val judged = judge(
            transcript("Ahoj jsemtady dnes", 0.9f, 0.9f, 0.1f),
            mapOf("jsemtady" to VoiceKnowledge.Misrecognized("jsem tady"))
        )

        assertEquals("Ahoj jsem tady dnes", judged.transcript.text)
        assertEquals(4, judged.judgements.size)
        assertEquals(4, judged.confidences.size)
        assertEquals(mapOf(1 to "jsemtady"), judged.originals)
        // The doubt belongs to "dnes" — not to "tady", which the replacement put there.
        assertEquals(SuspectReason.LOW_CONFIDENCE, judged.judgements[3].reason)
        assertNull(judged.judgements[2].reason)
        assertEquals(0.1f, judged.confidences[3])
        assertNull(judged.confidences[2])

        val entry = ledgerOf(judged)
        assertEquals(listOf("Ahoj", "jsem", "tady", "dnes"), entry.words.map { it.current })
        // What was heard rides on the first word of the replacement; ↺ there puts "jsemtady" back.
        assertEquals("jsemtady", entry.words[1].recognized)
        assertTrue(entry.words[1].autoReplaced)
        assertEquals("tady", entry.words[2].recognized)
        assertFalse(entry.words[2].autoReplaced)
        assertEquals(VoiceJudging.WordMark.REPLACED, VoiceJudging.markOf(entry.words[1]))
        assertEquals(VoiceJudging.WordMark.NONE, VoiceJudging.markOf(entry.words[2]))
        assertEquals(VoiceJudging.WordMark.LOW_CONFIDENCE, VoiceJudging.markOf(entry.words[3]))
    }

    @Test
    fun `the punctuation around a replaced word stays around the replacement`() = runTest {
        val judged = judge(
            transcript("Ahoj, jsemtady!"),
            mapOf("jsemtady" to VoiceKnowledge.Misrecognized("jsem tady"))
        )

        assertEquals("Ahoj, jsem tady!", judged.transcript.text)
        assertEquals(mapOf(1 to "jsemtady!"), judged.originals)
        assertEquals(3, judged.judgements.size)
    }

    @Test
    fun `a sentence settled by hand is judged but never rewritten`() = runTest {
        val settled = transcript("Ahoj housky dnes")
        val knowledge = mapOf("housky" to VoiceKnowledge.Misrecognized("chleba"))

        val marked = judge(settled, knowledge, replace = false)

        assertEquals("Ahoj housky dnes", marked.transcript.text)
        assertTrue(marked.originals.isEmpty())
        // Judged, not rewritten: the word carries no mark of its own any more — you settled it.
        assertEquals(SuspectReason.KNOWN_MISRECOGNITION, marked.judgements[1].reason)
        val entry = ledgerOf(marked)
        assertEquals("housky", entry.words[1].current)
        assertFalse(entry.words[1].autoReplaced)
        assertEquals(VoiceJudging.WordMark.NONE, VoiceJudging.markOf(entry.words[1]))

        // The same sentence as a FRESH decode is rewritten — which is what correcting it was for.
        assertEquals("Ahoj chleba dnes", judge(settled, knowledge, replace = true).transcript.text)
    }

    @Test
    fun `a phrase correction and a word correction keep their own indices`() = runTest {
        val judged = judge(
            transcript("The lukou i ostupu ends"),
            mapOf(
                "lukou i ostupu" to VoiceKnowledge.Misrecognized("loukou"),
                "ends" to VoiceKnowledge.Misrecognized("end")
            )
        )

        assertEquals("The loukou end", judged.transcript.text)
        assertEquals(mapOf(1 to "lukou i ostupu", 2 to "ends"), judged.originals)
        assertEquals(3, judged.judgements.size)
        assertEquals(3, judged.confidences.size)

        val entry = ledgerOf(judged)
        assertEquals("lukou i ostupu", entry.words[1].recognized)
        assertEquals("ends", entry.words[2].recognized)
        assertTrue(entry.words.drop(1).all { it.autoReplaced })
    }

    @Test
    fun `nothing known means nothing touched`() = runTest {
        val judged = judge(transcript("Ahoj housky dnes", 0.9f, 0.9f, 0.9f))

        assertEquals("Ahoj housky dnes", judged.transcript.text)
        assertTrue(judged.originals.isEmpty())
        assertEquals(3, judged.judgements.size)
        assertTrue(judged.judgements.none { it.isSuspect })
    }
}
