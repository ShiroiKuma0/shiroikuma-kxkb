package com.urik.keyboard.service.voice

import com.urik.keyboard.data.VoiceCorpusRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceReviewLogicTest {
    // ---- confidence alignment ------------------------------------------------------------------------

    @Test
    fun `confidences align through the engine's capitalisation and ellipsis clean-up`() {
        val words = VoiceTranscript.splitWords("Ahoj jak se máš")
        val raw = listOf("ahoj", "jak", "se...", "máš")
        val conf = floatArrayOf(0.9f, 0.8f, 0.2f, 0.7f)
        assertEquals(listOf(0.9f, 0.8f, 0.2f, 0.7f), VoiceTranscript.alignConfidences(words, raw, conf))
    }

    @Test
    fun `a word missing from the raw decode gets no confidence, the rest still align`() {
        val words = listOf("Ahoj", "Pavle", "jak")
        val raw = listOf("Ahoj", "jak")
        val conf = floatArrayOf(0.9f, 0.5f)
        assertEquals(listOf(0.9f, null, 0.5f), VoiceTranscript.alignConfidences(words, raw, conf))
    }

    @Test
    fun `no confidences means none per word`() {
        assertEquals(listOf(null, null), VoiceTranscript.alignConfidences(listOf("a", "b"), null, null))
    }

    // ---- the judge ---------------------------------------------------------------------------------------

    private val dictionary = setOf("ahoj", "jak", "se", "máš", "česko", "slovenský")

    private suspend fun judge(
        words: List<String>,
        confidences: List<Float?> = words.map { 0.9f },
        language: String = "cs",
        knowledge: Map<String, VoiceKnowledge> = emptyMap()
    ) = VoiceWordJudge().judge(words, confidences, language, { knowledge[it] }, { it.lowercase() in dictionary })

    @Test
    fun `unknown words and low-confidence words are suspect, punctuation is ignored`() = runTest {
        val result = judge(listOf("„Ahoj,", "Pavle", "jak", "se"), listOf(0.9f, 0.9f, 0.1f, 0.9f))
        assertEquals(
            listOf(null, SuspectReason.UNKNOWN_WORD, SuspectReason.LOW_CONFIDENCE, null),
            result.map { it.reason }
        )
    }

    @Test
    fun `a confirmed word is never suspect, even unknown and unsure`() = runTest {
        val result = judge(listOf("Pavle"), listOf(0.05f), knowledge = mapOf("pavle" to VoiceKnowledge.Confirmed))
        assertFalse(result.single().isSuspect)
    }

    @Test
    fun `a known misrecognition is suspect with the correction offered`() = runTest {
        val result = judge(listOf("jak"), knowledge = mapOf("jak" to VoiceKnowledge.Misrecognized("Jakub")))
        assertEquals(SuspectReason.KNOWN_MISRECOGNITION, result.single().reason)
        assertEquals("Jakub", result.single().alternative)
    }

    @Test
    fun `numbers, hyphenated known parts and Japanese are not marked`() = runTest {
        assertFalse(judge(listOf("10:35")).single().isSuspect)
        assertFalse(judge(listOf("česko-slovenský")).single().isSuspect)
        assertFalse(judge(listOf("こんにちは"), listOf(0.01f), language = "ja").single().isSuspect)
    }

    @Test
    fun `core strips surrounding punctuation only`() {
        assertEquals("Ahoj", VoiceWordJudge.coreOf("„Ahoj,“"))
        assertEquals("don't", VoiceWordJudge.coreOf("don't."))
        assertEquals(1, VoiceWordJudge.coreStartIn("„Ahoj,"))
        assertEquals("", VoiceWordJudge.coreOf("—"))
    }

    // ---- the ledger --------------------------------------------------------------------------------------

    private fun ledgerWith(text: String, start: Int, suspects: Set<Int> = emptySet()): VoiceSessionLedger {
        val ledger = VoiceSessionLedger()
        val n = VoiceTranscript.splitWords(text).size
        ledger.add(
            "u1", "cs", text, start, List(n) { 0.9f },
            List(n) { if (it in suspects) VoiceJudgement(SuspectReason.UNKNOWN_WORD) else VoiceJudgement(null) },
            null
        )
        return ledger
    }

    @Test
    fun `words are recorded at their field offsets and found by position`() {
        val ledger = ledgerWith("Ahoj Pavle, jak", 10)
        val (_, word) = ledger.wordAt(16)!!
        assertEquals("Pavle,", word.current)
        assertEquals(15, word.start)
        assertNull(ledger.wordAt(5))
    }

    @Test
    fun `a correction keeps the punctuation and shifts the following words`() {
        val ledger = ledgerWith("Ahoj Pavle, jak", 0, suspects = setOf(1))
        val word = ledger.applyEdit(5, "Pavle", "Pavlíku")!!.word
        assertEquals("Pavlíku,", word.current)
        assertTrue(word.corrected)
        assertEquals(14, ledger.wordAt(14)!!.second.start)
        assertEquals("jak", ledger.wordAt(14)!!.second.current)
        assertTrue(ledger.suspects().isEmpty())
    }

    @Test
    fun `several dictated words corrected into one become one word, recorded with what was heard`() {
        val ledger = ledgerWith("z lukou i ostupu, konec", 0, suspects = setOf(1, 2, 3))
        val entry = ledger.entries().single()
        assertEquals(2, ledger.adjacentIndex(entry, 1, +1))
        assertEquals("lukou i ostupu", ledger.spanText(entry, 1, 3))
        val edit = ledger.applyEdit(2, "lukou i ostupu", "lukoiostupu")!!
        assertEquals("lukou i ostupu", edit.recognized)
        assertEquals("lukoiostupu,", edit.word.current)
        assertTrue(edit.word.corrected)
        assertEquals("z lukoiostupu, konec", entry.currentText())
        assertEquals(listOf("z", "lukoiostupu,", "konec"), ledger.allWords().map { it.second.current })
        assertEquals(15, ledger.allWords().last().second.start)
        ledger.verify("z lukoiostupu, konec", 0)
        assertEquals(3, ledger.allWords().size)
    }

    @Test
    fun `an automatically replaced word keeps what was heard, and putting it back confirms the recognition`() {
        val ledger = VoiceSessionLedger()
        ledger.add(
            "u1", "cs", "Chleba housky", 0, listOf(0.9f, 0.9f),
            listOf(VoiceJudgement(null), VoiceJudgement(SuspectReason.KNOWN_MISRECOGNITION, "housky")),
            null,
            originals = mapOf(1 to "houské")
        )
        val word = ledger.allWords().last().second
        assertTrue(word.autoReplaced)
        assertEquals("houské", word.recognized)
        assertTrue(ledger.suspects().isEmpty())
        val revert = ledger.applyEdit(7, "housky", "houské")!!
        assertEquals("houské", revert.recognized)
        assertEquals("houské", revert.word.current)
        assertFalse(revert.word.autoReplaced)
    }

    @Test
    fun `a corrected run of words is replaced as one word, longest run first`() {
        val rules = mapOf("lukou i ostupu" to "lukoiostupu", "i ostupu" to "WRONG")
        val (text, originals) = VoiceReviewCoordinator.replacePhrases("Z lukou i ostupu, konec.") { rules[it] }
        assertEquals("Z lukoiostupu, konec.", text)
        assertEquals(mapOf(1 to "lukou i ostupu,"), originals)
    }

    @Test
    fun `a run is matched case-insensitively and nothing else changes`() {
        val rules = mapOf("lukou i ostupu" to "lukoiostupu")
        assertEquals("Lukoiostupu jde", VoiceReviewCoordinator.replacePhrases("Lukou I ostupu jde") { rules[it]?.replaceFirstChar(Char::uppercase) }.first)
        val (same, none) = VoiceReviewCoordinator.replacePhrases("Nic tu není") { rules[it] }
        assertEquals("Nic tu není", same)
        assertTrue(none.isEmpty())
    }

    @Test
    fun `words typed over by hand are found as corrections, word for word`() {
        val ledger = ledgerWith("Chleba houské rohlike.", 0, suspects = setOf(1, 2))
        ledger.verify("Chleba housky rohlíky.", 0)
        val entry = ledger.entries().single()
        assertEquals(
            listOf(
                VoiceSessionLedger.TypedCorrection(1, 1, "houské", "housky"),
                VoiceSessionLedger.TypedCorrection(2, 2, "rohlike", "rohlíky")
            ),
            entry.typedCorrections
        )
        assertEquals("Chleba housky rohlíky", entry.currentText())
        assertTrue(ledger.suspects().isEmpty())
    }

    @Test
    fun `several words typed over into one between anchors`() {
        val ledger = ledgerWith("z lukou i ostupu konec", 0)
        ledger.verify("z lukoiostupu konec", 0)
        assertEquals(
            listOf(VoiceSessionLedger.TypedCorrection(1, 3, "lukou i ostupu", "lukoiostupu")),
            ledger.entries().single().typedCorrections
        )
    }

    @Test
    fun `a word mid-retyping is found again once complete`() {
        val ledger = ledgerWith("Ahoj Pavle jak", 0, suspects = setOf(1))
        ledger.verify("Ahoj Pa jak", 0)
        ledger.verify("Ahoj Pavlíku jak", 0)
        assertEquals(
            listOf(VoiceSessionLedger.TypedCorrection(1, 1, "Pavle", "Pavlíku")),
            ledger.entries().single().typedCorrections
        )
        // Retyped back exactly: intact again, no correction.
        ledger.verify("Ahoj Pavle jak", 0)
        assertTrue(ledger.entries().single().typedCorrections.isEmpty())
        assertTrue(ledger.entries().single().words[1].intact)
    }

    @Test
    fun `text typed after the dictation is not taken for a correction`() {
        val ledger = ledgerWith("Ahoj Pavle", 0, suspects = setOf(1))
        ledger.verify("Ahoj Pavle a pak jsem šel domů", 0)
        assertTrue(ledger.entries().single().typedCorrections.isEmpty())
    }

    @Test
    fun `a run broken by typed text cannot be merged`() {
        val ledger = ledgerWith("a b c", 0)
        ledger.verify("a X b c", 0)
        val entry = ledger.entries().single()
        assertNull(ledger.adjacentIndex(entry, 0, +1))
        assertNull(ledger.applyEdit(0, "a b", "ab"))
    }

    @Test
    fun `a word corrected into two words still verifies as intact`() {
        val ledger = ledgerWith("Jakse má", 0, suspects = setOf(0))
        ledger.applyEdit(0, "Jakse", "Jak se")
        ledger.verify("Jak se má", 0)
        assertEquals(listOf("Jak se", "má"), ledger.allWords().map { it.second.current })
    }

    @Test
    fun `a correction elsewhere is not attributed to dictation`() {
        val ledger = ledgerWith("Ahoj Pavle", 0)
        assertNull(ledger.applyEdit(5, "Petr", "Pavel"))
    }

    @Test
    fun `verify follows the text when something is typed before it`() {
        val ledger = ledgerWith("Ahoj Pavle", 6, suspects = setOf(1))
        ledger.verify("Nazdar Uff! Ahoj Pavle konec", 0)
        val (_, word) = ledger.suspects().single()
        assertEquals(17, word.start)
        assertTrue(word.intact)
    }

    @Test
    fun `a deleted or retyped word drops out and is never confirmed`() {
        val ledger = ledgerWith("Ahoj Pavle jak", 0, suspects = setOf(1, 2))
        ledger.verify("Ahoj jak", 0)
        val drained = ledger.drain().single()
        assertFalse(drained.words[1].intact)
        assertTrue(drained.words[2].intact)
        assertEquals(5, drained.words[2].start)
    }

    @Test
    fun `an entry outside the visible window is left as it was`() {
        val ledger = ledgerWith("Ahoj Pavle", 5000, suspects = setOf(1))
        ledger.verify("something else entirely", 0, reachesFieldStart = true, reachesFieldEnd = false)
        assertTrue(ledger.suspects().single().second.intact)
    }

    // ---- corpus knowledge: newest evidence wins ----------------------------------------------------------

    @Test
    fun `a correction marks the recognition and settles what was typed`() {
        val map = mutableMapOf<String, VoiceKnowledge>()
        VoiceCorpusRepository.applyEvent(map, "Jak", "Jakub", "corrected")
        assertEquals(VoiceKnowledge.Misrecognized("Jakub"), map["jak"])
        assertEquals(VoiceKnowledge.Confirmed, map["jakub"])
    }

    @Test
    fun `a later confirmation overrides an earlier correction`() {
        val map = mutableMapOf<String, VoiceKnowledge>()
        VoiceCorpusRepository.applyEvent(map, "jak", "Jakub", "corrected")
        VoiceCorpusRepository.applyEvent(map, "jak", "jak", "confirmed")
        assertEquals(VoiceKnowledge.Confirmed, map["jak"])
    }

    @Test
    fun `a later correction overrides an earlier confirmation`() {
        val map = mutableMapOf<String, VoiceKnowledge>()
        VoiceCorpusRepository.applyEvent(map, "Pavle", "Pavle", "confirmed")
        VoiceCorpusRepository.applyEvent(map, "Pavle", "Pavel", "corrected")
        assertEquals(VoiceKnowledge.Misrecognized("Pavel"), map["pavle"])
    }
}
