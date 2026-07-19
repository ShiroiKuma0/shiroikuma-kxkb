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
 * One-shot 16 kHz mono utterance recorder for Whisper voice input, re-derived in spirit from the
 * whisperIMEplus recorder (the compiled submodule engine subset is UI-free and has no recorder).
 *
 * Records until [stop], until the WebRTC VAD hears end-of-speech (when [useVad]), or until the
 * 30 s Whisper window fills. Delivers peak-normalised float samples — the exact input format of
 * the ONNX recognizer — via [onFinished] on the recording thread; null means cancelled, failed,
 * or shorter than the 0.2 s minimum.
 */
class VoiceRecorder(
    private val useVad: Boolean,
    private val silenceDurationMs: Int,
    private val onSpeechStart: () -> Unit,
    private val onFinished: (FloatArray?) -> Unit
) {
    private val inProgress = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** Caller must hold RECORD_AUDIO — the IME checks before starting. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!inProgress.compareAndSet(false, true)) return
        Thread({ recordUtterance() }, "kxkb-voice-recorder").start()
    }

    /** Finish the utterance: hand what was recorded to the recognizer. */
    fun stop() {
        inProgress.set(false)
    }

    /** Discard the utterance (keyboard hidden, field changed). */
    fun cancel() {
        cancelled.set(true)
        inProgress.set(false)
    }

    @SuppressLint("MissingPermission")
    private fun recordUtterance() {
        var vad: VadWebRTC? = null
        var audioRecord: AudioRecord? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuffer, VAD_FRAME_BYTES)
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
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

            val output = ByteArrayOutputStream()
            val chunk = ByteArray(VAD_FRAME_BYTES)
            val vadFrame = ByteArray(VAD_FRAME_BYTES)
            var totalBytes = 0
            var speechHeard = false
            var announcedStart = false

            while (inProgress.get() && totalBytes < MAX_BYTES) {
                val read = audioRecord.read(chunk, 0, VAD_FRAME_BYTES)
                if (read <= 0) break
                output.write(chunk, 0, read)
                totalBytes += read

                if (vad != null) {
                    val bytes = output.toByteArray()
                    if (bytes.size >= VAD_FRAME_BYTES) {
                        System.arraycopy(bytes, bytes.size - VAD_FRAME_BYTES, vadFrame, 0, VAD_FRAME_BYTES)
                        if (vad.isSpeech(vadFrame)) {
                            if (!announcedStart) {
                                announcedStart = true
                                onSpeechStart()
                            }
                            speechHeard = true
                        } else if (speechHeard) {
                            // End of the utterance: the VAD's silence window elapsed after speech.
                            inProgress.set(false)
                        }
                    }
                } else if (!announcedStart) {
                    announcedStart = true
                    onSpeechStart()
                }
            }

            audioRecord.stop()
            val bytes = output.toByteArray()
            if (cancelled.get() || bytes.size < MIN_BYTES) {
                onFinished(null)
            } else {
                onFinished(toNormalisedSamples(bytes))
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "VoiceRecorder",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "recordUtterance")
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

        /** Whisper's 30 s window: 16 kHz × 2 bytes × 30 s. */
        private const val MAX_BYTES = SAMPLE_RATE * 2 * 30

        /** 0.2 s minimum — anything shorter is a stray tap, not an utterance. */
        private const val MIN_BYTES = 6400
    }
}
