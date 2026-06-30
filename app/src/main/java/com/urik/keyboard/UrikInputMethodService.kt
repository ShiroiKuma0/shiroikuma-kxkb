package com.urik.keyboard

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.content.IntentFilter
import android.icu.lang.UScript
import android.icu.util.ULocale
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.LinearLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.VisibleForTesting
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.urik.keyboard.KeyboardConstants.AutofillConstants.MAX_PASSWORD_INLINE_SUGGESTIONS
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.model.KeyboardEvent
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.AdaptiveDimensions
import com.urik.keyboard.service.AutoCorrectionEngine
import com.urik.keyboard.service.GeometryBucket
import com.urik.keyboard.service.geometryKey
import com.urik.keyboard.service.AutofillStateCoordinator
import com.urik.keyboard.service.AutofillStateTracker
import com.urik.keyboard.service.BackspaceHandler
import com.urik.keyboard.service.CandidateBarController
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.ClipboardActionCoordinator
import com.urik.keyboard.service.CustomSuggestionDefaults
import com.urik.keyboard.service.CustomSuggestionRow
import com.urik.keyboard.service.ClipboardMonitorService
import com.urik.keyboard.service.ClipboardPanelHost
import com.urik.keyboard.service.EmojiSearchManager
import com.urik.keyboard.service.ImeStateCoordinator
import com.urik.keyboard.service.InputFieldClassifier
import com.urik.keyboard.service.InputMethod
import com.urik.keyboard.service.InputStateManager
import com.urik.keyboard.service.JapaneseCandidateHandler
import com.urik.keyboard.service.KeyEventHandler
import com.urik.keyboard.service.KeyboardLookKnobs
import com.urik.keyboard.service.EnterActionPerformer
import com.urik.keyboard.service.KeyEventRouter
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.LetterInputHandler
import com.urik.keyboard.service.NonLetterInputHandler
import com.urik.keyboard.service.OnUpdateSelectionHandler
import com.urik.keyboard.service.DeadKeys
import com.urik.keyboard.service.OutputBridge
import com.urik.keyboard.service.SpaceInputHandler
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.SpecialTokens
import com.urik.keyboard.service.SuggestionPipeline
import com.urik.keyboard.service.SuggestionPipelineHost
import com.urik.keyboard.service.SwipeWordHandler
import com.urik.keyboard.service.TextInputProcessor
import com.urik.keyboard.service.ViewCallback
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.ui.keyboard.KeyboardViewModel
import com.urik.keyboard.ui.keyboard.components.ClipboardPanel
import com.urik.keyboard.ui.keyboard.components.KeyboardLayoutManager
import com.urik.keyboard.ui.keyboard.components.ResizeOverlayView
import com.urik.keyboard.ui.keyboard.components.ResizeValues
import com.urik.keyboard.ui.keyboard.components.SwipeDetector
import com.urik.keyboard.ui.keyboard.components.SwipeKeyboardView
import com.urik.keyboard.utils.BackspaceUtils
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.KanaTransformUtils
import com.urik.keyboard.utils.KeyboardModeUtils
import com.urik.keyboard.utils.isUserUnlocked
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Main input method service for the Urik keyboard.
 *
 * Delegates composing state to [InputStateManager], InputConnection operations
 * to [OutputBridge], and suggestion processing to [SuggestionPipeline].
 */
@Suppress("LargeClass")
@AndroidEntryPoint
open class UrikInputMethodService :
    InputMethodService(),
    LifecycleOwner,
    KeyEventHandler,
    ClipboardPanelHost,
    SuggestionPipelineHost {
    @Inject
    lateinit var repository: KeyboardRepository

    @Inject
    lateinit var swipeDetector: SwipeDetector

    @Inject
    lateinit var streamingScoringEngine: com.urik.keyboard.ui.keyboard.components.StreamingScoringEngine

    @Inject
    lateinit var languageManager: LanguageManager

    @Inject
    lateinit var wordLearningEngine: WordLearningEngine

    @Inject
    lateinit var wordFrequencyRepository: com.urik.keyboard.data.WordFrequencyRepository

    @Inject
    lateinit var cacheMemoryManager: CacheMemoryManager

    @Inject
    lateinit var characterVariationService: CharacterVariationService

    @Inject
    lateinit var spellCheckManager: SpellCheckManager

    @Inject
    lateinit var textInputProcessor: TextInputProcessor

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var themeManager: ThemeManager

    @Inject
    lateinit var clipboardRepository: com.urik.keyboard.data.ClipboardRepository

    @Inject
    lateinit var clipboardMonitorService: ClipboardMonitorService

    @Inject
    lateinit var emojiSearchManager: EmojiSearchManager

    @Inject
    lateinit var recentEmojiProvider: com.urik.keyboard.service.RecentEmojiProvider

    @Inject
    lateinit var customKeyMappingService: com.urik.keyboard.service.CustomKeyMappingService

    @Inject
    lateinit var keyboardModeManager: com.urik.keyboard.service.KeyboardModeManager

    @Inject
    lateinit var caseTransformer: com.urik.keyboard.utils.CaseTransformer

    @Inject
    lateinit var swipeSpaceManager: com.urik.keyboard.service.SwipeSpaceManager

    @Inject
    lateinit var scriptConverterRegistry: com.urik.keyboard.service.ScriptConverterRegistry

    @Inject
    lateinit var autoCorrectionEngine: AutoCorrectionEngine

    @Inject
    lateinit var keyEventRouter: KeyEventRouter

    private lateinit var viewModel: KeyboardViewModel
    private lateinit var layoutManager: KeyboardLayoutManager
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private var postureDetector: com.urik.keyboard.service.PostureDetector? = null

    @VisibleForTesting
    internal lateinit var inputState: InputStateManager

    @VisibleForTesting
    internal lateinit var outputBridge: OutputBridge

    @VisibleForTesting
    internal lateinit var suggestionPipeline: SuggestionPipeline

    private val inputMethodManager: android.view.inputmethod.InputMethodManager by lazy {
        getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    }

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val observerJobs = mutableListOf<Job>()

    private var swipeKeyboardView: SwipeKeyboardView? = null
    private var filteredLayoutCache: KeyboardLayout? = null
    private var filteredLayoutCacheKey: Pair<KeyboardLayout?, Boolean>? = null
    private var adaptiveContainer: com.urik.keyboard.ui.keyboard.components.AdaptiveKeyboardContainer? = null
    private var keyboardRootContainer: LinearLayout? = null
    // Cold-start window pre-size. The last good keyboard height per app|geometry, in device-protected storage so
    // it survives a process kill and reads synchronously (and before unlock). Read in onStartInputView to seed a
    // height FLOOR before the async grid build runs, so a slow cold build can't leave the window short (the clip).
    // The floor applies only while [coldBuildPending] (until the build settles), so resize-down still shrinks.
    private val windowHeightCache: SharedPreferences by lazy {
        createDeviceProtectedStorageContext().getSharedPreferences("kb_window_height", Context.MODE_PRIVATE)
    }
    private var presizeHeight: Int = -1
    private var coldBuildPending: Boolean = false
    private var clipboardPanel: ClipboardPanel? = null
    private var lastDisplayDensity: Float = 0f
    private var lastKeyboardConfig: Int = android.content.res.Configuration.KEYBOARD_UNDEFINED

    private var doubleShiftThreshold: Long = DOUBLE_SHIFT_THRESHOLD_MS

    private var currentSettings: KeyboardSettings = KeyboardSettings()

    private lateinit var autofillCoordinator: AutofillStateCoordinator
    private lateinit var clipboardCoordinator: ClipboardActionCoordinator

    private lateinit var candidateBarController: CandidateBarController
    private lateinit var imeStateCoordinator: ImeStateCoordinator
    private lateinit var onUpdateSelectionHandler: OnUpdateSelectionHandler
    private lateinit var japaneseCandidateHandler: JapaneseCandidateHandler
    private lateinit var letterInputHandler: LetterInputHandler
    private lateinit var nonLetterInputHandler: NonLetterInputHandler
    private lateinit var backspaceHandler: BackspaceHandler
    private lateinit var spaceInputHandler: SpaceInputHandler
    private lateinit var swipeWordHandler: SwipeWordHandler

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun showSuggestions(): Boolean = currentSettings.showSuggestions
    override fun effectiveSuggestionCount(): Int = currentSettings.effectiveSuggestionCount
    override fun getKeyboardState(): KeyboardState = viewModel.state.value
    override fun shouldAutoCapitalize(text: String): Boolean = viewModel.shouldAutoCapitalize(text)
    override fun currentLanguage(): String = languageManager.currentLanguage.value
    override fun currentLayoutLanguage(): String = languageManager.currentLayoutLanguage.value
    override fun japaneseRegisterLabel(): String = getString(R.string.ja_register_candidate_label)

    private fun setAcceleratedDeletion(active: Boolean) {
        inputState.isAcceleratedDeletion = active
    }

    private fun clearSecureFieldState() = imeStateCoordinator.clearSecureFieldState()

    private fun updateScriptContext(locale: ULocale) {
        val currentLayout = viewModel.layout.value
        val isRTL = currentLayout?.isRTL ?: false
        val scriptCode =
            currentLayout?.script?.let { scriptStr ->
                when (scriptStr) {
                    "Arab" -> UScript.ARABIC
                    "Cyrl" -> UScript.CYRILLIC
                    "Latn" -> UScript.LATIN
                    else -> UScript.LATIN
                }
            } ?: UScript.LATIN

        layoutManager.updateScriptContext()
        swipeDetector.updateScriptContext(locale, isRTL, scriptCode)
        textInputProcessor.updateScriptContext(locale, scriptCode)
        spellCheckManager.clearCaches()
    }

    private fun coordinateStateClear() {
        if (::japaneseCandidateHandler.isInitialized) japaneseCandidateHandler.reset()
        imeStateCoordinator.coordinateStateClear()
    }

    private fun invalidateComposingStateOnCursorJump() = imeStateCoordinator.invalidateComposingStateOnCursorJump()

    private fun checkAutoCapitalization(textBefore: String) {
        // Code / no-predict fields suppress auto-capitalization along with suggestions and
        // autocorrect, so typed text is left exactly as entered.
        if (inputState.isSuggestionsDisabled) return
        // Japanese layouts must NEVER auto-capitalise: on Japanese, Shift = katakana, so any auto-shift
        // (sentence start, after . ! ?, empty/field-start) would silently turn katakana ON — wrong. Katakana
        // engages only when the user taps Shift explicitly. This is the single choke point every auto-cap
        // trigger funnels through (onStartInput, onStartInputView, performInputAction, the suggestion-selection
        // and swipe/onUpdateSelection callbacks), so suppressing here covers them all.
        viewModel.checkAndApplyAutoCapitalization(
            textBefore,
            currentSettings.autoCapitalizationEnabled,
            suppressAutoShift = suggestionPipeline.isJapaneseLayout
        )
    }

    private fun sendCharacterAsKeyEvents(char: String) {
        val ic = currentInputConnection ?: return
        val events = KeyCharacterMap.load(
            KeyCharacterMap.VIRTUAL_KEYBOARD
        ).getEvents(char.toCharArray())
        if (events != null) {
            val softKeyFlags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            for (event in events) {
                ic.sendKeyEvent(
                    KeyEvent(
                        event.downTime,
                        event.eventTime,
                        event.action,
                        event.keyCode,
                        event.repeatCount,
                        event.metaState,
                        event.deviceId,
                        event.scanCode,
                        event.flags or softKeyFlags
                    )
                )
            }
        } else {
            ic.commitText(char, 1)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Before first unlock (Direct Boot), arrange to restart the process the instant the user unlocks, so
        // the IME comes back fully featured (real storage/prediction). Registered first, robustly, so it works
        // even if the init below degrades.
        if (!isUserUnlocked) registerUnlockRestart()
        try {
            lifecycleRegistry = LifecycleRegistry(this)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED

            lastDisplayDensity = resources.displayMetrics.density
            swipeDetector.updateDisplayMetrics(lastDisplayDensity)

            // Seed the look knobs from the last cold-start cache BEFORE any view is built, so the first
            // render after an app update / process restart is already the right size (see the helper).
            seedLookKnobsFromCache()

            postureDetector = com.urik.keyboard.service.PostureDetector(this, serviceScope).also {
                it.start()
                keyboardModeManager.initialize(serviceScope, it)
            }

            initializeCoreComponents()

            serviceScope.launch {
                try {
                    initializeServices()
                } catch (e: Throwable) {
                    ErrorLogger.logException(
                        component = "UrikInputMethodService",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("phase" to "initializeServices", "locked" to (!isUserUnlocked).toString())
                    )
                }
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "onCreate", "locked" to (!isUserUnlocked).toString())
            )
            // On the lock screen (BFU) a crash here could brick PIN entry — degrade and let the system fall
            // back to another IME, never die. When unlocked, keep failing loud (a real bug).
            if (isUserUnlocked) throw e
        }
    }

    private var unlockReceiver: BroadcastReceiver? = null

    /** When the user unlocks, end this (Direct-Boot) process so the IME restarts with real storage. */
    private fun registerUnlockRestart() {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    try {
                        unregisterReceiver(this)
                    } catch (_: Throwable) {
                    }
                    unlockReceiver = null
                    // Simplest robust path to a full re-init: end the process. The system recreates the IME on
                    // next use, now with unlocked storage; FUTO covers the brief gap.
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            unlockReceiver = receiver
        } catch (_: Throwable) {
            // Receiver setup must never crash the IME; without it the user just relaunches after unlock.
        }
    }

    private fun initializeCoreComponents() {
        try {
            viewModel = KeyboardViewModel(repository, languageManager)

            inputState =
                InputStateManager(
                    viewCallback =
                    object : ViewCallback {
                        override fun clearSuggestions() {
                            candidateBarController.clearSuggestions()
                        }

                        override fun updateSuggestions(suggestions: List<String>) {
                            candidateBarController.updateSuggestions(suggestions)
                        }

                        override fun showDegradedIndicator(degraded: Boolean) {
                            candidateBarController.showDegradedIndicator(degraded)
                        }

                        override fun setSelectedSuggestion(index: Int) {
                            candidateBarController.setSelectedSuggestion(index)
                        }
                    },
                    onShiftStateChanged = { pressed ->
                        viewModel.onEvent(KeyboardEvent.ShiftStateChanged(pressed))
                    },
                    isCapsLockOn = { viewModel.state.value.isCapsLockOn },
                    cancelDebounceJob = {
                        if (::suggestionPipeline.isInitialized) {
                            suggestionPipeline.cancelDebounceJob()
                        }
                    }
                )

            outputBridge =
                OutputBridge(
                    state = inputState,
                    swipeDetector = swipeDetector,
                    swipeSpaceManager = swipeSpaceManager,
                    icProvider = { currentInputConnection },
                    keyEventSender = { keyCode ->
                        val ic = currentInputConnection
                        if (ic != null) {
                            val now = SystemClock.uptimeMillis()
                            val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
                            ic.sendKeyEvent(
                                KeyEvent(
                                    now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags
                                )
                            )
                            ic.sendKeyEvent(
                                KeyEvent(
                                    now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
                                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags
                                )
                            )
                        }
                    },
                    keyCharEventSender = { char -> sendCharacterAsKeyEvents(char) }
                )

            candidateBarController = CandidateBarController(viewProvider = { swipeKeyboardView })

            imeStateCoordinator = ImeStateCoordinator(
                outputBridge = outputBridge,
                streamingScoringEngine = streamingScoringEngine,
                inputState = inputState,
                spellCheckManager = spellCheckManager,
                textInputProcessor = textInputProcessor,
                wordLearningEngine = wordLearningEngine
            )

            onUpdateSelectionHandler = OnUpdateSelectionHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                imeStateCoordinator = imeStateCoordinator,
                onCheckAutoCapitalization = ::checkAutoCapitalization
            )

            suggestionPipeline =
                SuggestionPipeline(
                    host = this,
                    state = inputState,
                    outputBridge = outputBridge,
                    textInputProcessor = textInputProcessor,
                    spellCheckManager = spellCheckManager,
                    wordLearningEngine = wordLearningEngine,
                    wordFrequencyRepository = wordFrequencyRepository,
                    languageManager = languageManager,
                    caseTransformer = caseTransformer,
                    scriptConverterRegistry = scriptConverterRegistry,
                    serviceScope = serviceScope
                )

            layoutManager =
                KeyboardLayoutManager(
                    context = this,
                    onKeyClick = { key ->
                        // Any non-cycle key ends an in-progress multitap cycle (cycle taps arrive via
                        // onCycleTap, not here, so this never clobbers the cycle itself).
                        resetCycle()
                        // In cluster typing, Space commits the highlighted next-word (bigram) candidate —
                        // so don't let the generic bigram-dismiss wipe pendingSuggestions before the space
                        // handler runs. Every other key (and non-cluster layouts) still dismisses them.
                        val preserveBigrams = inputState.clusterLayoutActive &&
                            key is KeyboardKey.Action && key.action == KeyboardKey.ActionType.SPACE
                        if (!preserveBigrams) inputState.clearBigramPredictions()
                        // A key (e.g. a macro key) whose text is a special-key shortcut — a cursor pair,
                        // [Paste], a date template — runs the shortcut instead of committing the raw text.
                        if (key is KeyboardKey.Character && SpecialTokens.isSpecial(key.value)) {
                            applySpecialToken(key.value)
                        } else {
                            keyEventRouter.route(key)
                        }
                    },
                    onAcceleratedDeletionChanged = { active -> setAcceleratedDeletion(active) },
                    onSymbolsLongPress = { handleClipboardButtonClick() },
                    onLanguageSwitch = { languageCode -> handleLanguageSwitch(languageCode) },
                    onSwitchToLayout = { lang, layoutId -> switchToLayout(lang, layoutId) },
                    onMenuAction = { action -> handleSpaceMenuAction(action) },
                    onClusterBands = { bands ->
                        spellCheckManager.setClusterBands(bands)
                        // A cluster layout enables Space-commits-candidate / Tab-advances + the bar highlight.
                        // Japanese uses the same model, so the highlight stays on for it too.
                        inputState.clusterLayoutActive = bands.isNotEmpty()
                        candidateBarController.setSuggestionSelectionEnabled(
                            bands.isNotEmpty() || suggestionPipeline.isJapaneseLayout
                        )
                    },
                    onSpaceLongPress = { handleSpaceLongPressLiteral() },
                    onShowInputMethodPicker = { showInputMethodPicker() },
                    onFlickBinding = { binding -> handleFlickBinding(binding) },
                    onCycleTap = { taps -> handleCycleTap(taps) },
                    characterVariationService = characterVariationService,
                    languageManager = languageManager,
                    themeManager = themeManager,
                    cacheMemoryManager = cacheMemoryManager
                )
            layoutManager.onDeleteWord = { handleBackspaceSwipeDelete() }

            japaneseCandidateHandler = JapaneseCandidateHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                onCommit = { suggestion -> handleSuggestionSelected(suggestion) },
                isRegisterAffordance = { suggestion ->
                    suggestionPipeline.isJapaneseRegisterAffordance(suggestion)
                }
            )
            letterInputHandler = LetterInputHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                suggestionPipeline = suggestionPipeline,
                swipeSpaceManager = swipeSpaceManager,
                onCoordinateStateClear = ::coordinateStateClear,
                onCheckAutoCapitalization = ::checkAutoCapitalization
            )
            nonLetterInputHandler = NonLetterInputHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                suggestionPipeline = suggestionPipeline,
                autoCorrectionEngine = autoCorrectionEngine,
                textInputProcessor = textInputProcessor,
                swipeSpaceManager = swipeSpaceManager,
                languageManager = languageManager,
                candidateBarController = candidateBarController,
                serviceScope = serviceScope,
                onGetCurrentSettings = { currentSettings },
                onCoordinateStateClear = ::coordinateStateClear,
                onCheckAutoCapitalization = ::checkAutoCapitalization,
                onDisableCapsLockAfterPunctuation = { viewModel.disableCapsLockAfterPunctuation() }
            )
            backspaceHandler = BackspaceHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                suggestionPipeline = suggestionPipeline,
                candidateBarController = candidateBarController,
                layoutManager = layoutManager,
                serviceScope = serviceScope,
                onCoordinateStateClear = ::coordinateStateClear,
                onInvalidateComposingState = ::invalidateComposingStateOnCursorJump,
                onDisableShiftAfterBackspace = { viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false)) },
                onGetKeyboardState = { viewModel.state.value },
                onSendDownUpKeyEvents = ::sendDownUpKeyEvents
            )
            spaceInputHandler = SpaceInputHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                suggestionPipeline = suggestionPipeline,
                autoCorrectionEngine = autoCorrectionEngine,
                swipeSpaceManager = swipeSpaceManager,
                swipeDetector = swipeDetector,
                candidateBarController = candidateBarController,
                languageManager = languageManager,
                serviceScope = serviceScope,
                onGetCurrentSettings = { currentSettings },
                onCheckAutoCapitalization = ::checkAutoCapitalization,
                onJapaneseSpaceAdvance = { advanceJapaneseCandidate() }
            )
            swipeWordHandler = SwipeWordHandler(
                inputState = inputState,
                outputBridge = outputBridge,
                suggestionPipeline = suggestionPipeline,
                textInputProcessor = textInputProcessor,
                wordLearningEngine = wordLearningEngine,
                languageManager = languageManager,
                caseTransformer = caseTransformer,
                swipeSpaceManager = swipeSpaceManager,
                swipeDetector = swipeDetector,
                serviceScope = serviceScope,
                onGetKeyboardState = { viewModel.state.value },
                onCoordinateStateClear = ::coordinateStateClear,
                onCheckAutoCapitalization = ::checkAutoCapitalization,
                onDisableShiftAfterSwipe = { viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false)) }
            )

            autofillCoordinator = AutofillStateCoordinator(
                tracker = AutofillStateTracker(),
                candidateBarController = candidateBarController,
                serviceScope = serviceScope,
                displaySuggestions = { suggestions -> inflateAndDisplaySuggestions(suggestions) }
            )

            clipboardCoordinator = ClipboardActionCoordinator(
                clipboardRepository = clipboardRepository,
                outputBridge = outputBridge,
                serviceScope = serviceScope,
                panelHost = this
            )

            keyEventRouter.configure(
                handler = this,
                searchInputHandler = { key -> candidateBarController.handleSearchInput(key) },
                viewModel = viewModel
            )
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "core_init")
            )
            throw e
        }
    }

    private suspend fun initializeServices() {
        try {
            customKeyMappingService.initialize()

            val result = languageManager.initialize()
            if (result.isFailure) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.CRITICAL,
                    exception =
                    result.exceptionOrNull()
                        ?: Exception("Language manager initialization failed"),
                    context = mapOf("phase" to "language_init")
                )
                return
            }

            // Seed currentSettings synchronously (off the main thread, during onCreate) so the very first
            // onStartInputView paint reads the real customSuggestions instead of the empty default — the
            // async settings collector can otherwise emit AFTER that first paint, leaving the custom row
            // blank until a keypress. (Bug 1 — load-timing race.)
            try {
                val seeded = settingsRepository.settings.first()
                currentSettings = seeded
                applyCustomSuggestions(seeded)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "seedCurrentSettings")
                )
            }

            try {
                spellCheckManager.clearCaches()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "clearCaches_spellCheck")
                )
            }

            try {
                textInputProcessor.clearCaches()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "clearCaches_textInputProcessor")
                )
            }

            val currentLanguage = languageManager.currentLanguage.value
            try {
                wordLearningEngine.initializeLearnedWordsCache(currentLanguage)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context =
                    mapOf(
                        "phase" to "word_learning_init",
                        "language" to currentLanguage
                    )
                )
            }

            val currentLayoutLanguage = languageManager.currentLayoutLanguage.value
            val locale = ULocale.forLanguageTag(currentLayoutLanguage)
            updateScriptContext(locale)

            try {
                wordLearningEngine.getLearningStats()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "getLearningStats")
                )
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "services_init")
            )
        }
    }

    override fun onCreateInputView(): View? {
        try {
            if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            }

            val actualWindow = window?.window
            if (actualWindow != null) {
                val layoutParams = actualWindow.attributes
                layoutParams.gravity = Gravity.BOTTOM
                actualWindow.attributes = layoutParams
                actualWindow.navigationBarColor = themeManager.currentTheme.value.colors.keyboardBackground
                // Transparent IME window so the bottom-lift gap (part of the input-view area) shows the
                // app through, rather than the window's default opaque background.
                actualWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }

            val initialMode = keyboardModeManager.currentMode.value
            val initialDims = initialMode.adaptiveDimensions
            if (initialDims != null) {
                val initialLook = withLookKnobs(initialDims)
                layoutManager.updateAdaptiveDimensions(initialLook)
                layoutManager.updateSplitGapPx(initialLook.splitGapPx)
            } else {
                layoutManager.updateSplitGapPx(initialMode.splitGapPx)
            }

            val hasMultipleImes = inputMethodManager.enabledInputMethodList.size > 1
            layoutManager.updateHasMultipleImes(hasMultipleImes)

            val keyboardView = createSwipeKeyboardView() ?: return null

            window?.window?.context?.let { windowContext ->
                postureDetector?.attachToWindow(windowContext)
            }

            val adaptive =
                com.urik.keyboard.ui.keyboard.components.AdaptiveKeyboardContainer(this).apply {
                    setThemeManager(themeManager)
                    setKeyboardView(keyboardView)
                    setOnLayoutTransformListener { scaleFactor, offsetX ->
                        swipeDetector.updateLayoutTransform(scaleFactor, offsetX)
                    }
                    setOnModeToggleListener { mode ->
                        keyboardModeManager.setManualMode(mode)
                    }
                    setOnFloatingRectChangeListener { x, y, w, h ->
                        commitFloatingRect(x, y, w, h)
                    }
                }

            adaptiveContainer = adaptive

            val currentModeConfig = keyboardModeManager.currentMode.value
            val currentPostureInfo = postureDetector?.postureInfo?.value
            adaptive.setModeConfig(currentModeConfig, currentPostureInfo?.hingeBounds)

            val panel =
                ClipboardPanel(this, themeManager).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                }
            clipboardPanel = panel

            val rootContainer =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )

                    // Transparent root: only the keyboard view (opaque) and the clipboard panel cover
                    // their areas, so the bottom-lift gap and narrowed sides show the app through.
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)

                    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                        view.setPadding(
                            systemBars.left,
                            0,
                            systemBars.right,
                            systemBars.bottom
                        )
                        WindowInsetsCompat.CONSUMED
                    }

                    addView(
                        panel,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )

                    addView(
                        adaptive,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )

                    ViewCompat.requestApplyInsets(this)
                }

            keyboardRootContainer = rootContainer
            return rootContainer
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "onCreateInputView")
            )
            return null
        }
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * Tell the app how to lay out around the keyboard. When split, set [contentTopInsets] to the bottom of the
     * input view so the app lays out FULL-SCREEN BEHIND the (floating) keyboard instead of resizing above it —
     * that's what makes the see-through gap reveal the app in every orientation (FUTO-style; the app "jumps"
     * under the keyboard when you split and back above when you un-split). The opaque keys cover the app; only
     * the centre gap shows through. Un-split → the framework default (app resizes above the keyboard).
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val root = keyboardRootContainer ?: return
        if (root.height <= 0) return

        // FLOATING: the input view fills the screen but only the panel's rect is touchable — taps outside it
        // pass through to the app. Report the full input-view height as occupied so the app lays out behind
        // the (floating) panel (it "jumps" under), exactly like the split recipe, but with a tighter region.
        val container = adaptiveContainer
        val panelRect = android.graphics.Rect()
        if (container != null && container.getFloatingPanelRect(panelRect)) {
            // Offset the container-local panel rect into root/window coordinates.
            val containerTop = offsetTopWithinRoot(container, root)
            val left = panelRect.left
            val top = (panelRect.top + containerTop).coerceAtLeast(0)
            val right = panelRect.right.coerceAtMost(root.width)
            val bottom = (panelRect.bottom + containerTop).coerceAtMost(root.height)
            outInsets.contentTopInsets = root.height
            outInsets.visibleTopInsets = root.height
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(left, top, right, bottom)
            return
        }

        // SPLIT (and any look that produces a split gap): the docked "floating" recipe — the whole input-view
        // area is touchable (the opaque keys cover the app; only the centre gap shows through).
        val splitGap = keyboardModeManager.currentMode.value.adaptiveDimensions
            ?.let { withLookKnobs(it).splitGapPx } ?: 0
        if (splitGap > 0) {
            val h = root.height
            outInsets.contentTopInsets = h
            outInsets.visibleTopInsets = h
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(0, 0, root.width, h)
        }
    }

    /** Sum the top offsets from [descendant] up to [ancestor] (both in the same view tree). */
    private fun offsetTopWithinRoot(descendant: View, ancestor: View): Int {
        var top = 0
        var v: View? = descendant
        while (v != null && v !== ancestor) {
            top += v.top
            v = v.parent as? View
        }
        return top
    }

    private fun createSwipeKeyboardView(): View? = try {
        if (!::viewModel.isInitialized || !::layoutManager.isInitialized) {
            initializeCoreComponents()
        }

        val swipeView =
            SwipeKeyboardView(this).apply {
                initialize(
                    layoutManager,
                    swipeDetector,
                    spellCheckManager,
                    wordLearningEngine,
                    themeManager,
                    languageManager,
                    emojiSearchManager,
                    recentEmojiProvider
                )
                // The keyboard view carries the opaque background so the container/root can stay
                // transparent — that makes the bottom-lift gap (and narrowed sides) see-through.
                setBackgroundColor(themeManager.currentTheme.value.colors.keyboardBackground)
                setOnKeyClickListener { key ->
                    inputState.clearBigramPredictions()
                    keyEventRouter.route(key)
                }
                setOnSwipeWordListener { validatedWord -> handleSwipeWord(validatedWord) }
                setOnSuggestionClickListener { suggestion -> handleSuggestionSelected(suggestion) }
                setOnExpandRequestedListener {
                    // Build the full (tens-of) candidate list off the main thread, then show the pane.
                    serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        val list = suggestionPipeline.expandedClusterCandidates()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            swipeKeyboardView?.showCandidatesPane(list)
                        }
                    }
                }
                setOnSuggestionLongPressListener { suggestion ->
                    handleSuggestionRemoval(
                        suggestion
                    )
                }
                setOnEmojiSelectedListener { selectedEmoji ->
                    handleEmojiSelected(selectedEmoji)
                }
                setOnBackspacePressedListener {
                    handleBackspace()
                }
                setOnSpacebarCursorMoveListener { distance ->
                    handleSpacebarCursorMove(distance)
                }
                setOnBackspaceSwipeDeleteListener {
                    handleBackspaceSwipeDelete()
                }
            }

        swipeKeyboardView = swipeView
        keyboardModeManager.currentMode.value.adaptiveDimensions?.let {
            swipeView.updateAdaptiveDimensions(withLookKnobs(it))
        }
        layoutManager.setSwipeKeyboardView(swipeView)
        wireResizeOverlay(swipeView.keyboardResizeOverlay)
        updateSwipeKeyboard()
        observeViewModel()

        swipeView
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "UrikInputMethodService",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("phase" to "create_keyboard_view")
        )
        null
    }

    private fun handleEmojiSelected(emoji: String) {
        serviceScope.launch {
            try {
                if (inputState.displayBuffer.isNotEmpty()) {
                    val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                    val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                    if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                        coordinateStateClear()
                    }
                }

                if (!inputState.requiresDirectCommit && inputState.displayBuffer.isNotEmpty()) {
                    suggestionPipeline.coordinateWordCompletion()
                }

                outputBridge.commitText(emoji, 1)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleEmojiSelected")
                )
            }
        }
    }

    private fun handleClipboardButtonClick() {
        val panel = clipboardPanel ?: return

        if (panel.isShowing) {
            dismissClipboardPanel()
            return
        }

        serviceScope.launch {
            try {
                val settings = settingsRepository.settings.first()

                if (!settings.clipboardEnabled) return@launch

                val keyboardHeight = adaptiveContainer?.height ?: return@launch
                adaptiveContainer?.visibility = View.GONE
                panel.layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        keyboardHeight
                    )

                if (!settings.clipboardConsentShown) {
                    panel.showConsentScreen {
                        serviceScope.launch {
                            settingsRepository.updateClipboardConsentShown(true)
                            clipboardMonitorService.startMonitoring()
                            clipboardCoordinator.loadAndDisplayContent()
                        }
                    }
                } else {
                    clipboardCoordinator.loadAndDisplayContent()
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleClipboardButtonClick")
                )
            }
        }
    }

    private fun dismissClipboardPanel() {
        clipboardPanel?.hide()
        adaptiveContainer?.visibility = View.VISIBLE
    }

    override suspend fun onClipboardDataLoaded(
        pinnedItems: List<com.urik.keyboard.data.database.ClipboardItem>,
        recentItems: List<com.urik.keyboard.data.database.ClipboardItem>
    ) {
        val panel = clipboardPanel ?: return
        withContext(Dispatchers.Main) {
            if (panel.isShowing) {
                panel.refreshContent(pinnedItems, recentItems)
            } else {
                panel.showClipboardContent(
                    pinnedItems = pinnedItems,
                    recentItems = recentItems,
                    onItemClick = { content ->
                        clipboardCoordinator.pasteContent(content)
                        dismissClipboardPanel()
                    },
                    onPinToggle = { item -> clipboardCoordinator.togglePin(item) },
                    onDelete = { item -> clipboardCoordinator.deleteItem(item) },
                    onDeleteAll = { clipboardCoordinator.deleteAllUnpinned() },
                    onClose = { dismissClipboardPanel() }
                )
            }
        }
    }

    private fun handleLanguageSwitch(languageCode: String) {
        serviceScope.launch {
            try {
                coordinateStateClear()
                viewModel.clearShiftAndCapsState()

                val switched = languageManager.switchLayoutLanguage(languageCode).isSuccess

                val locale =
                    ULocale
                        .forLanguageTag(languageCode)
                updateScriptContext(locale)

                // Remember this layout language for the current app (per-app layout memory).
                if (switched) {
                    currentInputEditorInfo?.packageName?.let { pkg ->
                        settingsRepository.setPerAppLayoutLanguage(pkg, languageCode)
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "handleLanguageSwitch")
                )
            }
        }
    }

    /** 1D space-menu: set the active layout for [language] and reload (switching language first if needed). */
    fun switchToLayout(language: String, layoutId: String) {
        serviceScope.launch {
            settingsRepository.setActiveLayoutForLanguage(language, layoutId)
            if (languageManager.currentLayoutLanguage.value != language) {
                handleLanguageSwitch(language)
            } else {
                repository.cleanup()
                viewModel.reloadLayout()
                refreshLookKnobs()
                withContext(Dispatchers.Main) { updateSwipeKeyboard() }
            }
        }
    }

    /** Space-menu actions column: open a settings page, or the system IME chooser. */
    private fun handleSpaceMenuAction(action: String) {
        if (action == "ime_picker") {
            showInputMethodPicker()
            return
        }
        if (action == "editor") {
            val editorIntent = com.urik.keyboard.settings.library.KeyboardEditorActivity
                .intentForActiveLayout(this, languageManager.currentLayoutLanguage.value)
            editorIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(editorIntent)
            return
        }
        if (action == "mode") {
            showModePicker()
            return
        }
        val intent = when (action) {
            "kxkb_ui" -> com.urik.keyboard.settings.SettingsActivity.createIntent(
                this, com.urik.keyboard.settings.SettingsActivity.PAGE_KEYBOARD_UI
            )
            "languages" -> com.urik.keyboard.settings.SettingsActivity.createIntent(
                this, com.urik.keyboard.settings.SettingsActivity.PAGE_LANGUAGES
            )
            "library" -> com.urik.keyboard.settings.SettingsActivity.createIntent(
                this, com.urik.keyboard.settings.SettingsActivity.PAGE_LIBRARY
            )
            "export_import" -> com.urik.keyboard.settings.SettingsActivity.createIntent(
                this, com.urik.keyboard.settings.SettingsActivity.PAGE_EXPORT_IMPORT
            )
            "user_dictionary" -> com.urik.keyboard.settings.SettingsActivity.createIntent(
                this, com.urik.keyboard.settings.SettingsActivity.PAGE_USER_DICTIONARY
            )
            else -> com.urik.keyboard.settings.SettingsActivity.createIntent(this)
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * Space-slide "Keyboard mode" → one themed selection dialog over the keyboard. Selection-only (no text
     * field) so there's no IME focus war; it's an attached dialog on the input view's window token. Picking a
     * mode routes through the same setManualMode the kxkb-UI Mode picker + one-handed toggle use.
     */
    private fun showModePicker() {
        val anchor = swipeKeyboardView ?: return
        val anchorToken = anchor.windowToken ?: return
        val modes = listOf(
            KeyboardDisplayMode.STANDARD,
            KeyboardDisplayMode.SPLIT,
            KeyboardDisplayMode.ONE_HANDED_LEFT,
            KeyboardDisplayMode.ONE_HANDED_RIGHT,
            KeyboardDisplayMode.FLOATING
        )
        val labels = arrayOf(
            getString(R.string.keyboard_ui_mode_standard),
            getString(R.string.keyboard_ui_mode_split),
            getString(R.string.keyboard_ui_mode_one_handed_left),
            getString(R.string.keyboard_ui_mode_one_handed_right),
            getString(R.string.keyboard_ui_mode_floating)
        )
        val checked = modes.indexOf(keyboardModeManager.currentMode.value.mode).coerceAtLeast(0)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Urik_Dialog)
            .setTitle(R.string.space_menu_mode)
            .setSingleChoiceItems(labels, checked) { d, which ->
                keyboardModeManager.setManualMode(modes[which])
                d.dismiss()
            }
            .setNegativeButton(R.string.library_git_cancel, null)
            .create()
        dialog.window?.let { w ->
            val lp = w.attributes
            lp.token = anchorToken
            lp.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            w.attributes = lp
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        dialog.show()
    }

    private val virtualKeyCharMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    /** Dispatches a compass-key binding from the GNU layout (chords, function keys, layers). */
    private fun handleFlickBinding(binding: KeyboardKey.FlickBinding) {
        when (binding) {
            is KeyboardKey.FlickBinding.Action -> handleGnuAction(binding.name)
            is KeyboardKey.FlickBinding.Chord -> sendChord(binding.spec)
            is KeyboardKey.FlickBinding.Layer -> handleLayerSwitch(binding.target)
        }
    }

    // Multitap (cycle) state: consecutive taps of the same cycle key step through its entries, replacing the
    // previously committed one. Any other key tap (onKeyClick) or a new input field (onStartInput) resets it.
    private var activeCycleTaps: List<String>? = null
    private var cycleIndex = 0
    private var cycleCommittedLen = 0

    private fun handleCycleTap(taps: List<String>) {
        if (taps.isEmpty()) return
        if (activeCycleTaps == taps) {
            outputBridge.deleteSurroundingText(cycleCommittedLen, 0)
            cycleIndex = (cycleIndex + 1) % taps.size
        } else {
            activeCycleTaps = taps
            cycleIndex = 0
        }
        val entry = taps[cycleIndex]
        outputBridge.commitText(entry, 1)
        cycleCommittedLen = entry.length
    }

    private fun resetCycle() {
        activeCycleTaps = null
        cycleIndex = 0
        cycleCommittedLen = 0
    }

    /** GNU compass layers map onto Urik's keyboard modes: main = letters, altPages = numbers/symbols. */
    private fun handleLayerSwitch(target: String) {
        val mode = when (target) {
            "alt0" -> KeyboardMode.NUMBERS
            "alt1" -> KeyboardMode.SYMBOLS
            "alt2" -> KeyboardMode.SYMBOLS_SECONDARY
            "alt3" -> KeyboardMode.NUMPAD
            else -> KeyboardMode.LETTERS
        }
        onModeSwitch(mode)
    }

    private fun handleGnuAction(name: String) {
        when (name) {
            "escape" -> sendKeyEventWithMeta(KeyEvent.KEYCODE_ESCAPE, 0)
            // Route through onTab so a flick-bound Tab ("tap" key) advances the cluster candidate selection
            // when one is pending, and only falls back to a literal KEYCODE_TAB otherwise.
            "tab" -> onTab()
            // A compass/flick Enter (the column layouts' ⏎ key) must take the EXACT same path as the plain
            // action-Enter key: finish any composing word with NO trailing space, then perform the field's
            // editor action. Routing it through onEnterAction (not outputBridge.sendEnter(), which commits a
            // "\n" that single-line fields normalise to a SPACE) fixes the "ac " trailing-space bug. (Bug E.)
            "enter" -> onEnterAction(EditorInfo.IME_ACTION_NONE)
            "space" -> outputBridge.sendSpace()
            "backspace" -> handleBackspace()
            "arrow_up" -> sendKeyEventWithMeta(KeyEvent.KEYCODE_DPAD_UP, 0)
            "arrow_down" -> sendKeyEventWithMeta(KeyEvent.KEYCODE_DPAD_DOWN, 0)
            "arrow_left" -> sendKeyEventWithMeta(KeyEvent.KEYCODE_DPAD_LEFT, 0)
            "arrow_right" -> sendKeyEventWithMeta(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
            "undo" -> currentInputConnection?.performContextMenuAction(android.R.id.undo)
            "redo" -> currentInputConnection?.performContextMenuAction(android.R.id.redo)
            "cut" -> currentInputConnection?.performContextMenuAction(android.R.id.cut)
            "copy" -> currentInputConnection?.performContextMenuAction(android.R.id.copy)
            "paste" -> currentInputConnection?.performContextMenuAction(android.R.id.paste)
            "select_all" -> currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            "hide" -> requestHideSelf(0)
            // "Reshow": hide the keyboard, then re-request it so the framework runs a FRESH show — which
            // re-pins the window height from scratch and clears the rare cold-start bottom-clip that an
            // in-place remeasure can leave behind. requestShowSelf is API 30+; on older devices we can't
            // self-show, so we re-pin the height in place instead (no hide, no flicker — same end result).
            "reshow" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestHideSelf(0)
                    keyboardRootContainer?.postDelayed({ requestShowSelf(0) }, RESHOW_REOPEN_DELAY_MS)
                } else {
                    forceInputViewRemeasure()
                }
            "next_language" -> handleLanguageSwitch(languageManager.getNextLayoutLanguage())
        }
    }

    /** Parses an Emacs-style chord spec ("C-c", "M-x", "S-TAB", "C-S-x", "C--") and sends it. */
    private fun sendChord(spec: String) {
        var s = spec
        var meta = 0
        while (s.length >= 2 && s[1] == '-' && (s[0] == 'C' || s[0] == 'M' || s[0] == 'S')) {
            meta = meta or when (s[0]) {
                'C' -> KeyEvent.META_CTRL_ON
                'M' -> KeyEvent.META_ALT_ON
                else -> KeyEvent.META_SHIFT_ON
            }
            s = s.substring(2)
        }
        val (keyCode, baseMeta) = resolveChordKey(s) ?: return
        sendKeyEventWithMeta(keyCode, meta or baseMeta)
    }

    private fun resolveChordKey(token: String): Pair<Int, Int>? = when (token.uppercase()) {
        "TAB" -> KeyEvent.KEYCODE_TAB to 0
        "RET", "RETURN", "ENTER" -> KeyEvent.KEYCODE_ENTER to 0
        "SPC", "SPACE" -> KeyEvent.KEYCODE_SPACE to 0
        // Named special keys (Emacs notation), so chords like "M-C-RIGHT" send Alt-Ctrl-Right rather than
        // falling through to the single-char path (which read "RIGHT" as 'R', or returned null for it).
        "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT to 0
        "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT to 0
        "UP" -> KeyEvent.KEYCODE_DPAD_UP to 0
        "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN to 0
        "ESC", "ESCAPE" -> KeyEvent.KEYCODE_ESCAPE to 0
        "DEL", "BACKSPACE", "BS" -> KeyEvent.KEYCODE_DEL to 0
        "DELETE", "DELETECHAR" -> KeyEvent.KEYCODE_FORWARD_DEL to 0
        "HOME" -> KeyEvent.KEYCODE_MOVE_HOME to 0
        "END" -> KeyEvent.KEYCODE_MOVE_END to 0
        "PRIOR", "PAGEUP", "PGUP" -> KeyEvent.KEYCODE_PAGE_UP to 0
        "NEXT", "PAGEDOWN", "PGDN" -> KeyEvent.KEYCODE_PAGE_DOWN to 0
        "INSERT", "INS" -> KeyEvent.KEYCODE_INSERT to 0
        else -> if (token.length == 1) {
            virtualKeyCharMap.getEvents(charArrayOf(token[0]))
                ?.firstOrNull()
                ?.let { it.keyCode to it.metaState }
        } else {
            null
        }
    }

    /** Sends a key event with modifiers as discrete down/up events around the key (terminal-friendly). */
    private fun sendKeyEventWithMeta(keyCode: Int, metaState: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        fun send(action: Int, code: Int, meta: Int) {
            ic.sendKeyEvent(KeyEvent(now, now, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
        }
        val ctrl = metaState and KeyEvent.META_CTRL_ON != 0
        val alt = metaState and KeyEvent.META_ALT_ON != 0
        val shift = metaState and KeyEvent.META_SHIFT_ON != 0
        var m = 0
        if (ctrl) {
            m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            send(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, m)
        }
        if (alt) {
            m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            send(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, m)
        }
        if (shift) {
            m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            send(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, m)
        }
        send(KeyEvent.ACTION_DOWN, keyCode, m)
        send(KeyEvent.ACTION_UP, keyCode, m)
        if (shift) {
            m = m and (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON).inv()
            send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, m)
        }
        if (alt) {
            m = m and (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON).inv()
            send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, m)
        }
        if (ctrl) {
            m = m and (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON).inv()
            send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, m)
        }
    }

    private fun showInputMethodPicker() {
        try {
            inputMethodManager.showInputMethodPicker()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "showInputMethodPicker")
            )
        }
    }

    private fun observeSettings() {
        observerJobs.add(
            serviceScope.launch {
                settingsRepository.settings
                    .distinctUntilChanged()
                    .collect { newSettings ->
                        if (!::layoutManager.isInitialized || !::swipeDetector.isInitialized) {
                            return@collect
                        }

                        val layoutChanged =
                            currentSettings.alternativeKeyboardLayout != newSettings.alternativeKeyboardLayout ||
                                currentSettings.showLanguageSwitchKey != newSettings.showLanguageSwitchKey

                        currentSettings = newSettings

                        // Custom suggestion row: resolve the CURRENT layout language's row (per-language, with
                        // built-in defaults) and hand the clean list to the input state for the candidate bar.
                        applyCustomSuggestions(newSettings)
                        // If the bar is currently idle (no composing word, no live predictions), repaint the
                        // default row now so a just-saved custom row appears without needing a refocus (Bug 3).
                        if (inputState.displayBuffer.isEmpty() && inputState.pendingSuggestions.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                inputState.clearSuggestionDisplay()
                            }
                        }

                        val currentMode = keyboardModeManager.currentMode.value.mode
                        updateSwipeEnabledState(currentMode)

                        layoutManager.updateLongPressDuration(newSettings.longPressDuration)
                        layoutManager.updateLongPressPunctuationMode(newSettings.longPressPunctuationMode)

                        layoutManager.updateKeySize(newSettings.keySize)
                        layoutManager.updateSpaceBarSize(newSettings.spaceBarSize)
                        layoutManager.updateKeyLabelSize(newSettings.keyLabelSize)

                        swipeKeyboardView?.setCursorSpeed(newSettings.cursorSpeed)

                        layoutManager.updateHapticSettings(
                            newSettings.hapticFeedback,
                            newSettings.vibrationStrength
                        )

                        layoutManager.updateClipboardEnabled(newSettings.clipboardEnabled)
                        layoutManager.updateShowLanguageSwitchKey(newSettings.showLanguageSwitchKey)
                        layoutManager.updateNumberHints(newSettings.showNumberHints)
                        layoutManager.updatePressHighlight(newSettings.keyPressHighlightEnabled)
                        layoutManager.updateKeyPreview(newSettings.keyPreviewEnabled)

                        if (layoutChanged) {
                            repository.cleanup()
                            viewModel.reloadLayout()
                            // The alternative layout is part of the look combo — re-resolve.
                            refreshLookKnobs()
                        }

                        withContext(Dispatchers.Main) {
                            updateSwipeKeyboard()
                        }
                    }
            }
        )
    }

    private fun observeViewModel() {
        observerJobs.forEach { it.cancel() }
        observerJobs.clear()

        observerJobs.add(
            serviceScope.launch {
                var prevShift = false
                var prevCapsLock = false
                var prevAutoShift = false
                viewModel.state.collect { state ->
                    updateSwipeKeyboard()

                    val shiftChanged =
                        state.isShiftPressed != prevShift ||
                            state.isCapsLockOn != prevCapsLock ||
                            state.isAutoShift != prevAutoShift
                    prevShift = state.isShiftPressed
                    prevCapsLock = state.isCapsLockOn
                    prevAutoShift = state.isAutoShift

                    if (shiftChanged && inputState.currentRawSuggestions.isNotEmpty()) {
                        var effectiveState = state
                        if (inputState.isCurrentWordManualShifted && !state.isShiftPressed && !state.isCapsLockOn) {
                            effectiveState = state.copy(isShiftPressed = true, isAutoShift = false)
                        }
                        val recased =
                            caseTransformer.applyCasingToSuggestions(
                                inputState.currentRawSuggestions,
                                effectiveState,
                                inputState.isCurrentWordAtSentenceStart
                            )
                        inputState.pendingSuggestions = recased
                        // Route through updateSuggestionDisplay so the custom-row suffix (if any) stays
                        // appended after the recased predictions instead of being dropped on a shift change.
                        inputState.updateSuggestionDisplay(recased)
                    }
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                viewModel.layout.collect { layout ->
                    if (layout != null) {
                        updateSwipeKeyboard()
                        coldBuildPending = false // grid is built — track the real height from here (release the floor)
                        // The layout id is now known — re-resolve the per-(app·layout·geometry) size with the
                        // real id (the first show may have resolved before the layout finished loading).
                        refreshLookKnobs()
                        // The layout populating is the big first-show grow — pin the window to the new height
                        // unconditionally (refreshLookKnobs only re-measures when a knob actually changed).
                        forceInputViewRemeasure()
                        val locale = ULocale.forLanguageTag(languageManager.currentLayoutLanguage.value)
                        updateScriptContext(locale)
                    }
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                languageManager.currentLayoutLanguage.collect { detectedLanguage ->
                    swipeDetector.updateCurrentLanguage(detectedLanguage.split("-").first())
                    val isJa = detectedLanguage.split("-").first() == "ja"
                    textInputProcessor.setJapaneseLayout(isJa)
                    suggestionPipeline.setJapaneseLayout(isJa)
                    // Japanese shares the cluster Space-commits / Tab-advances candidate model, so keep the
                    // bar highlight on for it (and for any active cluster layout).
                    candidateBarController.setSuggestionSelectionEnabled(isJa || inputState.clusterLayoutActive)
                    // The custom-suggestion row is per-language: re-resolve it for the new layout language, then
                    // repaint the idle default row NOW. The bar's pendingSuggestions still holds the PREVIOUS
                    // language's custom row, so without this the bar shows the prior language's entries until the
                    // next keystroke. Gate on no composing word so an in-progress word isn't wiped.
                    applyCustomSuggestions()
                    if (inputState.displayBuffer.isEmpty()) {
                        inputState.clearSuggestionDisplay()
                    }
                    // Re-evaluate field flags so the GNU layout's forced no-prediction takes
                    // effect immediately when switching to/from it (not only on field focus).
                    applyFieldTypeFromEditorInfo(currentInputEditorInfo)
                    // The layout language is part of the look combo — re-resolve.
                    refreshLookKnobs()
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                languageManager.activeLanguages.collect { languages ->
                    layoutManager.updateActiveLanguages(languages)
                    updateSwipeKeyboard()

                    languages.forEach { lang ->
                        wordFrequencyRepository.preloadTopBigrams(lang)
                    }
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                languageManager.effectiveDictionaryLanguages.collect { languages ->
                    swipeDetector.updateActiveLanguages(languages)
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                themeManager.currentTheme.collect { theme ->
                    keyboardRootContainer?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    val gap = keyboardModeManager.currentMode.value.adaptiveDimensions
                        ?.let { withLookKnobs(it).splitGapPx } ?: 0
                    applySwipeKeyboardBackground(gap)
                    window?.window?.navigationBarColor = theme.colors.keyboardBackground
                    updateSwipeKeyboard()
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                // Cache the decoded look map in memory and re-resolve + re-apply whenever it changes (the
                // Keyboard UI sliders or the on-keyboard resize wrote a knob). The cache lets refreshLookKnobs
                // resolve the per-(app·layout·geometry) size SYNCHRONOUSLY on the show path (serviceScope is
                // Main), so the window is sized to the right height up front instead of a frame late (clipping).
                settingsRepository.perGeometryLookMap.collect {
                    lookMapCache = it
                    lookMapLoaded = true
                    refreshLookKnobs()
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                customKeyMappingService.mappings.collect { mappings ->
                    layoutManager.updateCustomKeyMappings(mappings)
                    updateSwipeKeyboard()
                }
            }
        )

        observerJobs.add(
            serviceScope.launch {
                keyboardModeManager.currentMode.collect { config ->
                    outputBridge.beginBatchEdit()
                    try {
                        val postureInfo = postureDetector?.postureInfo?.value
                        adaptiveContainer?.setModeConfig(config, postureInfo?.hingeBounds)

                        val dims = config.adaptiveDimensions
                        if (dims != null) {
                            val look = withLookKnobs(dims)
                            layoutManager.updateAdaptiveDimensions(look)
                            layoutManager.updateSplitGapPx(look.splitGapPx)
                            swipeKeyboardView?.updateAdaptiveDimensions(look)
                            swipeDetector.updateAdaptiveDimensions(look)
                        } else {
                            layoutManager.updateSplitGapPx(config.splitGapPx)
                        }

                        updateSwipeEnabledState(config.mode)
                        updateSwipeKeyboard()
                        // A dims change (e.g. posture settling a frame after cold start) can grow the
                        // keyboard past the already-sized window — re-measure so the bottom isn't clipped.
                        forceInputViewRemeasure()
                        // Geometry may have changed (rotate/fold) — re-resolve the per-geometry look.
                        refreshLookKnobs()
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                }
            }
        )

        observeSettings()
    }

    /**
     * The active per-geometry "look" knob set, resolved from the look store for the current
     * (language·layout·geometry) and cached so the hot path ([withLookKnobs]) never suspends.
     * [refreshLookKnobs] re-resolves it off the main thread when geometry / language / layout / the
     * store changes. Seeds to 白い熊's signature default (square keys + bold labels).
     */
    @Volatile
    private var activeLookKnobs: KeyboardLookKnobs = KeyboardLookKnobs.DEFAULT

    /**
     * The single seam every [AdaptiveDimensions] push routes through: overlays [activeLookKnobs] onto
     * the mode's base dimensions before they reach the renderer / swipe views.
     */
    private fun withLookKnobs(dims: AdaptiveDimensions): AdaptiveDimensions {
        if (isUserUnlocked) return clampKeyboardHeight(activeLookKnobs.applyTo(dims, resources.displayMetrics.density))
        // On the lock screen (BFU) the saved per-geometry look is unreadable — force the tall, gapless,
        // double-primary-font BFU look for fast PIN entry, full-bleed (no side inset) to the screen edges.
        return clampKeyboardHeight(
            KeyboardLookKnobs.BFU.applyTo(dims, resources.displayMetrics.density)
                .copy(keyboardPaddingHorizontalPx = 0)
        )
    }

    /**
     * HARD CEILING on per-row key height so the whole keyboard can never exceed the display. A large
     * keyHeightScale makes the IME window taller than the screen, which the framework rejects → the keyboard
     * opens-and-closes (it bricks). This caps keyHeightPx so (rows + the suggestion bar) stay within ~62% of
     * the screen height; it ONLY shrinks an oversized value, so normal sizes are untouched. This is also the
     * recovery path for an already-saved oversized value — no data wipe needed.
     */
    /**
     * The row count height math uses: the layout's LETTERS-page rows, so the per-key height clamp resolves to
     * the SAME value on every page (symbols/alt/numpad then normalise to the letters height — see
     * KeyboardLayoutManager.pageHeightScale). Falls back to the current page's rows.
     */
    private fun referenceRowCount(): Int {
        if (!::viewModel.isInitialized) return 5
        val layout = viewModel.layout.value ?: return 5
        return (layout.referenceRows.takeIf { it > 0 } ?: layout.rows.size).coerceAtLeast(1)
    }

    private fun clampKeyboardHeight(dims: AdaptiveDimensions): AdaptiveDimensions {
        val rows = referenceRowCount()
        val screenH = resources.displayMetrics.heightPixels
        val maxKeyHeightPx = (screenH * 0.93f / (rows.coerceAtLeast(1) + 1)).toInt().coerceAtLeast(1)
        return if (dims.keyHeightPx > maxKeyHeightPx) dims.copy(keyHeightPx = maxKeyHeightPx) else dims
    }

    /**
     * The largest keyHeightScale that still fits the screen for the CURRENT layout (its row count) — the same
     * ceiling [clampKeyboardHeight] enforces, expressed as a scale. The resolved scale is clamped to this and
     * the resize drag is bounded by it, so the stored scale and the visible height stay in sync: dragging up
     * stops exactly where the keyboard fills its budget (instead of a dead zone above it).
     */
    private fun maxHeightScale(): Float {
        val base = keyboardModeManager.currentMode.value.adaptiveDimensions?.keyHeightPx ?: return 4f
        if (base <= 0) return 4f
        val rows = referenceRowCount()
        val maxKeyHeightPx = resources.displayMetrics.heightPixels * 0.93f / (rows.coerceAtLeast(1) + 1)
        return (maxKeyHeightPx / base).coerceIn(1f, 4f)
    }

    /**
     * Re-resolve the active look knobs from the store for the current geometry / layout language /
     * alternative layout (off the main thread), and re-apply if they changed. Idempotent.
     */
    // The decoded per-geometry look map mirrored from the store, so refreshLookKnobs resolves the
    // per-(app·layout·geometry) size SYNCHRONOUSLY — the keyboard is then sized right up front, not a frame
    // late (which clipped it). Updated by the perGeometryLookMap collector.
    @Volatile private var lookMapCache: Map<String, KeyboardLookKnobs> = emptyMap()
    // False until the look map has been read from the store once. Until then refreshLookKnobs keeps the
    // synchronous cold-start seed instead of resolving to DEFAULT (which would shrink-then-grow → flicker/clip).
    @Volatile private var lookMapLoaded = false

    /**
     * Re-resolve the per-(app·layout·geometry) look from [lookMapCache] and re-apply if it changed.
     * SYNCHRONOUS and on Main (every caller — the collectors and onStartInputView — runs on Main), so
     * activeLookKnobs is correct BEFORE the IME window is sized. The async tail just publishes values for the
     * settings screen / Library preview.
     */
    private fun refreshLookKnobs() {
        if (!lookMapLoaded) return // keep the synchronous cold-start seed until the map is read once
        val geometry = postureDetector?.postureInfo?.value?.let { geometryKey(it) } ?: GeometryBucket.FOLDED_PORT.key
        val app = currentInputEditorInfo?.packageName
        val layoutId = if (::viewModel.isInitialized) viewModel.layout.value?.id.orEmpty() else ""
        val resolved = resolveLookSync(app, layoutId, geometry)
        if (resolved != activeLookKnobs) {
            activeLookKnobs = resolved
            reapplyLookKnobs()
        }
        serviceScope.launch {
            settingsRepository.setCurrentGeometry(geometry)
            settingsRepository.setCurrentLayoutLanguage(languageManager.currentLayoutLanguage.value)
            settingsRepository.setCurrentKeyHeightScale(resolved.keyHeightScale)
            cacheLookKnobSeed(geometry, resolved)
            // Remember the focused REAL app's override key so the Keyboard UI "Reset layout to default" button
            // can target the app the user was typing in. Skip our own package (the settings test field) so a
            // visit to the settings screen never overwrites the real target.
            if (!app.isNullOrEmpty() && app != packageName) {
                settingsRepository.setCurrentSizeTarget("$app|$layoutId|$geometry")
            }
        }
    }

    /**
     * The effective knobs for [app]·[layoutId]·[geometry]: DEFAULT overlaid by the per-geometry baseline, then
     * by the per-(app·layout·geometry) size override (the four resize knobs). app null/empty → baseline only.
     * Reads only the in-memory [lookMapCache] (key format matches SettingsRepository.sizeOverrideKey), so it's
     * cheap enough to call on the show path.
     */
    private fun resolveLookSync(app: String?, layoutId: String, geometry: String): KeyboardLookKnobs {
        var resolved = KeyboardLookKnobs.DEFAULT
        lookMapCache[geometry]?.let { resolved = resolved.overlay(it) }
        if (!app.isNullOrEmpty()) {
            lookMapCache["$app|$layoutId|$geometry"]?.let { resolved = resolved.overlay(it) }
        }
        // Clamp the height scale to what fits this layout so the stored scale never sits ABOVE the visible
        // ceiling — otherwise the resize drag has a dead zone (moving the finger changes the scale but the
        // clamped height doesn't budge). Also recovers a previously-saved oversized value.
        val maxH = maxHeightScale()
        resolved.keyHeightScale?.let { if (it > maxH) resolved = resolved.copy(keyHeightScale = maxH) }
        return resolved
    }

    /**
     * Persist the just-resolved knobs (and the geometry they belong to) so that on the NEXT process start
     * (e.g. after an app update kills the IME) [seedLookKnobsFromCache] can size the very first keyboard
     * correctly — without it the first show built at DEFAULT height while the async resolve rebuilt taller
     * a frame later, leaving the keyboard clipped ("only half") until dismissed and reopened.
     */
    private fun cacheLookKnobSeed(geometry: String, knobs: KeyboardLookKnobs) {
        try {
            getSharedPreferences(LOOK_SEED_PREFS, Context.MODE_PRIVATE).edit()
                .putString(LOOK_SEED_LAST_GEO, geometry)
                .putString("$LOOK_SEED_KNOBS_PREFIX$geometry", knobs.encode())
                .apply()
        } catch (_: Exception) {
            // Best-effort cache; a miss just means the first render falls back to DEFAULT.
        }
    }

    /** Seed [activeLookKnobs] synchronously from the last cold-start cache (best-effort; DEFAULT on miss). */
    private fun seedLookKnobsFromCache() {
        try {
            val prefs = getSharedPreferences(LOOK_SEED_PREFS, Context.MODE_PRIVATE)
            val geo = prefs.getString(LOOK_SEED_LAST_GEO, null) ?: return
            val enc = prefs.getString("$LOOK_SEED_KNOBS_PREFIX$geo", null)
            if (enc.isNullOrEmpty()) return
            activeLookKnobs = KeyboardLookKnobs.decode(enc)
        } catch (_: Exception) {
            // Keep the DEFAULT seed.
        }
    }

    /** Re-push the current mode's dimensions through [withLookKnobs] to the renderer + swipe views. */
    private fun reapplyLookKnobs() {
        if (!::layoutManager.isInitialized) return
        val dims = keyboardModeManager.currentMode.value.adaptiveDimensions ?: return
        val look = withLookKnobs(dims)
        layoutManager.updateAdaptiveDimensions(look)
        layoutManager.updateSplitGapPx(look.splitGapPx)
        swipeKeyboardView?.updateAdaptiveDimensions(look)
        if (::swipeDetector.isInitialized) swipeDetector.updateAdaptiveDimensions(look)
        applyContainerLookKnobs()
        applySwipeKeyboardBackground(look.splitGapPx)
        updateSwipeKeyboard()
        forceInputViewRemeasure()
    }

    /**
     * Background of the whole keyboard view. When split (gap > 0) it's the see-through split background (a
     * centre strip is transparent so the app shows through the gap); the suggestion bar's own opaque
     * background covers the strip behind it. Otherwise a plain fill.
     */
    private fun applySwipeKeyboardBackground(splitGapPx: Int) {
        val view = swipeKeyboardView ?: return
        val color = activeLookKnobs.keyboardBgColor ?: themeManager.currentTheme.value.colors.keyboardBackground
        if (splitGapPx > 0) {
            view.background =
                com.urik.keyboard.ui.keyboard.components.SplitBackgroundDrawable(color, splitGapPx)
        } else {
            view.setBackgroundColor(color)
        }
    }

    /**
     * Force the IME window to re-measure to the current keyboard height. On a cold start (esp. the first
     * show after an app update, and on foldables where posture/look settle a frame late) the keyboard can
     * grow after the window was already sized short, leaving the bottom row clipped; a posted requestLayout
     * once the window is established makes the framework resize the window to fit.
     */
    /**
     * Make the IME window match the keyboard's height after the (async) layout/posture/look-knob settles.
     *
     * ROOT CAUSE of the recurring first-show bottom-clip: the input view is WRAP_CONTENT, and the framework
     * fixes the soft-input window height at `showWindow` time. On a cold start the keyboard is still short then
     * (layout loads async, the foldable posture settles async, look-knobs resolve async); it grows a fraction
     * of a second later, but a plain `requestLayout` on a WRAP_CONTENT input view does NOT make the framework
     * re-size the already-shown window — only a fresh show does (hence dismiss+reopen "fixed" it).
     *
     * Fix: pin the input view to an EXPLICIT measured content height. Setting `layoutParams.height` is a real
     * size change the framework's window layout must honour, so the window follows. We re-pin on the next two
     * frames and again on short delays to catch a grow that lands after the first pass (the posture settle on a
     * foldable is the slow one).
     */
    private fun forceInputViewRemeasure() {
        val root = keyboardRootContainer ?: return
        val pin = Runnable { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) pinInputViewHeight(root) }
        root.post {
            pin.run()
            root.post(pin)
            root.postDelayed(pin, 120)
            root.postDelayed(pin, 320)
            // Later passes for a SLOW settle: an app that launches the keyboard mid-transition (Termux from a
            // share) finishes its window/posture settle after the early passes. Each pass re-measures the true
            // content height (UNSPECIFIED), so a late pass corrects an earlier short pin — no lock, no listener.
            root.postDelayed(pin, 700)
            root.postDelayed(pin, 1200)
        }
    }

    /**
     * Measure the keyboard's NATURAL content height (UNSPECIFIED spec, so a previously-pinned height is ignored
     * — this also shrinks the window on a resize-down) and pin the input view to it, then relayout up the tree.
     * Skipped for FLOATING, which sizes its own tall container + reports its own insets.
     */
    private fun pinInputViewHeight(root: View) {
        val floatRect = android.graphics.Rect()
        if (adaptiveContainer?.getFloatingPanelRect(floatRect) == true) { root.requestLayout(); return }
        val widthPx = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val natural = root.measuredHeight
        if (natural <= 0) return
        // While the cold build is still pending, never sink below the cached good height — that floor is what
        // keeps the window full until the (slow) grid arrives. Once the build has settled we track the real
        // measured height exactly, so a resize-down shrinks and we record the new height for the next cold start.
        val target = if (coldBuildPending) maxOf(natural, presizeHeight) else natural
        root.layoutParams?.let { lp ->
            if (lp.height != target) { lp.height = target; root.layoutParams = lp }
        }
        root.requestLayout()
        (root.parent as? View)?.requestLayout()
        if (!coldBuildPending && natural > 0) {
            windowHeightCacheKey()?.let { k ->
                if (windowHeightCache.getInt(k, -1) != natural) windowHeightCache.edit().putInt(k, natural).apply()
            }
        }
    }

    /** Key for the per-app·geometry window-height cache. Layout id is intentionally omitted: on a cold start it
     *  isn't known yet, and one app overwhelmingly uses one layout, so app|geometry is the stable available key. */
    private fun windowHeightCacheKey(): String? {
        val app = currentInputEditorInfo?.packageName ?: return null
        val geometry = postureDetector?.postureInfo?.value?.let { geometryKey(it) } ?: GeometryBucket.FOLDED_PORT.key
        return "$app|$geometry"
    }

    /** Container-level look knobs (keyboard width narrowing + bottom lift) — applied to the container. */
    private fun applyContainerLookKnobs() {
        val density = resources.displayMetrics.density
        val widthScale = activeLookKnobs.keyboardWidthScale ?: 1f
        val liftPx = ((activeLookKnobs.bottomLiftDp ?: 0f) * density).toInt()
        adaptiveContainer?.applyLookKnobs(widthScale, liftPx)
        // Floating-panel rect (used only in FLOATING display mode; null fields → the centred default).
        adaptiveContainer?.applyFloatingKnobs(
            activeLookKnobs.floatXFraction,
            activeLookKnobs.floatYFraction,
            activeLookKnobs.floatWidthFraction,
            activeLookKnobs.floatHeightScale
        )
    }

    /**
     * Persist a floating-panel drag/resize into the SAME per-geometry baseline the Keyboard UI sliders edit
     * (merged so other knobs survive) — mirroring [commitResize]. Also keeps [activeLookKnobs] in sync so a
     * concurrent re-resolve doesn't snap the panel back.
     */
    private fun commitFloatingRect(xFraction: Float, yFraction: Float, widthFraction: Float, heightScale: Float) {
        activeLookKnobs =
            activeLookKnobs.copy(
                floatXFraction = xFraction,
                floatYFraction = yFraction,
                floatWidthFraction = widthFraction,
                floatHeightScale = heightScale
            )
        serviceScope.launch {
            val geometry =
                postureDetector?.postureInfo?.value?.let { geometryKey(it) } ?: GeometryBucket.FOLDED_PORT.key
            val existing = settingsRepository.getGeometryBaselineLook(geometry) ?: KeyboardLookKnobs()
            settingsRepository.updateGeometryBaselineLook(
                geometry,
                existing.copy(
                    floatXFraction = xFraction,
                    floatYFraction = yFraction,
                    floatWidthFraction = widthFraction,
                    floatHeightScale = heightScale
                )
            )
        }
    }

    /** Wire the seamless resize overlay (1C) to the look store: live-apply on drag, persist on release. */
    private fun wireResizeOverlay(overlay: ResizeOverlayView) {
        overlay.handleColor = themeManager.currentTheme.value.colors.swipePrimary
        overlay.longPressMs = currentSettings.longPressDuration.durationMs
        overlay.onHaptic = { if (::layoutManager.isInitialized) layoutManager.triggerHapticFeedback() }
        overlay.maxSplitPx = KeyboardLookKnobs.MAX_SPLIT_GAP_DP * resources.displayMetrics.density
        overlay.onBegin = {
            // Bound the drag to the screen-fit ceiling for the CURRENT layout, so dragging up stops where the
            // keyboard fills its budget instead of running into a clamped dead zone.
            overlay.maxHeightScale = maxHeightScale()
            ResizeValues(
                activeLookKnobs.keyHeightScale ?: 1f,
                activeLookKnobs.keyboardWidthScale ?: 1f,
                activeLookKnobs.bottomLiftDp ?: 0f,
                activeLookKnobs.splitFraction ?: 0f
            )
        }
        overlay.onApply = { v -> liveResize(v.heightScale, v.widthScale, v.bottomLiftDp, v.splitFraction) }
        overlay.onCommit = { v -> commitResize(v.heightScale, v.widthScale, v.bottomLiftDp, v.splitFraction) }
    }

    /** In-memory live apply of a resize drag (no persistence). */
    private fun liveResize(height: Float, width: Float, liftDp: Float, splitFraction: Float) {
        activeLookKnobs =
            activeLookKnobs.copy(
                keyHeightScale = height,
                keyboardWidthScale = width,
                bottomLiftDp = liftDp,
                splitFraction = splitFraction
            )
        reapplyLookKnobs()
    }

    /**
     * Commit a resize: snap-to-dock dead zone, then persist the four size knobs into the per-(app·layout·
     * geometry) size override — keyed by the SYNCHRONOUS live layout id (viewModel.layout.value.id) + the
     * focused app — so each layout keeps its own size per app and geometry, and resizing one never changes the
     * others. Merged so a prior override's values survive. Float position stays in the per-geometry baseline.
     * No focused app (shouldn't happen mid-resize) → the geometry baseline.
     */
    private fun commitResize(height: Float, width: Float, liftDp: Float, splitFraction: Float) {
        val w = if (width >= 0.98f) 1f else width
        val lift = if (liftDp <= 5f) 0f else liftDp
        val split = if (splitFraction <= 0.02f) 0f else splitFraction
        liveResize(height, w, lift, split)
        val app = currentInputEditorInfo?.packageName
        val layoutId = if (::viewModel.isInitialized) viewModel.layout.value?.id.orEmpty() else ""
        serviceScope.launch {
            val geometry =
                postureDetector?.postureInfo?.value?.let { geometryKey(it) } ?: GeometryBucket.FOLDED_PORT.key
            if (app.isNullOrEmpty()) {
                val existing = settingsRepository.getGeometryBaselineLook(geometry) ?: KeyboardLookKnobs()
                settingsRepository.updateGeometryBaselineLook(
                    geometry,
                    existing.copy(keyHeightScale = height, keyboardWidthScale = w, bottomLiftDp = lift, splitFraction = split)
                )
            } else {
                val existing = settingsRepository.getSizeOverride(app, layoutId, geometry) ?: KeyboardLookKnobs()
                settingsRepository.updateSizeOverride(
                    app,
                    layoutId,
                    geometry,
                    existing.copy(keyHeightScale = height, keyboardWidthScale = w, bottomLiftDp = lift, splitFraction = split)
                )
            }
        }
    }

    private fun computeFilteredLayout(layout: KeyboardLayout): KeyboardLayout =
        computeFilteredLayout(layout, currentSettings.showNumberRow)

    private fun updateSwipeKeyboard() {
        try {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return
            }

            val state = viewModel.state.value
            val layout = viewModel.layout.value

            if (layout != null && swipeKeyboardView != null) {
                val cacheKey = layout to currentSettings.showNumberRow
                val filteredLayout =
                    if (filteredLayoutCacheKey == cacheKey) {
                        filteredLayoutCache ?: computeFilteredLayout(layout)
                    } else {
                        val computed = computeFilteredLayout(layout)
                        filteredLayoutCache = computed
                        filteredLayoutCacheKey = cacheKey
                        computed
                    }

                swipeKeyboardView?.updateKeyboard(filteredLayout, state)
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "updateSwipeKeyboard")
            )
        }
    }

    private fun updateSwipeEnabledState(mode: KeyboardDisplayMode) {
        if (!::swipeDetector.isInitialized) return

        val userSettingEnabled = currentSettings.swipeEnabled
        val isSplitMode = mode == KeyboardDisplayMode.SPLIT

        val shouldEnableSwipe = userSettingEnabled && !isSplitMode

        swipeDetector.setSwipeEnabled(shouldEnableSwipe)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        layoutManager.updateLongPressDuration(currentSettings.longPressDuration)
        layoutManager.updateLongPressPunctuationMode(currentSettings.longPressPunctuationMode)
        layoutManager.updateKeySize(currentSettings.keySize)
        layoutManager.updateSpaceBarSize(currentSettings.spaceBarSize)
        layoutManager.updateKeyLabelSize(currentSettings.keyLabelSize)

        layoutManager.updateHapticSettings(
            currentSettings.hapticFeedback,
            currentSettings.vibrationStrength
        )

        layoutManager.updateClipboardEnabled(currentSettings.clipboardEnabled)
        layoutManager.updateShowLanguageSwitchKey(currentSettings.showLanguageSwitchKey)
        layoutManager.updateNumberHints(currentSettings.showNumberHints)
        layoutManager.updatePressHighlight(currentSettings.keyPressHighlightEnabled)
        layoutManager.updateKeyPreview(currentSettings.keyPreviewEnabled)

        if (serviceJob.isCancelled) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
        }

        if (observerJobs.isEmpty()) {
            observeViewModel()
        }

        super.onStartInputView(info, restarting)

        if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        applyFieldTypeFromEditorInfo(info)

        // Re-resolve the per-(app·layout·geometry) size for THIS app synchronously (the app changed on this
        // show), so the keyboard is at the right height before the window is sized — no clip on every open.
        refreshLookKnobs()
        // Cold-start pre-size: seed the window-height floor from the cached good height for this app|geometry, so
        // the window is sized right BEFORE the async grid build (which may finish after the window is locked). The
        // floor is released when the build settles (the layout collector) or after a safety timeout (warm shows
        // that never re-collect), so resize-down keeps shrinking normally.
        presizeHeight = windowHeightCacheKey()?.let { windowHeightCache.getInt(it, -1) } ?: -1
        coldBuildPending = presizeHeight > 0
        if (coldBuildPending) {
            // Apply the cached height to the input view NOW — before the framework measures it for showWindow — so
            // the window is correct on its first (and possibly only honoured) measure, not reliant on a late re-pin.
            // Skipped for floating (own sizing). The floor is released by the collector / a safety timeout below.
            keyboardRootContainer?.let { root ->
                val rect = android.graphics.Rect()
                if (adaptiveContainer?.getFloatingPanelRect(rect) != true) {
                    root.layoutParams = root.layoutParams?.apply { height = presizeHeight }
                }
            }
            keyboardRootContainer?.postDelayed({ coldBuildPending = false }, 1600)
        }
        // Pin the window to the keyboard height on every show (its delayed re-pins also catch the foldable
        // posture / async layout-load grow that lands after this returns).
        forceInputViewRemeasure()

        // Sync the custom-suggestion row from the latest settings synchronously: the settings Flow collector
        // is async, so on a fresh focus its first emission can land AFTER this method's clearSuggestionDisplay()
        // below — leaving the default row blank even though entries are configured. Re-parsing the already-held
        // currentSettings here makes the row correct immediately. (Bug C — empty custom row after newline/focus.)
        applyCustomSuggestions(currentSettings)

        val targetMode = KeyboardModeUtils.determineTargetMode(info, viewModel.state.value.currentMode)
        if (targetMode != viewModel.state.value.currentMode) {
            viewModel.onEvent(KeyboardEvent.ModeChanged(targetMode))
        }

        if (inputState.isSecureField) {
            clearSecureFieldState()
        } else if (!inputState.isUrlOrEmailField && !inputState.isTerminalField) {
            if (inputState.displayBuffer.isNotEmpty() || inputState.wordState.hasContent) {
                coordinateStateClear()
            } else {
                // Empty fresh field: nothing to clear, but the custom-suggestion default row must still be
                // shown when one is configured (coordinateStateClear would have done this via
                // clearInternalStateOnly). Without this the bar stays blank on first focus (Bug 3).
                inputState.clearSuggestionDisplay()
            }

            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            checkAutoCapitalization(textBefore)
        } else if (!inputState.isTerminalField) {
            if (inputState.displayBuffer.isNotEmpty()) {
                val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                }
            }

            try {
                outputBridge.finishComposingText()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "onStartInputView_finishComposing")
                )
            }
        }

        updateKeyboardForCurrentAction()

        // If the ＋登録 Activity just registered a new word, replace the still-typed reading with the
        // registered surface (しろいくま → 白い熊). Runs after the field/state setup above, with the input
        // connection live. (Japanese FIX 2 — register-then-insert.)
        applyPendingRegistrationReplacement()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            autofillCoordinator.onInputViewStarted(swipeKeyboardView != null)
        }
    }

    /**
     * Consume the one-shot registration signal set by [RegisterWordActivity] on a successful Save and, if the
     * text immediately before the cursor is exactly the registered reading, replace it with the registered
     * surface. The reading was committed (no longer composing) when the IME lost focus to the Activity, so a
     * plain [OutputBridge.finishComposingText] + surrounding-text guard is enough.
     *
     * The guard is deliberately strict: only delete when `getTextBeforeCursor(reading.length) == reading`. If
     * the user edited the reading in the Activity, moved the cursor, or focus landed in a different field, the
     * text won't match and NOTHING is deleted — the entry is still saved, we just don't touch the field.
     */
    private fun applyPendingRegistrationReplacement() {
        val (reading, surface) = scriptConverterRegistry.consumePendingRegistration() ?: return
        if (reading.isEmpty() || surface.isEmpty()) return
        try {
            outputBridge.beginBatchEdit()
            try {
                // Finalise any leftover composing region first so the surrounding-text read is committed text.
                outputBridge.finishComposingText()
                val before = outputBridge.safeGetTextBeforeCursor(reading.length)
                if (before == reading) {
                    outputBridge.deleteSurroundingText(reading.length, 0)
                    outputBridge.commitText(surface, 1)
                    coordinateStateClear()
                }
            } finally {
                outputBridge.endBatchEdit()
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "applyPendingRegistrationReplacement")
            )
        }
    }

    private fun updateKeyboardForCurrentAction() {
        viewModel.updateActionType(inputState.currentInputAction)
    }

    override fun onLetterInput(char: String, wasAutoShifted: Boolean, wasManualShifted: Boolean) {
        autofillCoordinator.onKeyInput()
        // Cluster keys carry punctuation/brackets/quotes/dashes as LETTER-typed flick characters (e.g. "-" is
        // a flick-up on the "—j_" key), so they arrive here and would be APPENDED to the centre-letter buffer
        // — showing underlined as a candidate ("long" + "-" -> garbage "litd-"; a lone "(" becoming a 1-char
        // composing candidate). A terminator on a cluster layout must NEVER join the buffer: redirect it to the
        // non-letter path, which commits any composing word's candidate first (if one's composing) and then
        // enters the mark directly with the right spacing — even when nothing is composing. (Bug C.)
        if (char.length == 1 &&
            inputState.clusterLayoutActive &&
            NonLetterInputHandler.isClusterTerminatorChar(char.single())
        ) {
            handleNonLetterInput(char)
            return
        }
        // Only the first letter of a word decides its casing flags. wasManualShifted is captured in
        // KeyEventRouter BEFORE the shift latch is cleared, so a mid-line manual Shift tap is honoured
        // and the candidate bar shows + commits the Capitalized form (Bug 2). Auto-shift and caps-lock
        // are handled by their own state and must NOT set the manual-shift flag.
        if (inputState.displayBuffer.isEmpty()) {
            inputState.isCurrentWordAtSentenceStart = wasAutoShifted
            inputState.isCurrentWordManualShifted = wasManualShifted
        }
        handleLetterInput(char)
    }

    override fun onNonLetterInput(char: String) {
        autofillCoordinator.onKeyInput()
        handleNonLetterInput(char)
    }

    override fun onBackspace() = handleBackspace()

    override fun onSpace() = handleSpace()

    override fun onEnterAction(imeAction: Int) {
        serviceScope.launch { performInputAction(imeAction) }
    }

    override fun onShift() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastShift = currentTime - inputState.lastShiftTime
        val currentState = viewModel.state.value

        when {
            timeSinceLastShift <= doubleShiftThreshold -> {
                viewModel.onEvent(KeyboardEvent.CapsLockToggled)
                viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
                inputState.lastShiftTime = 0
            }

            else -> {
                when {
                    currentState.isCapsLockOn -> viewModel.onEvent(KeyboardEvent.CapsLockToggled)
                    // Auto-capitalised (sentence start): Shift means "let me type lowercase here". Drop to
                    // lowercase AND suppress the immediate auto-cap re-check so it doesn't re-capitalise.
                    // Must be checked before the generic isShiftPressed branch (auto-shift also has shift
                    // latched). (Bug B.)
                    currentState.isAutoShift -> viewModel.dismissAutoShift()
                    !currentState.isShiftPressed -> viewModel.onEvent(KeyboardEvent.ShiftStateChanged(true))
                    currentState.isShiftPressed -> viewModel.onEvent(KeyboardEvent.ShiftStateChanged(false))
                }
                inputState.lastShiftTime = currentTime
            }
        }
    }

    override fun onCapsLock() {
        viewModel.onEvent(KeyboardEvent.CapsLockToggled)
    }

    override fun onModeSwitch(mode: KeyboardMode) {
        viewModel.onEvent(KeyboardEvent.ModeChanged(mode))
    }

    override fun onDakuten() {
        val buffer = inputState.displayBuffer
        if (buffer.isNotEmpty()) {
            val cycled = KanaTransformUtils.cycleDakutenOnLast(buffer)
            if (cycled != buffer) {
                inputState.updateDisplayBuffer(cycled)
                outputBridge.setComposingText(cycled, 1)
            }
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: return
        val transformed = KanaTransformUtils.cycleDakutenOnLast(before)
        if (transformed != before) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(transformed, 1)
            ic.endBatchEdit()
        }
    }

    override fun onSmallKana() {
        val buffer = inputState.displayBuffer
        if (buffer.isNotEmpty()) {
            val toggled = KanaTransformUtils.toggleSmallKanaOnLast(buffer)
            if (toggled != buffer) {
                inputState.updateDisplayBuffer(toggled)
                outputBridge.setComposingText(toggled, 1)
            }
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: return
        val transformed = KanaTransformUtils.toggleSmallKanaOnLast(before)
        if (transformed != before) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(transformed, 1)
            ic.endBatchEdit()
        }
    }

    override fun onNextCandidate() {
        japaneseCandidateHandler.onNextCandidate()
    }

    override fun onCommitCandidate() {
        japaneseCandidateHandler.onCommitCandidate()
    }

    override fun onHandakuten() {
        val buffer = inputState.displayBuffer
        if (buffer.isNotEmpty()) {
            val transformed = KanaTransformUtils.toHandakuten(buffer.last())?.let {
                buffer.dropLast(1) + it
            } ?: return
            inputState.updateDisplayBuffer(transformed)
            outputBridge.setComposingText(transformed, 1)
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: return
        val last = before.lastOrNull() ?: return
        val handakuten = KanaTransformUtils.toHandakuten(last) ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(handakuten.toString(), 1)
        ic.endBatchEdit()
    }

    override fun onEmoji() {
        candidateBarController.showEmojiPicker()
    }

    override fun onTab() {
        // Cluster typing: Tab ("tap") advances the highlighted candidate that Space will commit, rather
        // than emitting a literal tab. Falls back to a real KEYCODE_TAB when there's nothing to cycle.
        // No-prediction context (the GNU code layout, the manual no-prediction mode, secure/terminal fields):
        // Tab is ALWAYS a literal Tab — it must never cycle the static custom toolbar. The toolbar entries
        // there are tap-only. This keeps Tab-completion working in Emacs etc. on a GNU layout. (Mirrors Space,
        // which already emits a literal space in these contexts.)
        if (!inputState.isSuggestionsDisabled && !inputState.requiresDirectCommit) {
            if (inputState.clusterLayoutActive && inputState.pendingSuggestions.isNotEmpty()) {
                // Advance over exactly the candidates the bar is showing (as many as fit), wrapping around.
                val idx = candidateBarController.advanceSelection()
                if (idx >= 0) {
                    inputState.selectedCandidate = idx
                    return
                }
            }
            // Japanese: Tab advances the highlighted candidate that Enter commits, moving the bar highlight
            // without committing. The trailing ＋登録 affordance is skipped.
            if (suggestionPipeline.isJapaneseLayout && inputState.pendingSuggestions.isNotEmpty()) {
                advanceJapaneseCandidate()
                return
            }
        }
        // Commit any in-progress composing word first; commitText/sendKeyEvent would
        // otherwise replace the composing region instead of appending the tab.
        coordinateStateClear()
        // GNU "code mode": deliver a REAL Tab key event so apps like Emacs run their TAB command (completion,
        // indentation) rather than receiving a literal tab CHARACTER (which just self-inserts). Other contexts
        // keep the committed "\t" that normal text fields insert as a tab. (Raw/terminal fields already get a
        // real key event inside sendTab.) This is what makes Tab-completion work in GUI Emacs on a GNU layout.
        if (languageManager.currentLayoutLanguage.value.substringBefore("-") == "gnu") {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
        } else {
            outputBridge.sendTab()
        }
    }

    /**
     * Commit the currently highlighted Japanese candidate (the one Enter accepts): index 0 (best conversion)
     * by default, or the Space/Tab-advanced one. Routes through the same [handleSuggestionSelected] path a tap
     * uses, so the ＋登録 affordance opens the dialog, the reading→surface selection is learned, and the
     * surface is inserted with no trailing space. With no candidates yet (debounce window) it finalises the
     * raw kana.
     */
    private fun commitSelectedJapaneseCandidate() {
        val candidates = inputState.pendingSuggestions
        if (candidates.isEmpty()) {
            outputBridge.finishComposingText()
            coordinateStateClear()
            return
        }
        val idx = inputState.selectedCandidate.coerceIn(0, candidates.size - 1)
        handleSuggestionSelected(candidates[idx])
    }

    /**
     * Space or Tab on a Japanese layout: move the candidate-bar highlight to the next real candidate
     * (wrapping), skipping the trailing ＋登録 affordance, and mirror it into
     * [InputStateManager.selectedCandidate] so Enter commits exactly what is highlighted. Only the highlight
     * moves — the composing text is untouched until commit, so the commit's cursor math stays valid.
     */
    private fun advanceJapaneseCandidate() {
        val candidates = inputState.pendingSuggestions
        if (candidates.isEmpty()) return
        var idx = candidateBarController.advanceSelection()
        if (idx < 0) return
        var guard = 0
        while (guard < candidates.size &&
            suggestionPipeline.isJapaneseRegisterAffordance(candidates.getOrNull(idx).orEmpty())
        ) {
            idx = candidateBarController.advanceSelection()
            if (idx < 0) return
            guard++
        }
        inputState.selectedCandidate = idx
    }

    /**
     * Long-press Space (held, not slid) while cluster candidates are pending: insert a literal space,
     * committing the typed buffer as-is instead of the highlighted candidate. Returns true when consumed.
     */
    private fun handleSpaceLongPressLiteral(): Boolean {
        if (inputState.clusterLayoutActive &&
            (inputState.displayBuffer.isNotEmpty() || inputState.pendingSuggestions.isNotEmpty())
        ) {
            spaceInputHandler.handle(literalSpace = true)
            return true
        }
        return false
    }

    override fun onLanguageSwitch() {}

    private fun handleLetterInput(char: String) {
        japaneseCandidateHandler.reset()
        letterInputHandler.handle(char)
    }

    private fun handleNonLetterInput(char: String) = nonLetterInputHandler.handle(char)

    private fun handleSwipeWord(validatedWord: String) = swipeWordHandler.handle(validatedWord)

    /**
     * Apply a special-key shortcut ([SpecialTokens]) tapped on the toolbar or pressed as a layout key: a
     * cursor pair leaves the caret between the inserts, a date template expands, an action token runs the
     * editor action via [handleGnuAction].
     */
    private fun applySpecialToken(token: String) {
        serviceScope.launch {
            when (val effect = SpecialTokens.interpret(token)) {
                is SpecialTokens.Effect.Action ->
                    suggestionPipeline.coordinateSpecialAction { handleGnuAction(effect.name) }
                is SpecialTokens.Effect.Commit ->
                    suggestionPipeline.coordinateSpecialCommit(
                        effect.prefix,
                        effect.suffix,
                        moveCursorLeft = { sendKeyEventWithMeta(KeyEvent.KEYCODE_DPAD_LEFT, 0) },
                        checkAutoCapitalization = ::checkAutoCapitalization
                    )
            }
        }
    }

    private fun handleSuggestionSelected(suggestion: String) {
        serviceScope.launch {
            if (inputState.requiresDirectCommit) {
                return@launch
            }

            // A tapped custom-row entry commits its literal text (no spell-learn / bigram). It can never be
            // a real top prediction (isCustomSuggestion excludes words that are also live predictions), so
            // this only fires for the empty-buffer default row or the appended custom suffix.
            if (inputState.isCustomSuggestion(suggestion)) {
                // Toolbar special-key shortcuts (cursor pairs like “…”, [Paste], date templates) DO their
                // action; everything else commits its literal text as before.
                if (SpecialTokens.isSpecial(suggestion)) {
                    applySpecialToken(suggestion)
                } else {
                    suggestionPipeline.coordinateCustomSuggestionSelection(suggestion, ::checkAutoCapitalization)
                }
                return@launch
            }

            val replacementState = inputState.postCommitReplacementState
            if (replacementState != null) {
                suggestionPipeline.coordinatePostCommitReplacement(
                    suggestion,
                    replacementState,
                    ::checkAutoCapitalization
                )
                return@launch
            }

            if (suggestionPipeline.isJapaneseLayout) {
                // A tap on the trailing "＋登録" affordance opens the reading→surface registration dialog
                // instead of committing it as text. (Japanese FIX 2.)
                if (suggestionPipeline.isJapaneseRegisterAffordance(suggestion)) {
                    val reading = inputState.displayBuffer
                    // Open the registration screen as a real Activity (not an in-IME dialog): the keyboard
                    // keeps working there with no focus war, so the user can type the kanji into the surface
                    // field with this keyboard itself. (Japanese FIX 2 / BUG B.)
                    startActivity(RegisterWordActivity.intentForReading(this@UrikInputMethodService, reading))
                    return@launch
                }
                val reading = inputState.displayBuffer
                val rawSource = inputState.currentRawSuggestions
                    .firstOrNull { it.word.equals(suggestion, ignoreCase = true) }?.source
                if (rawSource == "learned" || rawSource == "dictionary") {
                    // Look up the converter by the active LAYOUT language ("ja"), not the primary language:
                    // when ja is active but not primary, currentLanguage() is e.g. "en" and forLanguage()
                    // would return null, so the selection would never be persisted. (Mirrors the lookup in
                    // requestJapaneseSuggestions.) (Japanese FIX 2.)
                    scriptConverterRegistry.forLanguage(languageManager.currentLayoutLanguage.value)
                        ?.recordSelection(reading, suggestion)
                }
            }

            suggestionPipeline.coordinateSuggestionSelection(suggestion, ::checkAutoCapitalization)
        }
    }

    private fun handleSuggestionRemoval(suggestion: String) {
        serviceScope.launch {
            try {
                textInputProcessor.removeSuggestion(suggestion)

                withContext(Dispatchers.Main) {
                    val currentSuggestions = inputState.removeSuggestionFromState(suggestion)
                    if (currentSuggestions.isNotEmpty()) {
                        candidateBarController.updateSuggestions(currentSuggestions)
                    } else {
                        // Custom-aware clear so the custom default row reappears after the last live
                        // suggestion is removed, instead of a blank bar. (Bug 1.)
                        inputState.clearSuggestionDisplay()
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "handleSuggestionRemoval")
                )
            }
        }
    }

    private suspend fun performInputAction(imeAction: Int) {
        try {
            // Japanese: Enter (確定) commits the highlighted candidate and is CONSUMED — accept the conversion,
            // no newline. A second Enter (nothing composing) then performs the field's real action. Space/Tab
            // move the highlight; Enter accepts it.
            if (suggestionPipeline.isJapaneseLayout && inputState.displayBuffer.isNotEmpty()) {
                commitSelectedJapaneseCandidate()
                return
            }

            if (!inputState.requiresDirectCommit && inputState.displayBuffer.isNotEmpty()) {
                val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
                val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

                if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                    coordinateStateClear()
                } else {
                    suggestionPipeline.coordinateWordCompletion()
                }
            }

            inputState.isActivelyEditing = true

            // Perform the field's Enter action WITHOUT ever committing a "\n"/" " (see EnterActionPerformer):
            // a committed newline is normalised to a SPACE by single-line fields (the "ac " bug). This is the
            // shared decision used by both the plain action-Enter key and the compass/flick Enter.
            performEnterAction(imeAction)

            coordinateStateClear()

            if (KeyboardModeUtils.shouldResetToLettersOnEnter(
                    viewModel.state.value.currentMode,
                    currentInputEditorInfo
                )
            ) {
                viewModel.onEvent(KeyboardEvent.ModeChanged(KeyboardMode.LETTERS))
            }

            if (imeAction == EditorInfo.IME_ACTION_NONE && !inputState.isTerminalField) {
                val textBefore = outputBridge.safeGetTextBeforeCursor(50)
                checkAutoCapitalization(textBefore)
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "performInputAction")
            )
            coordinateStateClear()
        }
    }

    /**
     * Performs the field's Enter action for an already-finished composing word, NEVER committing a "\n"/" ".
     * Shared by the plain action-Enter (via performInputAction) and the compass/flick Enter (handleGnuAction).
     */
    private fun performEnterAction(imeAction: Int) {
        EnterActionPerformer.perform(
            imeAction = imeAction,
            performEditorAction = { action -> outputBridge.performEditorAction(action) },
            sendDefaultEditorAction = { sendDefaultEditorAction(true) },
            sendEnterKeyEvent = { sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) }
        )
    }

    private fun handleBackspace() = backspaceHandler.handle()

    /**
     * Resolve the CURRENT layout language's custom-suggestion row (per-language override → built-in default)
     * and hand the parsed list to the input state.
     */
    private fun applyCustomSuggestions(settings: KeyboardSettings = currentSettings) {
        val lang = languageManager.currentLayoutLanguage.value
        val raw = CustomSuggestionDefaults.effective(lang, settings.customSuggestionsByLang)
        inputState.setCustomSuggestions(CustomSuggestionRow.parse(raw))
    }

    private fun handleSpace() = spaceInputHandler.handle()

    private fun handleSpacebarCursorMove(distance: Int) {
        if (inputState.requiresDirectCommit || !currentSettings.spacebarCursorControl) {
            return
        }

        try {
            val currentSelection = outputBridge.safeGetCursorPosition()
            val newPosition = (currentSelection + distance).coerceAtLeast(0)
            outputBridge.setSelection(newPosition, newPosition)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "handleSpacebarCursorMove")
            )
        }
    }

    private fun handleBackspaceSwipeDelete() {
        if (inputState.requiresDirectCommit || !currentSettings.backspaceSwipeDelete) {
            return
        }

        try {
            inputState.isActivelyEditing = true

            if (inputState.displayBuffer.isNotEmpty()) {
                val currentText = outputBridge.safeGetTextBeforeCursor(inputState.displayBuffer.length + 10)
                val expectedComposingText =
                    if (currentText.length >= inputState.displayBuffer.length) {
                        currentText.substring(maxOf(0, currentText.length - inputState.displayBuffer.length))
                    } else {
                        ""
                    }

                if (expectedComposingText == inputState.displayBuffer) {
                    outputBridge.beginBatchEdit()
                    try {
                        outputBridge.setComposingText("", 1)
                        coordinateStateClear()
                    } finally {
                        outputBridge.endBatchEdit()
                    }
                    return
                } else {
                    coordinateStateClear()
                }
            }

            val textBeforeCursor = outputBridge.safeGetTextBeforeCursor(50)
            if (textBeforeCursor.isEmpty()) {
                return
            }

            val lastChar = textBeforeCursor.last()

            if (Character.isWhitespace(lastChar)) {
                outputBridge.deleteSurroundingText(1, 0)
                coordinateStateClear()
                return
            }

            if (Character.isLetterOrDigit(lastChar)) {
                val wordInfo = BackspaceUtils.extractWordBeforeCursor(textBeforeCursor)
                if (wordInfo != null) {
                    val (word, _) = wordInfo
                    val shouldDeleteSpace = BackspaceUtils.shouldDeleteTrailingSpace(textBeforeCursor, word.length)
                    val deleteLength = BackspaceUtils.calculateDeleteLength(word.length, shouldDeleteSpace)
                    outputBridge.deleteSurroundingText(deleteLength, 0)
                    coordinateStateClear()
                }
                return
            }

            var idx = textBeforeCursor.length
            while (idx > 0 &&
                !Character.isLetterOrDigit(textBeforeCursor[idx - 1]) &&
                !Character.isWhitespace(textBeforeCursor[idx - 1])
            ) {
                idx--
            }
            val trailingPunctuationCount = textBeforeCursor.length - idx

            if (idx > 0 && !Character.isWhitespace(textBeforeCursor[idx - 1])) {
                val textBeforePunctuation = textBeforeCursor.take(idx)
                val wordInfo = BackspaceUtils.extractWordBeforeCursor(textBeforePunctuation)
                if (wordInfo != null) {
                    val (word, _) = wordInfo
                    val shouldDeleteSpace = BackspaceUtils.shouldDeleteTrailingSpace(textBeforePunctuation, word.length)
                    val deleteLength = BackspaceUtils.calculateDeleteLength(word.length, shouldDeleteSpace)
                    outputBridge.deleteSurroundingText(deleteLength + trailingPunctuationCount, 0)
                    coordinateStateClear()
                    return
                }
            }

            outputBridge.deleteSurroundingText(trailingPunctuationCount, 0)
            coordinateStateClear()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "handleBackspaceSwipeDelete")
            )
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)

        // Don't leave the expandable candidates pane open across a field/keyboard dismissal.
        swipeKeyboardView?.hideCandidatesPane()

        if (::layoutManager.isInitialized) {
            layoutManager.stopAcceleratedBackspace()
        }

        observerJobs.forEach { it.cancel() }
        observerJobs.clear()

        suggestionPipeline.cancelDebounceJob()
        candidateBarController.hideEmojiPicker()
        dismissClipboardPanel()

        if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }

        if (inputState.displayBuffer.isNotEmpty() && !inputState.requiresDirectCommit) {
            val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
            val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

            if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                coordinateStateClear()
            } else {
                try {
                    inputState.isActivelyEditing = true
                    val wordToCommit = inputState.displayBuffer.ifEmpty { inputState.wordState.buffer }
                    if (wordToCommit.isNotEmpty()) {
                        outputBridge.finishComposingText()
                    }
                    coordinateStateClear()
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "UrikInputMethodService",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "onFinishInputView_commitWord")
                    )
                    coordinateStateClear()
                }
            }
        }

        try {
            outputBridge.finishComposingText()
            coordinateStateClear()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "onFinishInputView_finishComposing")
            )
            coordinateStateClear()
        }

        if (currentSettings.resetToLettersOnDismiss) {
            viewModel.resetToLetters()
        }

        autofillCoordinator.onInputViewFinished()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetCycle()

        if (::layoutManager.isInitialized) {
            layoutManager.stopAcceleratedBackspace()
        }

        autofillCoordinator.onFieldChanged(
            inputType = attribute?.inputType ?: 0,
            imeOptions = attribute?.imeOptions ?: 0,
            fieldId = attribute?.fieldId ?: 0,
            packageHash = attribute?.packageName?.hashCode() ?: 0
        )
        inputState.selectionStateTracker.reset()
        coordinateStateClear()

        inputState.lastSpaceTime = 0
        inputState.lastShiftTime = 0
        inputState.lastCommittedWord = ""
        inputState.lastKnownCursorPosition = -1

        applyFieldTypeFromEditorInfo(attribute)

        restoreLayoutLanguageForApp(attribute?.packageName)

        if (inputState.isSecureField) {
            clearSecureFieldState()
        } else if (!inputState.isUrlOrEmailField && !inputState.isTerminalField) {
            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            checkAutoCapitalization(textBefore)
        }
    }

    /**
     * Per-app layout memory: if a layout language was previously remembered for [packageName],
     * switch to it (no-op when it already matches or is no longer active).
     */
    private fun restoreLayoutLanguageForApp(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        // A hardware-keymap layout (NexDock etc.) stays active across apps — on a physical keyboard the user
        // doesn't want it swapped out per-app, which would silently drop the remapping.
        if (::viewModel.isInitialized && viewModel.layout.value?.hardwareKeymap == true) return
        serviceScope.launch {
            try {
                val remembered = settingsRepository.getPerAppLayoutLanguage(packageName) ?: return@launch
                if (remembered == languageManager.currentLayoutLanguage.value) return@launch
                if (remembered !in languageManager.activeLanguages.value) return@launch

                if (languageManager.switchLayoutLanguage(remembered).isSuccess) {
                    updateScriptContext(ULocale.forLanguageTag(remembered))
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UrikInputMethodService",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "restoreLayoutLanguageForApp")
                )
            }
        }
    }

    private fun applyFieldTypeFromEditorInfo(info: EditorInfo?) {
        val c = InputFieldClassifier.classify(info)
        inputState.isSecureField = c.isSecureField
        inputState.isDirectCommitField = c.isDirectCommitField
        inputState.isRawKeyEventField = c.isRawKeyEventField
        inputState.isTerminalField = c.isTerminalField
        if (inputState.isTerminalField) viewModel.disableAutoCapForTerminalField()
        inputState.currentInputAction = c.currentInputAction
        inputState.isUrlOrEmailField = c.isUrlOrEmailField
        // The manual "no-prediction mode" forces suggestions off in every field.
        // GNU is a permanent "code mode": never predict/autocorrect/auto-cap, in any field.
        val isGnuLayout = languageManager.currentLayoutLanguage.value.substringBefore("-") == "gnu"
        inputState.isSuggestionsDisabled =
            c.isSuggestionsDisabled || currentSettings.forceNoPredict || isGnuLayout
    }

    // ---- Hardware-keyboard remapping -----------------------------------------------------------------
    // Map a physical keyboard's keys onto the active layout's grid by position (its rows correspond to the
    // function / number / qwerty / home / bottom / modifier rows of a standard board). Active only when the
    // current layout is a `hardwareKeymap` (e.g. "NexDock XL"); otherwise physical keys behave normally.
    private val hwKeymap: Map<Int, Pair<Int, Int>> by lazy {
        buildMap {
            fun row(r: Int, vararg codes: Int) {
                codes.forEachIndexed { c, code -> if (code != 0) put(code, r to c) }
            }
            row(
                0, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3,
                KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7,
                KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11,
                KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_FORWARD_DEL
            )
            row(
                1, KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
                KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7,
                KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_MINUS,
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_DEL
            )
            row(
                2, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E,
                KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U,
                KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_LEFT_BRACKET,
                KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH
            )
            row(
                3, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D,
                KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J,
                KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE,
                KeyEvent.KEYCODE_ENTER
            )
            row(
                4, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C,
                KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M,
                KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_SHIFT_RIGHT
            )
            row(
                5, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT
            )
            // The up/down half-key shares the up-slot's column.
            put(KeyEvent.KEYCODE_DPAD_DOWN, 5 to 8)
        }
    }

    // A pending combining diacritic (Czech ´ ˇ) armed by a hardware-keymap dead key: the next character key
    // composes with it (´ then a → á, ˇ then c → č). Mirrors KeyEventRouter.pendingDeadKey but on the
    // physical-key path, which commits straight to the field and never reaches the soft dispatch chain.
    private var hwPendingDeadKey: Char? = null

    // META bitmask of the command modifiers (Ctrl/Alt/Meta) a hardware-keymap layout currently holds — driven
    // by the layout, NOT the OS metaState, so a physical Ctrl/Alt key reassigned to a letter types the letter
    // (no stray Ctrl on the next key) and any key assigned a Ctrl/Alt/Meta action becomes that modifier at its
    // position. Shift stays native (see handleHardwareRemap). Cleared per-key in onKeyUp and wholesale in
    // onFinishInput so a missed release can't stick.
    private var hwHeldModifiers = 0

    /** The active layout for the physical-key path — the StateFlow value, falling back to the rendered one. */
    private fun activeHwLayout(): KeyboardLayout? =
        (if (::viewModel.isInitialized) viewModel.layout.value else null)
            ?: layoutManager.takeIf { ::layoutManager.isInitialized }?.effectiveLayout

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleHardwareRemap(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        releaseHeldModifier(keyCode, event) || super.onKeyUp(keyCode, event)

    /**
     * Map a physical key to whatever the hardware-keymap layout puts at its position — a character, a command
     * modifier, an action, or a soft action — so EVERY physical key (Ctrl and Alt included) is reassignable.
     * Returns true if handled. Modifier state is read from the layout-driven latch, never the OS metaState, so
     * a Ctrl/Alt key reassigned to a letter just types the letter. Shift stays native so Shift+navigation and
     * the caps interplay keep working.
     */
    private fun handleHardwareRemap(keyCode: Int, event: KeyEvent): Boolean {
        // Source the layout from the viewModel (a StateFlow), NOT the rendered view, so remapping keeps working
        // when the soft keyboard is hidden (the point of a NexDock: a physical keyboard + external monitor).
        val layout = activeHwLayout() ?: return false
        if (!layout.hardwareKeymap || !isPhysicalKeyboardEvent(event)) return false
        val (r, c) = hwKeymap[keyCode] ?: return dropDeadKeyUnlessModifier(keyCode)
        val key = layout.rows.getOrNull(r)?.getOrNull(c) ?: return dropDeadKeyUnlessModifier(keyCode)

        // 1) A layout command modifier (Ctrl/Alt/Meta) on this key — latch it; this is how relocation works
        //    (Caps assigned Ctrl) AND how a kept Ctrl works (driven by the layout, not the OS). A physical
        //    modifier key keeps going native too (so apps reading real modifier key events still see it); a
        //    relocated one is consumed so its own meaning (Caps Lock, a letter) is suppressed.
        modifierFlagOf(key).takeIf { it != 0 }?.let { flag ->
            hwHeldModifiers = hwHeldModifiers or flag
            hwPendingDeadKey = null
            // Pass the event native ONLY when the physical key's own modifier matches what the layout assigns
            // here (so apps still see a real modifier key); otherwise consume, so a reassigned modifier — a
            // physical Ctrl set to Alt, or Caps set to Ctrl — doesn't leak its native modifier alongside the
            // synthesized one.
            return physicalModifierFlag(keyCode) != flag
        }

        val command = hwHeldModifiers
        val shift = event.isShiftPressed
        val meta = command or (if (shift) KeyEvent.META_SHIFT_ON else 0)

        // 2) A character key — a chord under a command modifier (Ctrl + the "g" key → C-g), else dead-key + text.
        remappedChar(key, shift, event.isCapsLockOn)?.let { ch ->
            if (command != 0) {
                hwPendingDeadKey = null
                // remappedChar only returns non-null for a single-char FlickKey, so the cast is safe; the chord
                // uses the unshifted base char's keycode (shift rides `meta`).
                return emitCharChord((key as KeyboardKey.FlickKey).center[0], meta)
            }
            val pending = hwPendingDeadKey
            if (pending != null) {
                hwPendingDeadKey = null
                DeadKeys.compose(ch, pending)?.let { c2 -> outputBridge.sendCharacter(c2); return true }
                // No precomposed form: emit the spacing diacritic literally, then fall through to emit / re-arm.
                outputBridge.sendCharacter(DeadKeys.spacingFor(pending))
            }
            DeadKeys.combiningFor(ch)?.let { hwPendingDeadKey = it; return true }
            outputBridge.sendCharacter(ch)
            return true
        }

        // 3) An action key (Enter/Space/Backspace/Tab/Esc/arrows) — emit its keycode so a REASSIGNED key fires
        //    the action (physical Alt assigned Enter → Enter) and command/Shift combine (C-Space, S-Tab). A
        //    key already on its native keycode with no command modifier passes through natively (unchanged).
        actionKeyCode(key)?.let { kc ->
            if (kc == keyCode && command == 0) return dropDeadKeyUnlessModifier(keyCode)
            hwPendingDeadKey = null
            sendChordKeyEvent(kc, meta)
            return true
        }

        // 4) A soft-keyboard action with no key event (hide, language switch, editor ops) — route it through the
        //    GNU handler so a reassigned physical key can trigger it too.
        softActionName(key)?.let {
            hwPendingDeadKey = null
            handleGnuAction(it)
            return true
        }

        // 5) Anything else (F-keys, Caps Lock, bare labels) — the native physical key.
        return dropDeadKeyUnlessModifier(keyCode)
    }

    /** Clear a latched command modifier when its physical key is released; mirrors handleHardwareRemap step 1. */
    private fun releaseHeldModifier(keyCode: Int, event: KeyEvent): Boolean {
        val layout = activeHwLayout() ?: return false
        if (!layout.hardwareKeymap || !isPhysicalKeyboardEvent(event)) return false
        val (r, c) = hwKeymap[keyCode] ?: return false
        val key = layout.rows.getOrNull(r)?.getOrNull(c) ?: return false
        val flag = modifierFlagOf(key)
        if (flag == 0) return false
        hwHeldModifiers = hwHeldModifiers and flag.inv()
        return physicalModifierFlag(keyCode) != flag
    }

    /** The modifier a physical key natively IS (Ctrl/Alt/Shift/Meta), as a META bit, or 0. */
    private fun physicalModifierFlag(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON
        else -> 0
    }

    /**
     * An action / unmapped physical key reached the remap while a dead key may be armed. Anything that isn't a
     * modifier (space, enter, arrows…) drops the pending mark, matching the soft path. Always returns false so
     * the system still handles the key normally.
     */
    private fun dropDeadKeyUnlessModifier(keyCode: Int): Boolean {
        if (!KeyEvent.isModifierKey(keyCode)) hwPendingDeadKey = null
        return false
    }

    /**
     * The command modifier (Ctrl/Alt/Meta) a layout key holds while pressed, as a META bit, or 0. Shift is NOT
     * here — it stays native so Shift+navigation and the caps interplay keep working; only the command
     * modifiers are layout-driven (and thus relocatable / reassignable).
     */
    private fun modifierFlagOf(key: KeyboardKey?): Int = when (key) {
        is KeyboardKey.FlickKey -> when ((key.bindings["center"] as? KeyboardKey.FlickBinding.Action)?.name) {
            "ctrl" -> KeyEvent.META_CTRL_ON
            "alt" -> KeyEvent.META_ALT_ON
            "meta", "super" -> KeyEvent.META_META_ON
            else -> 0
        }
        else -> 0
    }

    /** Soft-keyboard binding actions (no key event) a reassigned physical key may trigger via [handleGnuAction]. */
    private val hwSoftActions =
        setOf("hide", "next_language", "undo", "redo", "cut", "copy", "paste", "select_all")

    private fun softActionName(key: KeyboardKey): String? =
        ((key as? KeyboardKey.FlickKey)?.bindings?.get("center") as? KeyboardKey.FlickBinding.Action)
            ?.name?.takeIf { it in hwSoftActions }

    /** Send a Ctrl/Alt/Meta chord for the REMAPPED base char [ch] (Ctrl + the key that types "g" → C-g). */
    private fun emitCharChord(ch: Char, meta: Int): Boolean {
        val (kc, charNeedsShift) = keycodeForChar(ch) ?: return false
        val command = meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON)
        val shift = if ((meta and KeyEvent.META_SHIFT_ON) != 0 || charNeedsShift) KeyEvent.META_SHIFT_ON else 0
        sendChordKeyEvent(kc, command or shift)
        return true
    }

    /** The (keyCode, needsShift) that types [ch] on a virtual keyboard, or null if it has no plausible event. */
    private fun keycodeForChar(ch: Char): Pair<Int, Boolean>? {
        val events = virtualKeyCharMap.getEvents(charArrayOf(ch)) ?: return null
        val down = events.firstOrNull {
            it.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(it.keyCode)
        } ?: return null
        return down.keyCode to down.isShiftPressed
    }

    /** The keycode a layout action / centre-bound binding sends under a modifier (C-Space, C-←), or null. */
    private fun actionKeyCode(key: KeyboardKey): Int? = when (key) {
        is KeyboardKey.Action -> when (key.action) {
            KeyboardKey.ActionType.SPACE -> KeyEvent.KEYCODE_SPACE
            KeyboardKey.ActionType.ENTER -> KeyEvent.KEYCODE_ENTER
            KeyboardKey.ActionType.BACKSPACE -> KeyEvent.KEYCODE_DEL
            KeyboardKey.ActionType.TAB -> KeyEvent.KEYCODE_TAB
            else -> null
        }
        is KeyboardKey.FlickKey -> when ((key.bindings["center"] as? KeyboardKey.FlickBinding.Action)?.name) {
            "space" -> KeyEvent.KEYCODE_SPACE
            "enter" -> KeyEvent.KEYCODE_ENTER
            "backspace" -> KeyEvent.KEYCODE_DEL
            "tab" -> KeyEvent.KEYCODE_TAB
            "escape" -> KeyEvent.KEYCODE_ESCAPE
            "arrow_left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "arrow_right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "arrow_up" -> KeyEvent.KEYCODE_DPAD_UP
            "arrow_down" -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> null
        }
        else -> null
    }

    /**
     * Send one [keyCode] down/up stamped with [metaState] (Ctrl/Alt/Meta/Shift). Unlike [sendKeyEventWithMeta]
     * it does NOT wrap the key in synthetic modifier down/up events: the modifier rides the event's metaState,
     * which is what apps (terminals included) read — correct whether the modifier is physically held or a
     * relocated latch, and avoiding a spurious "modifier released" mid-chord when it is physically held.
     */
    private fun sendChordKeyEvent(keyCode: Int, metaState: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
    }

    private fun isPhysicalKeyboardEvent(event: KeyEvent): Boolean {
        if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
        val device = InputDevice.getDevice(event.deviceId) ?: return false
        return device.supportsSource(InputDevice.SOURCE_KEYBOARD) &&
            device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    /**
     * The character a layout [key] emits under the effective [shiftActive]/[capsOn] state — or null if the key
     * isn't a plain single-character key (function / action / binding keys, and multi-char labels, pass
     * through). A letter honours Caps (shift XOR caps); a symbol only Shift. The explicit shifted face wins.
     */
    private fun remappedChar(key: KeyboardKey, shiftActive: Boolean, capsOn: Boolean): String? {
        if (key !is KeyboardKey.FlickKey || key.bindings.isNotEmpty()) return null
        val base = key.center
        if (base.length != 1) return null
        val useShifted = if (base[0].isLetter()) shiftActive != capsOn else shiftActive
        return if (useShifted) key.shifted?.center ?: base.uppercase() else base
    }

    override fun onFinishInput() {
        super.onFinishInput()

        hwPendingDeadKey = null
        hwHeldModifiers = 0

        if (::layoutManager.isInitialized) {
            layoutManager.stopAcceleratedBackspace()
        }

        suggestionPipeline.cancelDebounceJob()
        candidateBarController.hideEmojiPicker()
        dismissClipboardPanel()

        if (inputState.displayBuffer.isNotEmpty() && !inputState.requiresDirectCommit) {
            val actualTextBefore = outputBridge.safeGetTextBeforeCursor(1)
            val actualTextAfter = outputBridge.safeGetTextAfterCursor(1)

            if (actualTextBefore.isEmpty() && actualTextAfter.isEmpty()) {
                coordinateStateClear()
            } else {
                try {
                    inputState.isActivelyEditing = true
                    val wordToCommit = inputState.displayBuffer.ifEmpty { inputState.wordState.buffer }
                    if (wordToCommit.isNotEmpty()) {
                        outputBridge.finishComposingText()
                    }
                    coordinateStateClear()
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "UrikInputMethodService",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "onFinishInput_commitWord")
                    )
                    coordinateStateClear()
                }
            }
        }

        try {
            outputBridge.finishComposingText()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UrikInputMethodService",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "onFinishInput_finishComposing")
            )
        }

        coordinateStateClear()

        if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        onUpdateSelectionHandler.handle(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        streamingScoringEngine.cancelActiveGesture()

        val currentDensity = resources.displayMetrics.density

        if (lastDisplayDensity != 0f && lastDisplayDensity != currentDensity) {
            layoutManager.onDensityChanged()
            swipeDetector.updateDisplayMetrics(currentDensity)
            swipeKeyboardView?.let { view ->
                if (view.currentLayout != null && view.currentState != null) {
                    view.updateKeyboard(view.currentLayout!!, view.currentState!!)
                }
            }
        }

        lastDisplayDensity = currentDensity

        postureDetector?.onConfigurationChanged()

        val currentKeyboard = newConfig.keyboard
        if (lastKeyboardConfig != android.content.res.Configuration.KEYBOARD_UNDEFINED &&
            lastKeyboardConfig != currentKeyboard
        ) {
            updateInputViewShown()
        }
        lastKeyboardConfig = currentKeyboard
    }

    @Suppress("NewApi")
    @SuppressLint("RestrictedApi")
    override fun onCreateInlineSuggestionsRequest(uiExtras: android.os.Bundle): InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val theme = themeManager.currentTheme.value
        val density = resources.displayMetrics.density

        val keyHeight = resources.getDimensionPixelSize(R.dimen.key_height)
        val suggestionTextSize = (keyHeight * 0.30f / density).coerceIn(13f, 16f)

        val chipPadding = (8 * density).toInt()

        val stylesBuilder = UiVersions.newStylesBuilder()
        val style =
            InlineSuggestionUi
                .newStyleBuilder()
                .setSingleIconChipStyle(
                    ViewStyle
                        .Builder()
                        .setBackgroundColor(theme.colors.suggestionBarBackground)
                        .setPadding(0, 0, 0, 0)
                        .build()
                ).setChipStyle(
                    ViewStyle
                        .Builder()
                        .setBackgroundColor(theme.colors.suggestionBarBackground)
                        .setPadding(chipPadding, 0, chipPadding, 0)
                        .build()
                ).setTitleStyle(
                    TextViewStyle
                        .Builder()
                        .setTextColor(theme.colors.suggestionText)
                        .setTextSize(suggestionTextSize)
                        .build()
                ).setSubtitleStyle(
                    TextViewStyle
                        .Builder()
                        .setTextColor(theme.colors.suggestionText)
                        .setTextSize(suggestionTextSize * 0.9f)
                        .build()
                ).build()

        stylesBuilder.addStyle(style)
        val stylesBundle = stylesBuilder.build()

        val specs = mutableListOf<InlinePresentationSpec>()
        repeat(MAX_PASSWORD_INLINE_SUGGESTIONS) {
            val minSize = Size((80 * density).toInt(), (40 * density).toInt())
            val maxSize = Size((400 * density).toInt(), (40 * density).toInt())

            val spec =
                InlinePresentationSpec
                    .Builder(minSize, maxSize)
                    .setStyle(stylesBundle)
                    .build()
            specs.add(spec)
        }

        val iconMinSize = Size((32 * density).toInt(), (32 * density).toInt())
        val iconMaxSize = Size((48 * density).toInt(), (40 * density).toInt())
        specs.add(
            InlinePresentationSpec
                .Builder(iconMinSize, iconMaxSize)
                .setStyle(stylesBundle)
                .build()
        )

        return InlineSuggestionsRequest
            .Builder(specs)
            .setMaxSuggestionCount(MAX_PASSWORD_INLINE_SUGGESTIONS + 1)
            .build()
    }

    @Suppress("NewApi")
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return autofillCoordinator.onInlineSuggestionsResponse(response, swipeKeyboardView != null)
    }

    @Suppress("NewApi")
    private fun inflateAndDisplaySuggestions(suggestions: List<InlineSuggestion>) {
        val density = resources.displayMetrics.density
        val size = Size((150 * density).toInt(), (40 * density).toInt())

        serviceScope.launch(Dispatchers.Main) {
            val views = mutableListOf<View>()
            for (suggestion in suggestions.take(MAX_PASSWORD_INLINE_SUGGESTIONS)) {
                val view = inflateSuggestionView(suggestion, size)
                if (view != null) views.add(view)
            }
            if (views.isNotEmpty()) {
                candidateBarController.updateInlineAutofillSuggestions(views, true)
            }
        }
    }

    @Suppress("NewApi")
    private suspend fun inflateSuggestionView(suggestion: InlineSuggestion, size: Size): View? = try {
        suspendCancellableCoroutine { continuation ->
            suggestion.inflate(this@UrikInputMethodService, size, mainExecutor) { view ->
                if (continuation.isActive) {
                    continuation.resume(view)
                }
            }
        }
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "UrikInputMethodService",
            severity = ErrorLogger.Severity.LOW,
            exception = e,
            context = mapOf("operation" to "inflateSuggestionView")
        )
        null
    }

    override fun onDestroy() {
        unlockReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Throwable) {
            }
            unlockReceiver = null
        }
        streamingScoringEngine.cancelActiveGesture()
        wordFrequencyRepository.clearCache()
        autofillCoordinator.cleanup()

        serviceJob.cancel()

        observerJobs.forEach { it.cancel() }
        observerJobs.clear()

        postureDetector?.stop()
        postureDetector = null

        coordinateStateClear()
        dismissClipboardPanel()
        clipboardPanel = null
        swipeKeyboardView = null
        adaptiveContainer = null

        if (::layoutManager.isInitialized) {
            layoutManager.cleanup()
        }

        cacheMemoryManager.cleanup()

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private companion object {
        const val DOUBLE_SHIFT_THRESHOLD_MS = 400L
        // Gap between the hide and the re-show of the "reshow" action — long enough for the hide to register
        // before the show, so the framework treats it as a fresh show (and re-pins the window height).
        const val RESHOW_REOPEN_DELAY_MS = 150L
        const val LOOK_SEED_PREFS = "kxkb_look_seed"
        const val LOOK_SEED_LAST_GEO = "last_geo"
        const val LOOK_SEED_KNOBS_PREFIX = "knobs_"
    }
}

internal fun computeFilteredLayout(layout: KeyboardLayout, showNumberRow: Boolean): KeyboardLayout = when {
    !showNumberRow &&
        layout.mode == KeyboardMode.LETTERS &&
        layout.rows.isNotEmpty() &&
        isTopRowANumberRow(layout.rows[0]) -> {
        layout.copy(rows = layout.rows.drop(1))
    }

    layout.mode in listOf(KeyboardMode.SYMBOLS, KeyboardMode.SYMBOLS_SECONDARY) &&
        layout.rows.isNotEmpty() -> {
        val numberRow =
            listOf(
                KeyboardKey.Character("1", KeyboardKey.KeyType.NUMBER),
                KeyboardKey.Character("2", KeyboardKey.KeyType.NUMBER),
                KeyboardKey.Character("3", KeyboardKey.KeyType.NUMBER),
                KeyboardKey.Character("4", KeyboardKey.KeyType.NUMBER),
                KeyboardKey.Character("5", KeyboardKey.KeyType.NUMBER),
                KeyboardKey.Character("6", KeyboardKey.KeyType.NUMBER),
                KeyboardKey.Character("7", KeyboardKey.KeyType.NUMBER),
                KeyboardKey.Character("8", KeyboardKey.KeyType.NUMBER),
                KeyboardKey.Character("9", KeyboardKey.KeyType.NUMBER),
                KeyboardKey.Character("0", KeyboardKey.KeyType.NUMBER)
            )
        layout.copy(rows = listOf(numberRow) + layout.rows)
    }

    else -> layout
}

internal fun isTopRowANumberRow(row: List<KeyboardKey>): Boolean {
    val charKeys = row.filterIsInstance<KeyboardKey.Character>()
    return charKeys.size == 10 && charKeys.all { it.type == KeyboardKey.KeyType.NUMBER }
}
