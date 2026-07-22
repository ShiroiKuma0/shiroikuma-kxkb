package com.urik.keyboard.service.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.utils.ErrorLogger
import com.whisperonnx.voice_translation.neural_networks.NeuralNetworkApi
import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer
import com.whisperonnx.voice_translation.neural_networks.voice.RecognizerListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orchestrates the Whisper voice-input flow: mic key → [VoiceRecorder] → ONNX [Recognizer] (the
 * whisperIMEplus engine subset) → recognized text back to the IME.
 *
 * Two shapes:
 * - **Single utterance** (LISTENING → TRANSCRIBING → IDLE): one press, one sentence, one commit.
 * - **Continuous dictation** (DICTATING → … → IDLE): the recorder emits a chunk at every VAD
 *   end-of-utterance and KEEPS recording while the engine decodes in parallel (its internal
 *   queue is serial, so commits stay in order). The session ends on a mic tap, after the
 *   configured no-speech period, or at the recorder's hard cap; a TRANSCRIBING tail drains any
 *   still-decoding chunks.
 *
 * The recognizer loads lazily on the first press (overlapping the user speaking), stays resident
 * while used, and unloads 60 s after going idle. Watchdogs guarantee no state can silently stick.
 *
 * Voice language: the active layout language per session (GNU counts as English); the long-press
 * flip switches to the pair's other language — see [resolveLanguage].
 */
@Singleton
class VoiceInputController
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val languageManager: LanguageManager
) {
    enum class State { IDLE, LISTENING, DICTATING, TRANSCRIBING }

    interface Listener {
        fun onStateChanged(state: State)

        fun onResult(text: String, languageCode: String?)

        fun onError()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private var listener: Listener? = null
    private var recorder: VoiceRecorder? = null
    private var recognizer: Recognizer? = null
    private var recognizerReady = false
    private var recognizerFailed = false

    /** Chunks waiting for a recognizer that is still loading (kept in speech order). */
    private val pendingChunks = ArrayDeque<FloatArray>()

    /** Chunks handed to the engine whose results have not come back yet. */
    private var chunksInFlight = 0

    private var continuousActive = false
    private var stopRequested = false
    private var beepsEnabled = false
    private var beepPcm: ShortArray? = null
    private var requestedLanguage = "en"
    private var requestedAction = Recognizer.ACTION_TRANSCRIBE

    /** Bumped on [cancel]; results carrying a stale generation are dropped, never committed. */
    private var generation = 0

    /** Unloads the resident ONNX sessions after an idle period (armed on every return to IDLE). */
    private val unloadRunnable = Runnable { unloadRecognizer() }

    /** Safety net: no state may silently stick — a wedged state self-heals to IDLE. */
    private val stateWatchdog = Runnable { onStateTimeout() }

    fun isModelInstalled(): Boolean = VoiceModelStore.isInstalled(context)

    /**
     * The Whisper language code the next utterance will be transcribed as. Primary = the layout
     * language (GNU counts as English); the long-press flip switches to the pair's other language —
     * English, or Czech when the primary already is English.
     */
    fun resolveLanguage(settings: KeyboardSettings): String {
        if (settings.voiceAutoDetect) return "auto"
        val layoutLang = languageManager.currentLayoutLanguage.value.substringBefore("-")
        val primary = if (layoutLang == "gnu") "en" else layoutLang
        val alternate = if (primary == "en") "cs" else "en"
        return if (settings.voiceUseAlternate) alternate else primary
    }

    /** Start recording. No-op unless idle. */
    fun startListening(settings: KeyboardSettings, listener: Listener) {
        if (_state.value != State.IDLE) return
        mainHandler.removeCallbacks(unloadRunnable)
        this.listener = listener
        requestedLanguage = resolveLanguage(settings)
        requestedAction =
            if (settings.voiceTranslate) Recognizer.ACTION_TRANSLATE else Recognizer.ACTION_TRANSCRIBE
        continuousActive = settings.voiceContinuous && settings.voiceAutoStop
        stopRequested = false
        beepsEnabled = settings.voiceBeeps
        val myGeneration = generation
        ensureRecognizer()
        if (continuousActive) {
            setState(State.DICTATING)
            recorder = VoiceRecorder(
                useVad = true,
                silenceDurationMs = settings.voiceSilenceMs,
                chunkMode = true,
                sessionEndMs = settings.voiceSessionEndMs,
                onChunk = { samples -> mainHandler.post { onChunkReady(samples, myGeneration) } },
                onFinished = { mainHandler.post { onSessionRecorderDone(myGeneration) } }
            ).also { it.start() }
        } else {
            setState(State.LISTENING)
            recorder = VoiceRecorder(
                useVad = settings.voiceAutoStop,
                silenceDurationMs = settings.voiceSilenceMs,
                onFinished = { samples -> mainHandler.post { onUtteranceFinished(samples, myGeneration) } }
            ).also { it.start() }
        }
    }

    /** Mic key pressed while active: single mode transcribes; continuous mode ends the session. */
    fun finishListening() {
        if (continuousActive) stopRequested = true
        recorder?.stop()
    }

    /** Keyboard hidden / field gone: discard recording and never commit an in-flight result. */
    fun cancel() {
        generation++
        recorder?.cancel()
        recorder = null
        pendingChunks.clear()
        chunksInFlight = 0
        continuousActive = false
        if (_state.value != State.IDLE) setState(State.IDLE)
    }

    /** IME service teardown: stop everything and free the model's native memory immediately. */
    fun shutdown() {
        cancel()
        mainHandler.removeCallbacks(unloadRunnable)
        unloadRecognizer()
    }

    // ---- single-utterance flow -------------------------------------------------------------------------

    private fun onUtteranceFinished(samples: FloatArray?, myGeneration: Int) {
        recorder = null
        if (myGeneration != generation) return
        if (samples == null) {
            setState(State.IDLE)
            return
        }
        setState(State.TRANSCRIBING)
        if (recognizerFailed) {
            setState(State.IDLE)
            listener?.onError()
            return
        }
        chunksInFlight++
        if (recognizerReady) {
            recognize(samples)
        } else {
            chunksInFlight--
            pendingChunks.add(samples)
        }
    }

    // ---- continuous-dictation flow ---------------------------------------------------------------------

    private fun onChunkReady(samples: FloatArray, myGeneration: Int) {
        if (myGeneration != generation) return
        if (_state.value != State.DICTATING) return
        // The sentence is captured — one beep says "keep talking". The tail chunk of a manual
        // stop skips it (the session-end triple follows right away).
        if (!stopRequested) playBeeps(1)
        if (recognizerFailed) {
            listener?.onError()
            return
        }
        if (recognizerReady) {
            chunksInFlight++
            recognize(samples)
        } else {
            pendingChunks.add(samples)
        }
    }

    private fun onSessionRecorderDone(myGeneration: Int) {
        recorder = null
        if (myGeneration != generation) return
        if (_state.value != State.DICTATING) return
        if (chunksInFlight == 0 && pendingChunks.isEmpty()) {
            finishSession()
        } else {
            // Drain the still-decoding tail before going idle.
            setState(State.TRANSCRIBING)
        }
    }

    private fun finishSession() {
        val wasContinuous = continuousActive
        continuousActive = false
        setState(State.IDLE)
        if (wasContinuous) playBeeps(3)
    }

    /** Short beeps, 80 ms each — under the VAD's 200 ms speech threshold, so no phantom chunk. */
    private fun playBeeps(count: Int) {
        if (!beepsEnabled) return
        repeat(count) { i -> mainHandler.postDelayed({ playOneBeep() }, i * BEEP_SPACING_MS) }
    }

    /**
     * A generated sine blip on an [AudioTrack] with explicit USAGE_MEDIA attributes — proper media
     * routing and volume. (ToneGenerator's stream request is rerouted by some OEMs, EMUI included.)
     */
    private fun playOneBeep() {
        try {
            val pcm = beepPcm ?: buildBeepPcm().also { beepPcm = it }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(BEEP_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            mainHandler.postDelayed({
                try {
                    track.release()
                } catch (_: Exception) {
                }
            }, BEEP_MS + 120L)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "VoiceInputController",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "playOneBeep")
            )
        }
    }

    /** 1 kHz sine, 5 ms fade ramps against clicks, moderate level (media volume does the rest). */
    private fun buildBeepPcm(): ShortArray {
        val n = BEEP_SAMPLE_RATE * BEEP_MS / 1000
        val ramp = BEEP_SAMPLE_RATE * 5 / 1000
        return ShortArray(n) { i ->
            val envelope = when {
                i < ramp -> i / ramp.toFloat()
                i > n - ramp -> (n - i) / ramp.toFloat()
                else -> 1f
            }
            (kotlin.math.sin(2.0 * Math.PI * BEEP_HZ * i / BEEP_SAMPLE_RATE) * envelope * 0.6 * Short.MAX_VALUE)
                .toInt().toShort()
        }
    }

    // ---- shared ----------------------------------------------------------------------------------------

    private fun recognize(samples: FloatArray) {
        recognizer?.recognize(samples, BEAM_SIZE, requestedLanguage, requestedAction)
    }

    private fun flushPendingChunks() {
        while (pendingChunks.isNotEmpty()) {
            chunksInFlight++
            recognize(pendingChunks.poll())
        }
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizerFailed = false
        recognizer = Recognizer(
            context,
            false,
            object : NeuralNetworkApi.InitListener {
                override fun onInitializationFinished() {
                    mainHandler.post {
                        recognizerReady = true
                        if (_state.value != State.IDLE) flushPendingChunks()
                    }
                }

                override fun onError(reasons: IntArray?, value: Long) {
                    ErrorLogger.logException(
                        component = "VoiceInputController",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = RuntimeException("Recognizer init failed"),
                        context = mapOf("reasons" to (reasons?.joinToString() ?: "?"))
                    )
                    mainHandler.post {
                        recognizerFailed = true
                        recognizer = null
                        pendingChunks.clear()
                        chunksInFlight = 0
                        recorder?.cancel()
                        recorder = null
                        if (_state.value != State.IDLE) {
                            continuousActive = false
                            setState(State.IDLE)
                            listener?.onError()
                        }
                    }
                }
            }
        ).also { r ->
            r.addCallback(object : RecognizerListener {
                override fun onSpeechRecognizedResult(
                    text: String?,
                    languageCode: String?,
                    confidenceScore: Double,
                    isFinal: Boolean
                ) {
                    val myGeneration = generation
                    mainHandler.post { onRecognized(text, languageCode, myGeneration) }
                }

                override fun onError(reasons: IntArray?, value: Long) {
                    ErrorLogger.logException(
                        component = "VoiceInputController",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = RuntimeException("Recognition failed"),
                        context = mapOf("reasons" to (reasons?.joinToString() ?: "?"))
                    )
                    mainHandler.post { onChunkFailed() }
                }
            })
        }
    }

    private fun onRecognized(text: String?, languageCode: String?, myGeneration: Int) {
        if (myGeneration != generation) return
        if (_state.value == State.IDLE) return
        chunksInFlight = (chunksInFlight - 1).coerceAtLeast(0)
        val cleaned = text?.trim().orEmpty()
        if (cleaned.isNotEmpty() && cleaned != Recognizer.UNDEFINED_TEXT) {
            listener?.onResult(cleaned, languageCode)
        } else if (!continuousActive) {
            listener?.onError()
        }
        maybeFinishAfterDecode()
    }

    private fun onChunkFailed() {
        if (_state.value == State.IDLE) return
        chunksInFlight = (chunksInFlight - 1).coerceAtLeast(0)
        listener?.onError()
        maybeFinishAfterDecode()
    }

    /** After each decode: a single utterance is done; a continuous tail ends when drained. */
    private fun maybeFinishAfterDecode() {
        if (_state.value == State.TRANSCRIBING && chunksInFlight == 0 && pendingChunks.isEmpty()) {
            finishSession()
        }
    }

    private fun unloadRecognizer() {
        if (_state.value != State.IDLE) return
        recognizer?.destroy()
        recognizer = null
        recognizerReady = false
        recognizerFailed = false
        pendingChunks.clear()
        chunksInFlight = 0
    }

    private fun onStateTimeout() {
        ErrorLogger.logException(
            component = "VoiceInputController",
            severity = ErrorLogger.Severity.HIGH,
            exception = RuntimeException("Voice state watchdog fired"),
            context = mapOf("state" to _state.value.name, "inFlight" to chunksInFlight.toString())
        )
        when (_state.value) {
            State.LISTENING, State.DICTATING -> {
                recorder?.cancel()
                recorder = null
                pendingChunks.clear()
                chunksInFlight = 0
                continuousActive = false
                setState(State.IDLE)
                listener?.onError()
            }
            State.TRANSCRIBING -> {
                pendingChunks.clear()
                chunksInFlight = 0
                continuousActive = false
                setState(State.IDLE)
                listener?.onError()
            }
            State.IDLE -> Unit
        }
    }

    private fun setState(state: State) {
        _state.value = state
        listener?.onStateChanged(state)
        // The resident sessions hold hundreds of MB of native memory — drop them after an idle
        // minute; the next mic press reloads (the load overlaps the user speaking).
        mainHandler.removeCallbacks(unloadRunnable)
        if (state == State.IDLE) mainHandler.postDelayed(unloadRunnable, UNLOAD_AFTER_IDLE_MS)
        // No state may silently stick: the single-mode recorder hard-caps at 30 s, a dictation
        // session at 5 min, and a decode tail stays well under its bound — anything longer is a
        // wedge, self-heal it.
        mainHandler.removeCallbacks(stateWatchdog)
        when (state) {
            State.LISTENING -> mainHandler.postDelayed(stateWatchdog, LISTENING_TIMEOUT_MS)
            State.DICTATING -> mainHandler.postDelayed(stateWatchdog, DICTATING_TIMEOUT_MS)
            State.TRANSCRIBING -> mainHandler.postDelayed(stateWatchdog, TRANSCRIBING_TIMEOUT_MS)
            State.IDLE -> Unit
        }
    }

    companion object {
        private const val BEAM_SIZE = 1
        private const val UNLOAD_AFTER_IDLE_MS = 60_000L
        private const val LISTENING_TIMEOUT_MS = 40_000L
        private const val DICTATING_TIMEOUT_MS = 6 * 60_000L
        private const val TRANSCRIBING_TIMEOUT_MS = 45_000L
        private const val BEEP_SAMPLE_RATE = 22050
        private const val BEEP_HZ = 1000
        private const val BEEP_MS = 80
        private const val BEEP_SPACING_MS = 160L
    }
}
