package com.urik.keyboard.service.voice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a hand edit of a walk sentence costs: the words it changed, and nothing else.
 *
 * The walk-capture page rebuilds every row from the store after each save and each fold of the phone, so
 * the two marks that are EVENTS rather than judgements — a word you corrected, a word an earlier
 * correction of yours replaced — cannot be re-derived and have to travel with the sentence
 * ([VoiceSettledWords]). Fixing one comma used to take the blue and the green off every word in the
 * sentence, the untouched ones included.
 *
 * Each test runs the page's own sequence: judge → ledger → read the marks off it → carry them onto the
 * edited text → judge and build the ledger again, the way the row is rebuilt from the store.
 */
class VoiceSettledWordsTest {
    private val known = setOf("ahoj", "jsem", "tady", "housky", "rohlíky", "koupil", "dnes", "ráno")

    private fun transcript(text: String, raw: List<String>?, confidences: FloatArray?) =
        VoiceTranscript(text, "cs", raw, confidences, null)

    /** The page's [com.urik.keyboard.settings.voice.VoiceCaptureFragment] `rowFor`, to the word marks. */
    private suspend fun row(
        text: String,
        raw: List<String>? = null,
        confidences: FloatArray? = null,
        knowledge: Map<String, VoiceKnowledge> = emptyMap(),
        settled: List<VoiceSettledWords.Settled> = emptyList(),
        edited: Boolean = false
    ): VoiceSessionLedger.Entry {
        val judged = VoiceJudging.judge(
            input = transcript(text, raw, confidences),
            language = "cs",
            threshold = 0.4f,
            knowledge = { key -> knowledge[key] },
            isKnownWord = { core -> core.lowercase() in known },
            replace = !edited
        )
        return VoiceSessionLedger().add(
            utteranceId = "u1",
            language = judged.language,
            committedText = judged.transcript.text,
            fieldStart = 0,
            confidences = judged.confidences,
            judgements = judged.judgements,
            samples = null,
            originals = judged.originals + VoiceSettledWords.originals(settled),
            correctedAt = VoiceSettledWords.correctedAt(settled)
        )
    }

    private fun marks(entry: VoiceSessionLedger.Entry) =
        entry.words.filter { it.intact }.map { it.current to VoiceJudging.markOf(it) }

    @Test
    fun `an edit costs only the words it changed`() = runTest {
        val decode = "Ahoj jsemtady dnes housky koupil"
        val raw = VoiceTranscript.splitWords(decode)
        val confidences = floatArrayOf(0.9f, 0.9f, 0.1f, 0.9f, 0.9f)
        val knowledge = mapOf("jsemtady" to VoiceKnowledge.Misrecognized("jsem tady"))

        val before = row(decode, raw, confidences, knowledge)
        assertEquals("Ahoj jsem tady dnes housky koupil", before.currentText())
        assertEquals(
            listOf(
                "Ahoj" to VoiceJudging.WordMark.NONE,
                "jsem" to VoiceJudging.WordMark.REPLACED,
                "tady" to VoiceJudging.WordMark.NONE,
                "dnes" to VoiceJudging.WordMark.LOW_CONFIDENCE,
                "housky" to VoiceJudging.WordMark.NONE,
                "koupil" to VoiceJudging.WordMark.NONE
            ),
            marks(before)
        )

        // 白い熊 fixes the punctuation and swaps one word.
        val edited = "Ahoj jsem tady, dnes rohlíky koupil"
        val carried = VoiceSettledWords.carry(before.currentText(), edited, VoiceSettledWords.of(before))
        val after = row(edited, raw, confidences, knowledge, carried, edited = true)

        assertEquals(
            listOf(
                "Ahoj" to VoiceJudging.WordMark.NONE,
                "jsem" to VoiceJudging.WordMark.REPLACED,
                "tady," to VoiceJudging.WordMark.NONE,
                "dnes" to VoiceJudging.WordMark.LOW_CONFIDENCE,
                "rohlíky" to VoiceJudging.WordMark.NONE,
                "koupil" to VoiceJudging.WordMark.NONE
            ),
            marks(after)
        )
        // The replacement still knows what was heard there, so ↺ still has it to put back.
        assertEquals("jsemtady", after.words[1].recognized)
    }

    @Test
    fun `a word the edit really changed keeps nothing`() = runTest {
        val decode = "Ahoj housky koupil"
        val knowledge = mapOf("housky" to VoiceKnowledge.Misrecognized("rohlíky"))

        val before = row(decode, knowledge = knowledge)
        assertEquals("Ahoj rohlíky koupil", before.currentText())
        assertEquals(VoiceJudging.WordMark.REPLACED, VoiceJudging.markOf(before.words[1]))

        // The replacement was not what was meant either: it is typed over in the sentence box.
        val edited = "Ahoj housky koupil"
        val carried = VoiceSettledWords.carry(before.currentText(), edited, VoiceSettledWords.of(before))
        assertTrue(carried.isEmpty())

        val after = row(edited, knowledge = knowledge, settled = carried, edited = true)
        // Settled by hand: judged, never rewritten, and with no mark of its own any more.
        assertEquals("Ahoj housky koupil", after.currentText())
        assertEquals(VoiceJudging.WordMark.NONE, VoiceJudging.markOf(after.words[1]))
    }

    @Test
    fun `a correction survives the rebuild its own save triggers`() = runTest {
        val decode = "Ahoj houské koupil"
        val raw = VoiceTranscript.splitWords(decode)
        val before = row(decode, raw, floatArrayOf(0.9f, 0.1f, 0.9f))
        val ledger = VoiceSessionLedger().apply { restore(listOf(before)) }

        val original = ledger.spanText(before, 1, 1)!!
        ledger.applyEdit(ledger.coreStartOf(before.words[1]), original, "housky")
        assertEquals(VoiceJudging.WordMark.CORRECTED, VoiceJudging.markOf(before.words[1]))

        val settled = VoiceSettledWords.of(before)
        assertEquals(
            listOf(VoiceSettledWords.Settled(1, "houské", VoiceSettledWords.State.CORRECTED)),
            settled
        )

        val after = row(before.currentText(), raw, floatArrayOf(0.9f, 0.1f, 0.9f), settled = settled, edited = true)
        assertEquals("Ahoj housky koupil", after.currentText())
        assertEquals(VoiceJudging.WordMark.CORRECTED, VoiceJudging.markOf(after.words[1]))
        assertEquals("houské", after.words[1].recognized)
    }

    @Test
    fun `a run of words pulled into one correction leaves the words after it where they now are`() = runTest {
        // ◂ / ▸ make three misheard words one correction, so the sentence loses two words and everything
        // after them moves up — and the settled words are indexed by the sentence as it NOW reads.
        val before = row("Ahoj lukou i ostupu koupil")
        val ledger = VoiceSessionLedger().apply { restore(listOf(before)) }
        val run = ledger.spanText(before, 1, 3)!!
        assertEquals("lukou i ostupu", run)

        ledger.applyEdit(ledger.coreStartOf(before.words[1]), run, "loukou")
        assertEquals("Ahoj loukou koupil", before.currentText())

        assertEquals(
            listOf(VoiceSettledWords.Settled(1, "lukou", VoiceSettledWords.State.CORRECTED)),
            VoiceSettledWords.of(before)
        )
        val after = row(before.currentText(), settled = VoiceSettledWords.of(before), edited = true)
        assertEquals(
            listOf(
                "Ahoj" to VoiceJudging.WordMark.NONE,
                "loukou" to VoiceJudging.WordMark.CORRECTED,
                "koupil" to VoiceJudging.WordMark.NONE
            ),
            marks(after)
        )
    }

    @Test
    fun `marks follow their words when the edit adds one`() = runTest {
        val decode = "Jsemtady koupil"
        val before = row(decode, knowledge = mapOf("jsemtady" to VoiceKnowledge.Misrecognized("Jsem tady")))
        assertEquals("Jsem tady koupil", before.currentText())
        assertEquals(VoiceJudging.WordMark.REPLACED, VoiceJudging.markOf(before.words[0]))

        val carried = VoiceSettledWords.carry(before.currentText(), "Dnes ráno jsem tady koupil", VoiceSettledWords.of(before))

        assertEquals(listOf(VoiceSettledWords.Settled(2, "Jsemtady", VoiceSettledWords.State.REPLACED)), carried)
    }

    @Test
    fun `a sentence with nothing settled carries nothing`() = runTest {
        val before = row("Ahoj housky koupil")
        assertTrue(VoiceSettledWords.of(before).isEmpty())
        assertTrue(VoiceSettledWords.carry("Ahoj housky koupil", "Ahoj, housky koupil", emptyList()).isEmpty())
    }
}
