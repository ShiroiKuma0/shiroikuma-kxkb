package com.urik.keyboard.service.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting a long walk recording into sentences: where the cuts fall, and how long the pieces may be.
 *
 * The detector itself is the WebRTC one, which is native and cannot be loaded in a unit test, so these run
 * with an energy rule in its place ([VoiceLongSplit.Speech] exists for exactly that reason) — the framing,
 * the file reading, the cut planning and the trimming are the real ones, and the detector's own judgement
 * is what the phone verifies.
 */
class VoiceLongSplitTest {
    private val frameMs = VoiceLongSplit.FRAME_MS

    /** A frame with anything in it is speech — synthetic audio is either a tone or exact silence. */
    private val energy = VoiceLongSplit.Speech { frame ->
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0
        while (buffer.remaining() >= 2) peak = maxOf(peak, abs(buffer.short.toInt()))
        peak > 1000
    }

    @Test
    fun `a recording is cut in its pause, not at the window's end`() {
        // Twenty seconds spoken, two seconds of pause, nineteen more: the first 28 s window holds one
        // pause, and that is where the first sentence ends.
        val clip = clip(listOf(20.0 to true, 2.0 to false, 19.0 to true))
        val pieces = plan(clip)

        assertEquals(2, pieces.size)
        // The cut falls in the middle of the pause — inside it, not in either sentence.
        val cutMs = pieces[0].offsetMs + pieces[0].durationMs
        assertTrue("first piece ends at $cutMs ms", cutMs in 20_000..22_000)
        assertTrue("second piece starts at ${pieces[1].offsetMs} ms", pieces[1].offsetMs in 20_000..22_000)
        for (piece in pieces) assertTrue(piece.durationMs <= VoiceLongSplit.PIECE_MAX_MS.toLong())
    }

    @Test
    fun `speech that never pauses is cut hard, and never over the window`() {
        val clip = clip(listOf(70.0 to true))
        val pieces = plan(clip)

        assertEquals(3, pieces.size)
        for (piece in pieces) {
            assertTrue("a piece of ${piece.durationMs} ms", piece.durationMs <= VoiceLongSplit.PIECE_MAX_MS.toLong())
        }
        // Each hard cut is where the one before it ended, so nothing spoken falls between two pieces.
        assertEquals(0L, pieces[0].offsetMs)
        assertEquals(pieces[0].durationMs, pieces[1].offsetMs)
        assertEquals(pieces[1].offsetMs + pieces[1].durationMs, pieces[2].offsetMs)
        // And all three together are the whole seventy seconds, to the frame.
        assertEquals(70_000L / frameMs * frameMs, pieces.sumOf { it.durationMs })
    }

    @Test
    fun `the silence a walk is mostly made of becomes no sentences at all`() {
        val clip = clip(listOf(60.0 to false))
        assertTrue(plan(clip).isEmpty())
    }

    @Test
    fun `a piece is trimmed to what was said in it`() {
        // A minute of silence with four seconds of speech in the middle of it: one sentence, four seconds
        // long, not the three windows the recording would otherwise be cut into.
        val clip = clip(listOf(28.0 to false, 4.0 to true, 28.0 to false))
        val pieces = plan(clip)

        assertEquals(1, pieces.size)
        val piece = pieces.single()
        assertTrue("starts at ${piece.offsetMs} ms", piece.offsetMs in 27_800..28_000)
        assertTrue("runs for ${piece.durationMs} ms", piece.durationMs in 4_000..4_400)
    }

    @Test
    fun `the pieces are the audio itself, read back out of the recording`() {
        val clip = clip(listOf(1.0 to false, 2.0 to true, 1.0 to false))
        val pieces = plan(clip)
        val piece = pieces.single()

        val bytes = VoiceLongSplit.read(clip, HEADER.toLong(), piece)
        assertEquals(piece.byteCount, bytes.size)
        // What was read is the speech itself: the same bytes read as silence before the piece began.
        val middle = bytes.size / 2
        assertTrue(energy.isSpeech(bytes.copyOfRange(middle, middle + VoiceLongSplit.FRAME_BYTES)))
        // The margin the trimming keeps is still silence — a word's onset is never shaved off.
        assertFalse(energy.isSpeech(bytes.copyOfRange(0, VoiceLongSplit.FRAME_BYTES)))
    }

    private fun plan(clip: File): List<VoiceLongSplit.Piece> {
        val dataBytes = clip.length() - HEADER
        return VoiceLongSplit.plan(VoiceLongSplit.verdicts(clip, HEADER.toLong(), dataBytes, energy))
    }

    /** A canonical 16 kHz mono PCM16 WAV of the given runs of (seconds, speaking). */
    private fun clip(runs: List<Pair<Double, Boolean>>): File {
        val samples = runs.sumOf { (seconds, _) -> (seconds * RATE).toInt() }
        val data = samples * 2
        val buffer = ByteBuffer.allocate(HEADER + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1).putShort(1).putInt(RATE)
        buffer.putInt(RATE * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(data)
        var at = 0
        for ((seconds, speaking) in runs) {
            repeat((seconds * RATE).toInt()) {
                val value = if (speaking) (8000 * sin(at * 2 * Math.PI * 220 / RATE)).toInt() else 0
                buffer.putShort(value.toShort())
                at++
            }
        }
        val file = File.createTempFile("long-recording", ".wav")
        file.deleteOnExit()
        file.writeBytes(buffer.array())
        return file
    }

    private companion object {
        const val RATE = 16000
        const val HEADER = 44
    }
}
