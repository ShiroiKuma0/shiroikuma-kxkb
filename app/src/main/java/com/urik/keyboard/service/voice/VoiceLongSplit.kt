package com.urik.keyboard.service.voice

import com.konovalov.vad.webrtc.Vad
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * Cutting a long walk recording into sentences (contract §9).
 *
 * One press-to-press recording may be four hours long; Whisper hears thirty seconds. So a recording 白い熊
 * wants as sentences is cut HERE, at the pauses in it, by the same WebRTC voice-activity detector the
 * microphone recorder already uses to tell one dictated utterance from the next — the same question asked
 * of a file instead of a stream.
 *
 * The audio is never held in memory: the verdicts are taken frame by frame as the file is read, and each
 * piece is copied out of it afterwards by seeking. A four-hour recording is 460 MB and 480 000 frames.
 */
object VoiceLongSplit {
    /** 30 ms at 16 kHz — the detector's own frame, so one verdict per frame costs nothing extra. */
    const val FRAME_MS = 30
    const val FRAME_SAMPLES = 480
    const val FRAME_BYTES = FRAME_SAMPLES * 2

    /** Whisper's window is 30 s; a piece is cut at 28 s, exactly as the recorder's own sentences were. */
    const val PIECE_MAX_MS = 28_000
    private const val PIECE_MAX_FRAMES = PIECE_MAX_MS / FRAME_MS

    /** Kept around the speech of a piece so a word's onset is never shaved off by the cut. */
    private const val MARGIN_FRAMES = 5

    /** Shorter than this is not a sentence — the inbox's own floor for a clip. */
    private const val MIN_PIECE_FRAMES = 200 / FRAME_MS + 1

    /** How often the scan looks up to see whether it is still wanted — every ten seconds of audio. */
    private const val STOP_EVERY = 10_000 / FRAME_MS

    /** Speech, frame by frame. The detector on the phone; an energy rule in the tests, which have no JNI. */
    fun interface Speech {
        fun isSpeech(frame: ByteArray): Boolean
    }

    /** One sentence to cut out: frames [start] until [end], exclusive. */
    data class Piece(val start: Int, val end: Int) {
        val offsetMs: Long get() = start.toLong() * FRAME_MS
        val durationMs: Long get() = (end - start).toLong() * FRAME_MS
        val byteCount: Int get() = (end - start) * FRAME_BYTES
    }

    /**
     * The WebRTC detector, asked for a verdict on each frame by ITSELF: the silence and speech windows are
     * left at zero, which turns off the state machine the dictation recorder wants (it needs to know when an
     * utterance has ENDED) and leaves the raw per-frame answer — which is what a pause in a recording is
     * made of. Very aggressive, like the recorder: on a walk there is wind and traffic in every frame, and
     * a detector that calls all of it speech finds no pauses to cut at.
     *
     * It holds a native handle, so it is used in a `use { }` and nowhere else.
     */
    class Detector : Speech, AutoCloseable {
        private val vad = Vad.builder()
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_480)
            .setMode(Mode.VERY_AGGRESSIVE)
            .build()

        override fun isSpeech(frame: ByteArray): Boolean = vad.isSpeech(frame)

        override fun close() = vad.close()
    }

    /**
     * Was anyone speaking, frame by frame, through the [dataBytes] bytes of audio at [dataOffset] of
     * [clip]. Streamed: a four-hour recording passes through a 960-byte frame and a read buffer.
     *
     * [stop] is asked every few seconds of audio, so four hours of it can be given up on; what comes back
     * then is incomplete, and the caller is the one that knows it asked to stop.
     */
    fun verdicts(
        clip: File,
        dataOffset: Long,
        dataBytes: Long,
        speech: Speech,
        stop: () -> Boolean = { false }
    ): BooleanArray {
        val frames = (dataBytes / FRAME_BYTES).toInt()
        if (frames <= 0) return BooleanArray(0)
        val out = BooleanArray(frames)
        BufferedInputStream(FileInputStream(clip), 64 * 1024).use { input ->
            var skipped = 0L
            while (skipped < dataOffset) {
                val step = input.skip(dataOffset - skipped)
                if (step <= 0) return out
                skipped += step
            }
            val frame = ByteArray(FRAME_BYTES)
            for (i in 0 until frames) {
                if (i % STOP_EVERY == 0 && stop()) break
                var filled = 0
                while (filled < FRAME_BYTES) {
                    val read = input.read(frame, filled, FRAME_BYTES - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled < FRAME_BYTES) break
                out[i] = speech.isSpeech(frame)
            }
        }
        return out
    }

    /**
     * Where to cut, given a verdict per frame.
     *
     * Each piece is at most [PIECE_MAX_MS] long; inside that window the cut falls in the MIDDLE of the
     * longest pause, and only a window holding no pause at all is cut hard at its end — a sentence of
     * run-on speech that has to be broken somewhere. Then each piece is trimmed to the speech in it, with
     * a small margin, and a piece with no speech at all is dropped: twenty silent minutes of a walk are
     * not sentences to review, and a row the engine can only report as silence is worse than no row.
     */
    fun plan(speech: BooleanArray): List<Piece> {
        val n = speech.size
        val pieces = mutableListOf<Piece>()
        var start = 0
        while (start < n) {
            val end = if (n - start <= PIECE_MAX_FRAMES) n else cut(speech, start, start + PIECE_MAX_FRAMES)
            trimmed(speech, start, end)?.let { pieces.add(it) }
            start = end
        }
        return pieces
    }

    /**
     * The frame to cut at, somewhere in (`start`, `limit`]: the middle of the longest run of silence in
     * the window, the later one where two runs are as long as each other — a cut as late as possible keeps
     * the pieces full — and `limit` itself where there is no silence to cut in.
     */
    private fun cut(speech: BooleanArray, start: Int, limit: Int): Int {
        var bestFrom = -1
        var bestLength = 0
        var runFrom = -1
        for (i in start + 1..limit) {
            val silent = i < speech.size && !speech[i]
            if (silent) {
                if (runFrom < 0) runFrom = i
                val length = i - runFrom + 1
                if (length >= bestLength) {
                    bestLength = length
                    bestFrom = runFrom
                }
            } else {
                runFrom = -1
            }
        }
        if (bestFrom < 0) return limit
        return (bestFrom + bestLength / 2).coerceIn(start + 1, limit)
    }

    /** Frames [from] until [to] with the silence at both ends taken off, or null where nobody spoke. */
    private fun trimmed(speech: BooleanArray, from: Int, to: Int): Piece? {
        var first = -1
        var last = -1
        for (i in from until minOf(to, speech.size)) {
            if (!speech[i]) continue
            if (first < 0) first = i
            last = i
        }
        if (first < 0) return null
        val start = (first - MARGIN_FRAMES).coerceAtLeast(from)
        val end = (last + 1 + MARGIN_FRAMES).coerceAtMost(to)
        if (end - start < MIN_PIECE_FRAMES) return null
        return Piece(start, end)
    }

    /** The audio of [piece], read out of [clip] by seeking to it. */
    fun read(clip: File, dataOffset: Long, piece: Piece): ByteArray {
        val bytes = ByteArray(piece.byteCount)
        RandomAccessFile(clip, "r").use { raf ->
            raf.seek(dataOffset + piece.start.toLong() * FRAME_BYTES)
            raf.readFully(bytes)
        }
        return bytes
    }
}
