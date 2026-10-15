package com.urik.keyboard.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.data.database.DatabaseAvailability
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.markUserUnlocked
import com.urik.keyboard.service.voice.VoiceJudgement
import com.urik.keyboard.service.voice.VoiceSessionLedger
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a walk sentence's recording lives after a correction.
 *
 * Correcting one word stores the evidence at once, and storing it MOVES the clip out of the capture inbox
 * and into the voice corpus — while the inbox row stays until the sentence is sent. A review page that
 * looks only in the inbox therefore loses the recording on the first correction: the Play button vanishes,
 * and with it the ability to decode the sentence again after a language switch. The clip is not gone, it
 * has moved, and both halves of that are pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoiceCaptureClipTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = VoiceCaptureStore(context)
    private val captureDir get() = File(context.filesDir, VoiceCaptureStore.DIR)
    private lateinit var db: KeyboardDatabase
    private lateinit var corpus: VoiceCorpusRepository

    @Before
    fun setUp() {
        context.markUserUnlocked()
        DatabaseAvailability.resetForTesting()
        captureDir.deleteRecursively()
        File(context.filesDir, VoiceCorpusRepository.CORPUS_DIR).deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java)
            .allowMainThreadQueries().build()
        corpus = VoiceCorpusRepository(context, db.voiceCorpusDao())
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, VoiceCorpusRepository.CORPUS_DIR).deleteRecursively()
    }

    @Test
    fun `a corrected sentence's recording moves to the corpus and is found there`() {
        offer("walk1", wav(seconds = 1.0, peak = 8000))
        val entry = store.entries().single()
        val inbox = store.clipFile(entry)!!

        correct(entry.uuid, inbox.absolutePath)
        val moved = awaitCorpusClip("walk1")

        // The review resolves it by the utterance id, which here IS the capture uuid.
        assertNotNull("the clip should be in the corpus", moved)
        assertEquals(File(context.filesDir, VoiceCorpusRepository.CORPUS_DIR), moved!!.parentFile)
        // Gone from the inbox — the move is the point, there is never a second copy of the audio.
        assertNull(store.clipFile(store.entries().single()))
    }

    @Test
    fun `the moved recording still decodes, so the sentence can be transcribed again`() {
        offer("walk2", wav(seconds = 1.0, peak = 8000))
        val entry = store.entries().single()
        correct(entry.uuid, store.clipFile(entry)!!.absolutePath)
        val moved = awaitCorpusClip("walk2")!!

        val samples = store.readSamples(moved)!!

        assertEquals(16000, samples.size)
        assertEquals(1.0f, samples.max(), 0.0001f)
    }

    @Test
    fun `a clip that is nowhere reads as nothing rather than throwing`() {
        assertNull(store.readSamples(File(captureDir, "absent.wav")))
    }

    @Test
    fun `removing a sentence that is already gone is no-one's business`() {
        // What the review's deferred deletion relies on: a sentence sent while its deletion was pending
        // has already left the store, and committing that deletion on the way out must shrug it off.
        offer("sent", wav(seconds = 1.0, peak = 8000))
        store.remove("sent", deleteClip = false)

        store.remove("sent")

        assertTrue(store.entries().isEmpty())
    }

    /** Correct the first word of a one-sentence ledger carrying [clipPath], as the review page does. */
    private fun correct(uuid: String, clipPath: String) {
        val ledger = VoiceSessionLedger()
        val sentence = ledger.add(
            utteranceId = uuid,
            language = "en",
            committedText = "I walk to the river",
            fieldStart = 0,
            confidences = List(5) { null },
            judgements = List(5) { VoiceJudgement(null) },
            samples = null,
            clipPath = clipPath
        )
        corpus.recordCorrection(sentence, sentence.words[1], "walk", "walked")
    }

    /** The corpus write runs on the repository's own scope; wait for it rather than race it. */
    private fun awaitCorpusClip(uuid: String): File? {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            corpus.clipOf(uuid)?.let { return it }
            Thread.sleep(20)
        }
        return corpus.clipOf(uuid)
    }

    private fun offer(uuid: String, wav: ByteArray): VoiceCaptureStore.Import = store.import(
        uuid = uuid,
        capturedAt = 1_700_000_000_000L,
        durationMs = 0,
        language = "en",
        declaredBytes = wav.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) },
        source = ByteArrayInputStream(wav)
    )

    /** A canonical 16 kHz mono PCM16 WAV, the format the contract agrees on. */
    private fun wav(seconds: Double, peak: Int): ByteArray {
        val count = (16000 * seconds).toInt()
        val dataBytes = count * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1).putShort(1).putInt(16000)
        buffer.putInt(16000 * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        for (i in 0 until count) buffer.putShort(if (i % 2 == 0) peak.toShort() else (-peak).toShort())
        return buffer.array()
    }
}
