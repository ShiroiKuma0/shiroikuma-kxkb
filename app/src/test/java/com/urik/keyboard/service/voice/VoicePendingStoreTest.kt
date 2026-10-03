package com.urik.keyboard.service.voice

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The review under way survives a keyboard restart: words, their state, typed corrections and the audio. */
@RunWith(RobolectricTestRunner::class)
class VoicePendingStoreTest {
    private val store = VoicePendingStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `a saved review comes back whole and clear removes it`() {
        val ledger = VoiceSessionLedger()
        val samples = FloatArray(1600) { if (it % 2 == 0) 0.5f else -0.5f }
        ledger.add(
            "u1", "cs", "Chleba houské", 10, listOf(0.9f, 0.1f),
            listOf(VoiceJudgement(null), VoiceJudgement(SuspectReason.KNOWN_MISRECOGNITION, "housky")),
            samples,
            originals = mapOf(1 to "houské")
        )
        ledger.entries().single().typedCorrections = listOf(VoiceSessionLedger.TypedCorrection(0, 0, "Chleba", "Chléb"))
        store.save("app|7", ledger.entries())

        val (field, entries) = store.load()!!
        assertEquals("app|7", field)
        val entry = entries.single()
        assertEquals("u1", entry.utteranceId)
        val word = entry.words[1]
        assertEquals("houské", word.recognized)
        assertEquals(17, word.start)
        assertTrue(word.autoReplaced)
        assertEquals(SuspectReason.KNOWN_MISRECOGNITION, word.judgement.reason)
        assertEquals("housky", word.judgement.alternative)
        assertEquals("Chléb", entry.typedCorrections.single().corrected)
        assertNotNull(entry.samples)
        assertEquals(1600, entry.samples!!.size)
        assertEquals(0.5f, entry.samples!![0], 0.001f)

        store.clear()
        assertNull(store.load())
    }
}
