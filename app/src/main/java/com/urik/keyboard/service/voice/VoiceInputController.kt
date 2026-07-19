package com.urik.keyboard.service.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.utils.ErrorLogger
import com.whisperonnx.voice_translation.neural_networks.NeuralNetworkApi
import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer
import com.whisperonnx.voice_translation.neural_networks.voice.RecognizerListener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orchestrates the Whisper voice-input flow: mic key → [VoiceRecorder] utterance → ONNX
 * [Recognizer] (the whisperIMEplus engine subset) → recognized text back to the IME.
 *
 * Replaces the submodule's UI-entangled `Whisper`/`Recorder` layer in Kotlin. The recognizer is
 * loaded lazily on the first mic press — in parallel with the first recording, so the model-load
 * seconds overlap the user speaking — and stays resident afterwards (idle unload comes with the
 * fork-side session-release patch).
 *
 * Voice language follows the keyboard: the active layout language is passed per recognize() call,
 * so the space-slide language switch IS the dictation-language switch. GNU layouts are
 * language-neutral and instead use the 2-language fast-switch pair (en ⇄ cs via [flipGnuLanguage],
 * persisted as [KeyboardSettings.voiceGnuLanguage]).
 */
@Singleton
class VoiceInputController
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val languageManager: LanguageManager
) {
    enum class State { IDLE, LISTENING, TRANSCRIBING }

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

    /** Utterance waiting for a recognizer that is still loading. */
    private var pendingSamples: FloatArray? = null
    private var requestedLanguage = "en"
    private var requestedAction = Recognizer.ACTION_TRANSCRIBE

    /** Bumped on [cancel]; results carrying a stale generation are dropped, never committed. */
    private var generation = 0

    /** Unloads the resident ONNX sessions after an idle period (armed on every return to IDLE). */
    private val unloadRunnable = Runnable { unloadRecognizer() }

    /** Safety net: no state may silently stick — a wedged LISTENING/TRANSCRIBING self-heals to IDLE. */
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

    /** Start recording an utterance. No-op unless idle. */
    fun startListening(settings: KeyboardSettings, listener: Listener) {
        if (_state.value != State.IDLE) return
        mainHandler.removeCallbacks(unloadRunnable)
        this.listener = listener
        requestedLanguage = resolveLanguage(settings)
        requestedAction =
            if (settings.voiceTranslate) Recognizer.ACTION_TRANSLATE else Recognizer.ACTION_TRANSCRIBE
        val myGeneration = generation
        setState(State.LISTENING)
        ensureRecognizer()
        recorder = VoiceRecorder(
            useVad = settings.voiceAutoStop,
            silenceDurationMs = settings.voiceSilenceMs,
            onSpeechStart = {},
            onFinished = { samples -> mainHandler.post { onUtteranceFinished(samples, myGeneration) } }
        ).also { it.start() }
    }

    /** Mic key pressed again while listening: finish the utterance and transcribe it. */
    fun finishListening() {
        recorder?.stop()
    }

    /** Keyboard hidden / field gone: discard recording and never commit an in-flight result. */
    fun cancel() {
        generation++
        recorder?.cancel()
        recorder = null
        pendingSamples = null
        if (_state.value != State.IDLE) setState(State.IDLE)
    }

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
        if (recognizerReady) {
            recognize(samples)
        } else {
            pendingSamples = samples
        }
    }

    private fun recognize(samples: FloatArray) {
        recognizer?.recognize(samples, BEAM_SIZE, requestedLanguage, requestedAction)
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
                        pendingSamples?.let { samples ->
                            pendingSamples = null
                            if (_state.value == State.TRANSCRIBING) recognize(samples)
                        }
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
                        pendingSamples = null
                        if (_state.value != State.IDLE) {
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
                    mainHandler.post {
                        if (_state.value != State.IDLE) setState(State.IDLE)
                        listener?.onError()
                    }
                }
            })
        }
    }

    private fun onRecognized(text: String?, languageCode: String?, myGeneration: Int) {
        if (myGeneration != generation) return
        if (_state.value != State.TRANSCRIBING) return
        setState(State.IDLE)
        val cleaned = text?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned == Recognizer.UNDEFINED_TEXT) {
            listener?.onError()
            return
        }
        listener?.onResult(cleaned, languageCode)
    }

    /** IME service teardown: stop everything and free the model's native memory immediately. */
    fun shutdown() {
        cancel()
        mainHandler.removeCallbacks(unloadRunnable)
        unloadRecognizer()
    }

    private fun unloadRecognizer() {
        if (_state.value != State.IDLE) return
        recognizer?.destroy()
        recognizer = null
        recognizerReady = false
        recognizerFailed = false
        pendingSamples = null
    }

    private fun onStateTimeout() {
        ErrorLogger.logException(
            component = "VoiceInputController",
            severity = ErrorLogger.Severity.HIGH,
            exception = RuntimeException("Voice state watchdog fired"),
            context = mapOf("state" to _state.value.name)
        )
        when (_state.value) {
            State.LISTENING -> {
                recorder?.cancel()
                recorder = null
                setState(State.IDLE)
                listener?.onError()
            }
            State.TRANSCRIBING -> {
                pendingSamples = null
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
        // No state may silently stick: the recorder hard-caps at 30 s and a decode of a 30 s
        // utterance stays well under that — anything longer is a wedge, self-heal it.
        mainHandler.removeCallbacks(stateWatchdog)
        when (state) {
            State.LISTENING -> mainHandler.postDelayed(stateWatchdog, LISTENING_TIMEOUT_MS)
            State.TRANSCRIBING -> mainHandler.postDelayed(stateWatchdog, TRANSCRIBING_TIMEOUT_MS)
            State.IDLE -> Unit
        }
    }

    companion object {
        private const val BEAM_SIZE = 1
        private const val UNLOAD_AFTER_IDLE_MS = 60_000L
        private const val LISTENING_TIMEOUT_MS = 40_000L
        private const val TRANSCRIBING_TIMEOUT_MS = 45_000L
    }
}
