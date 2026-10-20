package com.urik.keyboard.service.voice

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A long walk recording kept as an Opus OGG (contract §9) — the "record this for later" half of the
 * walk-capture press, the half that is never transcribed.
 *
 * The WAV 自由作業盤 hands over is the agreed format for a recogniser, not for keeping: an hour of it is
 * 115 MB. Opus at 32 kbit/s mono is the same hour in 14 MB, which is what a recording 白い熊 means to listen
 * to again should cost. The audio is read and encoded in 20 ms frames, so the four-hour ceiling costs no
 * more memory here than a sentence does.
 *
 * Nothing in the keyboard decodes OGG, deliberately: once a recording is written out it belongs to 白い熊's
 * file manager and to whatever plays it, not to this inbox.
 */
object VoiceOggExport {
    /** The OGG muxer is Android 10 and up. Below it, a recording can still be cut into sentences. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private const val SAMPLE_RATE = 16000
    private const val BIT_RATE = 32000

    /** Opus encodes in frames; 20 ms is the common one, and the encoder wants whole frames. */
    private const val FRAME_MS = 20
    private const val FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MS / 1000
    private const val FRAME_US = FRAME_MS * 1000L

    private const val TIMEOUT_US = 10_000L

    /** A kept recording is named by when it was spoken, which is the only thing about it that is known. */
    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

    /**
     * Where a recording spoken at [capturedAt] goes inside [dir] — `2026-10-20_14-31-09.ogg`, and
     * `…_2.ogg`, `…_3.ogg` for the ones after it if that name is taken.
     *
     * **Nothing in that directory is ever written over.** Two presses can share a second, 白い熊 keeps their
     * own files there, and a recording cannot be made again — so the name moves aside rather than the file.
     * This only names the target; it creates neither the directory nor the file.
     */
    fun targetIn(dir: File, capturedAt: Long): File {
        val base = stamp.format(Date(capturedAt))
        var target = File(dir, "$base.ogg")
        var next = 2
        while (target.exists()) {
            target = File(dir, base + "_" + next + ".ogg")
            next++
        }
        return target
    }

    /**
     * Encode the [dataBytes] bytes of PCM16 at [dataOffset] of [source] into [target] as Opus in an OGG.
     * [onProgress] is handed the milliseconds encoded so far, often enough to move a bar. [stop] is asked
     * once per frame, so an hour of audio can be given up on; false then, and the caller deletes [target].
     *
     * Throws what the encoder, the muxer or the file system throws: the caller reports it to 白い熊 rather
     * than leaving a half-written file behind as though it had worked.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun write(
        source: File,
        dataOffset: Long,
        dataBytes: Long,
        target: File,
        stop: () -> Boolean = { false },
        onProgress: (Long) -> Unit
    ): Boolean {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * 4)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var started = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            BufferedInputStream(FileInputStream(source), 64 * 1024).use { input ->
                skip(input, dataOffset)
                val frame = ByteArray(FRAME_BYTES)
                val info = MediaCodec.BufferInfo()
                var track = -1
                var read = 0L
                var presentationUs = 0L
                var inputDone = false
                var done = false
                while (!done) {
                    if (stop()) return false
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear()
                            val want = minOf(FRAME_BYTES.toLong(), dataBytes - read).toInt()
                            val filled = if (want <= 0) 0 else fill(input, frame, want)
                            if (filled <= 0) {
                                codec.queueInputBuffer(
                                    index,
                                    0,
                                    0,
                                    presentationUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                // A whole frame every time: the tail of the recording is padded with
                                // silence rather than handed to the encoder as a part-frame it may refuse.
                                if (filled < FRAME_BYTES) frame.fill(0, filled, FRAME_BYTES)
                                buffer.put(frame, 0, FRAME_BYTES)
                                codec.queueInputBuffer(index, 0, FRAME_BYTES, presentationUs, 0)
                                read += filled
                                presentationUs += FRAME_US
                                onProgress(read / (SAMPLE_RATE * 2 / 1000))
                            }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // The OGG stream's own header (OpusHead) rides in this format, so the track
                            // cannot be added before it arrives.
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (index >= 0) {
                            val out = codec.getOutputBuffer(index)
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (out != null && info.size > 0 && started && !isConfig) {
                                muxer.writeSampleData(track, out, info)
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                        }
                    }
                }
            }
            return true
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun skip(input: BufferedInputStream, bytes: Long) {
        var skipped = 0L
        while (skipped < bytes) {
            val step = input.skip(bytes - skipped)
            if (step <= 0) return
            skipped += step
        }
    }

    private fun fill(input: BufferedInputStream, frame: ByteArray, want: Int): Int {
        var filled = 0
        while (filled < want) {
            val read = input.read(frame, filled, want - filled)
            if (read <= 0) break
            filled += read
        }
        return filled
    }
}
