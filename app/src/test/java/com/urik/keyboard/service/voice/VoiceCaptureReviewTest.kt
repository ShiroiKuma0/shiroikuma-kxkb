package com.urik.keyboard.service.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk-capture review's ledger: ONE sentence, word offsets relative to the sentence itself
 * (`fieldStart = 0`), and no text field to re-locate anything in — so the page never calls `verify`.
 *
 * What it does call is the correction box's two moves, and those must behave on a sentence-relative ledger
 * exactly as they do at a field: one word corrected, and a run of words pulled together by ◂/▸ into a single
 * correction. The clip rides along as a path so accepting the sentence moves the recording into the corpus
 * instead of re-encoding it from samples it does not have.
 */
class VoiceCaptureReviewTest {
    private fun sentence(
        text: String,
        suspect: Int = -1,
        clip: String? = "/data/voice_capture/u1.wav"
    ): Pair<VoiceSessionLedger, VoiceSessionLedger.Entry> {
        val words = VoiceTranscript.splitWords(text)
        val ledger = VoiceSessionLedger()
        val entry = ledger.add(
            utteranceId = "u1",
            language = "en",
            committedText = text,
            fieldStart = 0,
            confidences = words.indices.map { if (it == suspect) 0.1f else null },
            judgements = words.indices.map {
                if (it == suspect) VoiceJudgement(SuspectReason.LOW_CONFIDENCE) else VoiceJudgement(null)
            },
            samples = null,
            clipPath = clip
        )
        return ledger to entry
    }

    @Test
    fun `a marked word is corrected and the sentence reads as corrected`() {
        val (ledger, entry) = sentence("I walk to the river", suspect = 1)
        assertTrue(entry.words[1].isSuspect)

        val original = ledger.spanText(entry, 1, 1)
        assertEquals("walk", original)
        val applied = ledger.applyEdit(ledger.coreStartOf(entry.words[1]), original!!, "walked")

        assertNotNull(applied)
        assertEquals("walk", applied!!.recognized)
        assertTrue(applied.word.corrected)
        assertEquals("I walked to the river", entry.currentText())
    }

    @Test
    fun `two words pulled together become one correction`() {
        val (ledger, entry) = sentence("The lukou i ostupu ends")
        // ▸ extends the run to the adjacent word, twice — the three misheard words are one correction.
        assertEquals(2, ledger.adjacentIndex(entry, 1, +1))
        assertEquals(3, ledger.adjacentIndex(entry, 2, +1))
        val run = ledger.spanText(entry, 1, 3)
        assertEquals("lukou i ostupu", run)

        val applied = ledger.applyEdit(ledger.coreStartOf(entry.words[1]), run!!, "loukou")

        assertNotNull(applied)
        assertEquals("lukou i ostupu", applied!!.recognized)
        assertEquals("The loukou ends", entry.currentText())
    }

    @Test
    fun `a correction keeps punctuation that sat around the word`() {
        val (ledger, entry) = sentence("Ahoj, houské!", suspect = 1)
        val original = ledger.spanText(entry, 1, 1)
        assertEquals("houské", original)

        ledger.applyEdit(ledger.coreStartOf(entry.words[1]), original!!, "housky")

        assertEquals("Ahoj, housky!", entry.currentText())
    }

    @Test
    fun `the recording travels as a path, never as samples`() {
        val (_, entry) = sentence("One sentence")
        assertEquals("/data/voice_capture/u1.wav", entry.clipPath)
        assertNull(entry.samples)

        // Dictation is the other way round: samples, no path.
        val (_, dictated) = sentence("One sentence", clip = null)
        assertNull(dictated.clipPath)
    }

    @Test
    fun `the word marks are the strip's, plus the one the page adds`() {
        val (ledger, entry) = sentence("I walk to the river", suspect = 1)

        // Fresh from the decode: the recogniser's own doubt shows, nothing else does.
        assertEquals(VoiceJudging.WordMark.LOW_CONFIDENCE, VoiceJudging.markOf(entry.words[1]))
        assertEquals(VoiceJudging.WordMark.NONE, VoiceJudging.markOf(entry.words[0]))

        // Corrected here: no longer suspect, and the page says so in its own colour (the strip, where the
        // text itself shows the correction, folds this into NONE).
        val original = ledger.spanText(entry, 1, 1)!!
        ledger.applyEdit(ledger.coreStartOf(entry.words[1]), original, "walked")
        assertEquals(VoiceJudging.WordMark.CORRECTED, VoiceJudging.markOf(entry.words[1]))
    }

    @Test
    fun `a word an earlier correction replaced is marked as replaced, not as unknown`() {
        val ledger = VoiceSessionLedger()
        val entry = ledger.add(
            utteranceId = "u2",
            language = "cs",
            committedText = "Chleba housky",
            fieldStart = 0,
            confidences = listOf(null, null),
            judgements = listOf(
                VoiceJudgement(null),
                VoiceJudgement(SuspectReason.KNOWN_MISRECOGNITION, "housky")
            ),
            samples = null,
            originals = mapOf(1 to "houské")
        )

        assertEquals(VoiceJudging.WordMark.REPLACED, VoiceJudging.markOf(entry.words[1]))
        assertEquals("houské", entry.words[1].recognized)
    }

    @Test
    fun `a judged misrecognition the phrase pass already rewrote is settled`() {
        val ledger = VoiceSessionLedger()
        val entry = ledger.add(
            utteranceId = "u3",
            language = "en",
            committedText = "One word",
            fieldStart = 0,
            confidences = listOf(null, null),
            judgements = listOf(
                VoiceJudgement(null),
                VoiceJudgement(SuspectReason.KNOWN_MISRECOGNITION, "word")
            ),
            samples = null
        )

        assertEquals(VoiceJudging.WordMark.NONE, VoiceJudging.markOf(entry.words[1]))
    }

    @Test
    fun `a punctuation edit keeps every word's confidence`() {
        // Whisper ended the sentence in the middle of the utterance; 白い熊 replaces the period with a comma
        // (and lowercases what followed). The row is re-derived from the edited text, so the confidences
        // must still land on the right words — they align on the word CORE, which a comma does not change.
        val heard = listOf("I", "walked", "to", "the", "river.", "The", "water", "was", "cold.")
        val confidences = floatArrayOf(0.9f, 0.8f, 0.95f, 0.9f, 0.12f, 0.7f, 0.6f, 0.9f, 0.4f)
        val edited = "I walked to the river, the water was cold."

        val mapped = VoiceTranscript(edited, "en", heard, confidences, null).wordConfidences()

        assertEquals(9, mapped.size)
        // The word Whisper was least sure of keeps its own number across the punctuation change.
        assertEquals(0.12f, mapped[4]!!, 0.0001f)
        assertEquals(0.7f, mapped[5]!!, 0.0001f)
        assertEquals(0.4f, mapped[8]!!, 0.0001f)
    }

    @Test
    fun `a hand-edited sentence re-derives as its own words`() {
        val (_, entry) = sentence("I walked to the river, the water was cold.")

        assertEquals(9, entry.words.size)
        assertEquals("river,", entry.words[4].current)
        assertEquals("I walked to the river, the water was cold.", entry.currentText())
    }

    @Test
    fun `an edit that does not match anything dictated changes nothing`() {
        val (ledger, entry) = sentence("I walk to the river")
        assertNull(ledger.applyEdit(0, "nothing like it", "x"))
        assertEquals("I walk to the river", entry.currentText())
    }
}
