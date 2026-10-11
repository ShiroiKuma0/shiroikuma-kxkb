package com.urik.keyboard.data

import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.markUserUnlocked
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The walk-capture inbox: what it takes in, what it refuses, and what survives a reload.
 *
 * The refusals are the interesting half — a clip that is not the agreed format, or not the bytes the other
 * side said it was, must leave NOTHING behind: a half-written `.wav` in this directory would read as a
 * captured sentence and be decoded as one.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceCaptureStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = VoiceCaptureStore(context)
    private val dir get() = File(context.filesDir, VoiceCaptureStore.DIR)

    @Before
    fun clean() {
        context.markUserUnlocked()
        dir.deleteRecursively()
    }

    @Test
    fun `a valid clip lands and reads back as normalised samples`() {
        val wav = wav(seconds = 1.0, peak = 8000)
        assertEquals(VoiceCaptureStore.Import.Stored, offer("one", wav))

        val entry = store.entries().single()
        assertEquals("one", entry.uuid)
        assertEquals(1000L, entry.durationMs)
        assertEquals("en", entry.language)
        assertFalse(entry.transcribed)
        assertNotNull(store.clipFile(entry))

        val samples = store.readSamples(entry)!!
        assertEquals(16000, samples.size)
        // Peak-normalised, exactly as the microphone recorder hands audio to the engine.
        assertEquals(1.0f, samples.max(), 0.0001f)
    }

    @Test
    fun `a clip that is not the agreed format is refused and leaves nothing behind`() {
        val wrongRate = wav(seconds = 1.0, peak = 8000, sampleRate = 44100)
        assertEquals(
            VoiceCaptureStore.Import.Refused(VoiceCaptureStore.Reject.FORMAT),
            offer("rate", wrongRate)
        )
        assertTrue(store.entries().isEmpty())
        assertNull(dir.listFiles()?.firstOrNull { it.name.endsWith(".wav") })
    }

    @Test
    fun `a checksum mismatch is refused`() {
        val wav = wav(seconds = 1.0, peak = 8000)
        val outcome = store.import(
            uuid = "bad-sha",
            capturedAt = 1L,
            durationMs = 1000,
            language = "en",
            declaredBytes = wav.size.toLong(),
            sha256 = "00".repeat(32),
            source = ByteArrayInputStream(wav)
        )
        assertEquals(VoiceCaptureStore.Import.Refused(VoiceCaptureStore.Reject.SHA256), outcome)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `a wrong declared length is refused`() {
        val wav = wav(seconds = 1.0, peak = 8000)
        val outcome = store.import(
            uuid = "short",
            capturedAt = 1L,
            durationMs = 1000,
            language = "en",
            declaredBytes = wav.size.toLong() - 10,
            sha256 = sha(wav),
            source = ByteArrayInputStream(wav)
        )
        assertEquals(VoiceCaptureStore.Import.Refused(VoiceCaptureStore.Reject.LENGTH), outcome)
    }

    @Test
    fun `a stray press is too short to be a sentence`() {
        val wav = wav(seconds = 0.05, peak = 8000)
        assertEquals(VoiceCaptureStore.Import.Refused(VoiceCaptureStore.Reject.LENGTH), offer("blip", wav))
    }

    @Test
    fun `a missing descriptor is its own refusal`() {
        val outcome = store.import("nofd", 1L, 1000, "en", 40000, "", null)
        assertEquals(VoiceCaptureStore.Import.Refused(VoiceCaptureStore.Reject.NO_FD), outcome)
    }

    @Test
    fun `a uuid offered twice is a duplicate, and stays one after the sentence is gone`() {
        val wav = wav(seconds = 1.0, peak = 8000)
        assertEquals(VoiceCaptureStore.Import.Stored, offer("once", wav))
        assertEquals(VoiceCaptureStore.Import.Duplicate, offer("once", wav))

        // Reviewed and dropped: the uuid is remembered, so a clip re-offered because the other side's
        // delete did not land is answered rather than imported a second time.
        store.remove("once")
        assertTrue(store.entries().isEmpty())
        assertEquals(VoiceCaptureStore.Import.Duplicate, offer("once", wav))
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `a path-shaped uuid is refused before it can become a file name`() {
        assertFalse(VoiceCaptureStore.isSafeUuid("../../etc/passwd"))
        assertFalse(VoiceCaptureStore.isSafeUuid(""))
        assertTrue(VoiceCaptureStore.isSafeUuid("3f2a-99_b"))
    }

    @Test
    fun `the decode and a later correction survive a reload`() {
        offer("keep", wav(seconds = 1.0, peak = 8000))
        store.saveTranscript(
            "keep",
            "I walk to the river",
            "I walk to the river",
            listOf("I", "walk"),
            listOf(0.9f, 0.2f)
        )
        store.saveText("keep", "I walked to the river")

        val reloaded = VoiceCaptureStore(context).entries().single()
        assertEquals("I walk to the river", reloaded.recognized)
        assertEquals("I walked to the river", reloaded.text)
        assertEquals(listOf("I", "walk"), reloaded.rawWords)
        assertEquals(0.2f, reloaded.rawConfidences!![1], 0.0001f)
        assertTrue(reloaded.transcribed)
    }

    @Test
    fun `a decode that found nothing is counted, so the row can say so`() {
        offer("silence", wav(seconds = 0.5, peak = 8000))
        assertEquals(0, store.entries().single().attempts)

        store.countFailedAttempt("silence")
        store.countFailedAttempt("silence")

        val entry = VoiceCaptureStore(context).entries().single()
        assertEquals(2, entry.attempts)
        assertFalse(entry.transcribed)
    }

    @Test
    fun `keeping a sentence leaves its recording for the corpus to move`() {
        offer("kept", wav(seconds = 1.0, peak = 8000))
        val clip = store.clipFile(store.entries().single())!!
        store.remove("kept", deleteClip = false)
        assertTrue(store.entries().isEmpty())
        assertTrue(clip.isFile)
    }

    @Test
    fun `the orphan sweep spares a fresh file and clears an old one`() {
        offer("row", wav(seconds = 1.0, peak = 8000))
        val orphan = File(dir, "orphan.wav").apply { writeBytes(wav(seconds = 1.0, peak = 8000)) }
        val fresh = File(dir, "fresh.wav").apply { writeBytes(wav(seconds = 1.0, peak = 8000)) }
        orphan.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)

        store.sweepOrphans()

        assertFalse(orphan.exists())
        assertTrue(fresh.exists())
        assertEquals(1, store.entries().size)
        assertNotNull(store.clipFile(store.entries().single()))
    }

    @Test
    fun `an offered language outside the agreed pair becomes the default`() {
        // The field decides a decode AND, on the far side of the contract, a translation direction and a
        // voice — so a code neither side agreed on is normalised where it enters, never carried onward.
        offer("odd", wav(seconds = 1.0, peak = 8000), language = "ja")
        assertEquals("en", store.entries().single().language)
    }

    @Test
    fun `an offered Czech clip arrives marked as Czech`() {
        offer("cz", wav(seconds = 1.0, peak = 8000), language = "cs")
        assertEquals("cs", store.entries().single().language)
    }

    @Test
    fun `switching the language drops the decode, so the clip is decoded again`() {
        offer("one", wav(seconds = 1.0, peak = 8000))
        store.saveTranscript("one", "Lucky was", "Lucky was", listOf("Lucky", "was"), listOf(0.9f, 0.3f))
        store.countFailedAttempt("one")

        assertTrue(store.setLanguage("one", "cs"))

        val entry = VoiceCaptureStore(context).entries().single()
        assertEquals("cs", entry.language)
        assertFalse(entry.transcribed)
        assertNull(entry.text)
        assertNull(entry.recognized)
        assertNull(entry.rawWords)
        // A clip that came back silent in the wrong language is not still accused of silence.
        assertEquals(0, entry.attempts)
        // The recording itself is untouched — it is the one thing a re-decode needs.
        assertNotNull(store.clipFile(entry))
    }

    @Test
    fun `setting the language it already has changes nothing`() {
        offer("same", wav(seconds = 1.0, peak = 8000))
        store.saveTranscript("same", "Heard this", "Heard this", null, null)

        assertFalse(store.setLanguage("same", "en"))
        // An unknown code normalises to en, which this sentence already is: still nothing to do.
        assertFalse(store.setLanguage("same", "de"))
        assertTrue(store.entries().single().transcribed)
    }

    @Test
    fun `marking the whole walk changes every sentence that was not already in that language`() {
        offer("a", wav(seconds = 1.0, peak = 8000))
        offer("b", wav(seconds = 1.0, peak = 8000))
        offer("c", wav(seconds = 1.0, peak = 8000), language = "cs")
        store.saveTranscript("a", "One", "One", null, null)

        assertEquals(2, store.setLanguageForAll("cs"))

        val entries = VoiceCaptureStore(context).entries()
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.language == "cs" })
        assertTrue(entries.none { it.transcribed })
        // Idempotent: a second tap on the same pill has nothing left to do.
        assertEquals(0, store.setLanguageForAll("cs"))
    }

    @Test
    fun `a language set on a sentence that is not here is no-one's business`() {
        assertFalse(store.setLanguage("missing", "cs"))
    }

    private fun offer(uuid: String, wav: ByteArray, language: String = "en"): VoiceCaptureStore.Import = store.import(
        uuid = uuid,
        capturedAt = 1_700_000_000_000L,
        durationMs = 0,
        language = language,
        declaredBytes = wav.size.toLong(),
        sha256 = sha(wav),
        source = ByteArrayInputStream(wav)
    )

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A canonical 16 kHz mono PCM16 WAV, the format the contract agrees on. */
    private fun wav(seconds: Double, peak: Int, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
        val count = (sampleRate * seconds).toInt()
        val dataBytes = count * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1).putShort(channels.toShort()).putInt(sampleRate)
        buffer.putInt(sampleRate * 2 * channels).putShort((2 * channels).toShort()).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        for (i in 0 until count) buffer.putShort(if (i % 2 == 0) peak.toShort() else (-peak).toShort())
        return buffer.array()
    }
}
