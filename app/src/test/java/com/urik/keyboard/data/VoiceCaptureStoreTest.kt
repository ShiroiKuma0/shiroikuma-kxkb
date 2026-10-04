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

    private fun offer(uuid: String, wav: ByteArray): VoiceCaptureStore.Import = store.import(
        uuid = uuid,
        capturedAt = 1_700_000_000_000L,
        durationMs = 0,
        language = "en",
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
