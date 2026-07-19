package com.urik.keyboard.service.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.konovalov.vad.webrtc.Vad
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import com.urik.keyboard.utils.ErrorLogger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 16 kHz mono recorder for Whisper voice input, re-derived in spirit from the whisperIMEplus
 * recorder (the compiled submodule engine subset is UI-free and has no recorder).
 *
 * Two modes:
 * - **Single utterance** (chunkMode false): records until [stop], VAD end-of-speech (when
 *   [useVad]) or the 30 s Whisper window; delivers the whole utterance via [onFinished].
 * - **Continuous dictation** (chunkMode true, needs VAD): each VAD end-of-utterance emits that
 *   chunk via [onChunk] and recording CONTINUES — transcription runs in parallel, so nothing
 *   said during a decode is lost. The session ends on [stop], after [sessionEndMs] with no
 *   speech, or at the 5 min hard cap; [onFinished] (always null samples) closes it.
 *
 * Samples are peak-normalised floats — the exact input format of the ONNX recognizer. Callbacks
 * fire on the recording thread.
 */
class VoiceRecorder(
    private val useVad: Boolean,
    private val silenceDurationMs: Int,
    private val chunkMode: Boolean = false,
    private val sessionEndMs: Int = 0,
    private val onSpeechStart: () -> Unit = {},
    private val onChunk: (FloatArray) -> Unit = {},
    private val onFinished: (FloatArray?) -> Unit
) {
    private val inProgress = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** Caller must hold RECORD_AUDIO — the IME checks before starting. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!inProgress.compareAndSet(false, true)) return
        Thread({ recordLoop() }, "kxkb-voice-recorder").start()
    }

    /** Finish: single mode transcribes the utterance; chunk mode emits the tail chunk and ends. */
    fun stop() {
        inProgress.set(false)
    }

    /** Discard everything (keyboard hidden, field changed). */
    fun cancel() {
        cancelled.set(true)
        inProgress.set(false)
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        var vad: VadWebRTC? = null
        var audioRecord: AudioRecord? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, VAD_FRAME_BYTES))
                .build()
            if (useVad) {
                vad = Vad.builder()
                    .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                    .setFrameSize(FrameSize.FRAME_SIZE_480)
                    .setMode(Mode.VERY_AGGRESSIVE)
                    .setSilenceDurationMs(silenceDurationMs)
                    .setSpeechDurationMs(SPEECH_MIN_MS)
                    .build()
            }
            audioRecord.startRecording()

            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(VAD_FRAME_BYTES)
            val vadFrame = ByteArray(VAD_FRAME_BYTES)
            var speechInBuffer = false
            var announcedStart = false
            var noSpeechMs = 0
            var sessionBytes = 0

            while (inProgress.get()) {
                val read = audioRecord.read(chunk, 0, VAD_FRAME_BYTES)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
                sessionBytes += read

                val speaking = if (vad != null) {
                    val bytes = buffer.toByteArray()
                    if (bytes.size >= VAD_FRAME_BYTES) {
                        System.arraycopy(bytes, bytes.size - VAD_FRAME_BYTES, vadFrame, 0, VAD_FRAME_BYTES)
                        vad.isSpeech(vadFrame)
                    } else {
                        false
                    }
                } else {
                    true
                }

                if (speaking) {
                    noSpeechMs = 0
                    if (!announcedStart) {
                        announcedStart = true
                        onSpeechStart()
                    }
                    speechInBuffer = true
                } else {
                    noSpeechMs += read / BYTES_PER_MS
                    if (speechInBuffer) {
                        // End of an utterance: the VAD's silence window elapsed after speech.
                        if (chunkMode) {
                            emitChunk(buffer)
                            speechInBuffer = false
                        } else {
                            inProgress.set(false)
                        }
                    }
                }

                if (chunkMode) {
                    // A chunk must fit Whisper's 30 s window — force-emit a run-on utterance.
                    if (buffer.size() >= CHUNK_CAP_BYTES && speechInBuffer) {
                        emitChunk(buffer)
                        speechInBuffer = false
                    }
                    // Nobody has spoken for the session-end period, or the session hard cap hit.
                    if (noSpeechMs >= sessionEndMs || sessionBytes >= SESSION_MAX_BYTES) {
                        inProgress.set(false)
                    }
                } else if (buffer.size() >= MAX_BYTES) {
                    inProgress.set(false)
                }
            }

            audioRecord.stop()
            if (chunkMode) {
                // The tail: whatever was being said when the session was stopped by hand.
                if (!cancelled.get() && speechInBuffer) emitChunk(buffer)
                onFinished(null)
            } else {
                val bytes = buffer.toByteArray()
                if (cancelled.get() || bytes.size < MIN_BYTES) {
                    onFinished(null)
                } else {
                    onFinished(toNormalisedSamples(bytes))
                }
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "VoiceRecorder",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "recordLoop", "chunkMode" to chunkMode.toString())
            )
            onFinished(null)
        } finally {
            inProgress.set(false)
            vad?.close()
            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun emitChunk(buffer: ByteArrayOutputStream) {
        val bytes = buffer.toByteArray()
        buffer.reset()
        if (bytes.size >= MIN_BYTES && !cancelled.get()) {
            onChunk(toNormalisedSamples(bytes))
        }
    }

    /** PCM16 → float [-1, 1], peak-normalised — the recognizer's expected input. */
    private fun toNormalisedSamples(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val samples = FloatArray(bytes.size / 2)
        var maxAbs = 0f
        for (i in samples.indices) {
            samples[i] = buffer.short / 32768.0f
            if (kotlin.math.abs(samples[i]) > maxAbs) maxAbs = kotlin.math.abs(samples[i])
        }
        if (maxAbs > 0f) {
            for (i in samples.indices) samples[i] /= maxAbs
        }
        return samples
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val VAD_FRAME_SAMPLES = 480
        private const val VAD_FRAME_BYTES = VAD_FRAME_SAMPLES * 2
        private const val SPEECH_MIN_MS = 200
        private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000

        /** Whisper's 30 s window: 16 kHz × 2 bytes × 30 s. */
        private const val MAX_BYTES = SAMPLE_RATE * 2 * 30

        /** Chunk-mode per-utterance cap, safely inside the window. */
        private const val CHUNK_CAP_BYTES = SAMPLE_RATE * 2 * 28

        /** Continuous-session hard cap. */
        private const val SESSION_MAX_BYTES = SAMPLE_RATE * 2 * 300

        /** 0.2 s minimum — anything shorter is a stray tap, not an utterance. */
        private const val MIN_BYTES = 6400
    }
}
