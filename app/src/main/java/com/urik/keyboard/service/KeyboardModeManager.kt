package com.urik.keyboard.service

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.VisibleForTesting
import com.urik.keyboard.di.ApplicationScope
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.model.KeyboardModeConfig
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class KeyboardModeManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    private val _currentMode = MutableStateFlow(KeyboardModeConfig.standard())
    val currentMode: StateFlow<KeyboardModeConfig> = _currentMode.asStateFlow()

    // The display mode resolved for the CURRENT (app·layout·geometry) combo, pushed by the IME from the per-
    // combo look override on every show / layout change. It's the authoritative mode — determineMode prefers
    // it over the legacy global scalar, so a mode set for one layout never bleeds into another. Null = inherit.
    private val _comboMode = MutableStateFlow<KeyboardDisplayMode?>(null)

    /** Publish the per-(app·layout·geometry) display mode for the combo the keyboard is currently showing. */
    fun setComboMode(mode: KeyboardDisplayMode?) {
        _comboMode.value = mode
    }

    private var collectionJob: Job? = null
    private var currentPostureInfo: PostureInfo? = null
    private var latestSettings: KeyboardSettings? = null

    private val density: Float
        get() = context.resources.displayMetrics.density

    fun initialize(scope: CoroutineScope, postureDetector: PostureDetector) {
        collectionJob?.cancel()

        // Seed the mode WITH real dimensions synchronously, from the live posture, before the async collector
        // below ever runs. Without this the first cold-start input view is built dimension-less (the initial
        // _currentMode has null adaptiveDimensions) — it renders at the layout manager's default key height
        // and only grows a frame later when the collector emits, by which point the IME window is already
        // sized short, clipping the keyboard to "half" on the first show after a process start / app update.
        currentPostureInfo = postureDetector.postureInfo.value
        _currentMode.value =
            determineMode(latestSettings ?: KeyboardSettings(), postureDetector.postureInfo.value, _comboMode.value)

        collectionJob =
            scope.launch {
                combine(
                    settingsRepository.settings,
                    postureDetector.postureInfo,
                    _comboMode
                ) { settings, postureInfo, comboMode ->
                    latestSettings = settings
                    currentPostureInfo = postureInfo
                    determineMode(settings, postureInfo, comboMode)
                }.collect { config ->
                    _currentMode.value = config
                }
            }
    }

    @VisibleForTesting
    internal fun determineMode(
        settings: KeyboardSettings,
        postureInfo: PostureInfo,
        comboMode: KeyboardDisplayMode? = null
    ): KeyboardModeConfig {
        val dimensions = AdaptiveDimensions.compute(
            postureInfo = postureInfo,
            keySize = settings.keySize,
            density = density
        )

        // The per-(app·layout·geometry) mode wins; the legacy global keyboard_display_mode / one-handed
        // scalars are only a fallback (so pre-migration installs and the existing tests still resolve). The
        // per-combo write clears the global scalar the first time any layout's mode is set, so it can't bleed.
        val effective = comboMode ?: settings.keyboardDisplayMode

        val oneHanded = effective == KeyboardDisplayMode.ONE_HANDED_LEFT ||
            effective == KeyboardDisplayMode.ONE_HANDED_RIGHT ||
            (comboMode == null && settings.oneHandedModeEnabled)
        if (oneHanded) {
            val base = if (effective == KeyboardDisplayMode.ONE_HANDED_RIGHT) {
                KeyboardModeConfig.oneHandedRight(postureInfo.screenWidthPx)
            } else {
                KeyboardModeConfig.oneHandedLeft()
            }
            return base.copy(adaptiveDimensions = dimensions)
        }

        // Floating is an explicit mode, checked BEFORE the landscape-compact override so the floating panel
        // keeps floating in landscape too (it sizes itself from its own persisted rect).
        if (effective == KeyboardDisplayMode.FLOATING) {
            return KeyboardModeConfig.floating().copy(adaptiveDimensions = dimensions)
        }

        val isLandscapeCompact = postureInfo.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            postureInfo.sizeClass == DeviceSizeClass.COMPACT
        if (isLandscapeCompact) {
            return KeyboardModeConfig.standard().copy(adaptiveDimensions = dimensions)
        }

        // Manual split (the Mode picker / space-slide), now honoured per combo. Auto-split stays disabled:
        // the wide compass layouts (e.g. GNU) assume a single full-width keyboard.
        if (effective == KeyboardDisplayMode.SPLIT) {
            return KeyboardModeConfig(
                mode = KeyboardDisplayMode.SPLIT,
                widthFactor = 1.0f,
                splitGapPx = dimensions.splitGapPx,
                adaptiveDimensions = dimensions
            )
        }

        return KeyboardModeConfig.standard().copy(adaptiveDimensions = dimensions)
    }

    fun setManualMode(mode: KeyboardDisplayMode) {
        val postureInfo = currentPostureInfo ?: return
        val settings = latestSettings ?: KeyboardSettings()

        val dimensions = AdaptiveDimensions.compute(
            postureInfo = postureInfo,
            keySize = settings.keySize,
            density = density
        )

        _currentMode.value =
            when (mode) {
                KeyboardDisplayMode.STANDARD ->
                    KeyboardModeConfig.standard().copy(adaptiveDimensions = dimensions)

                KeyboardDisplayMode.ONE_HANDED_LEFT ->
                    KeyboardModeConfig.oneHandedLeft().copy(adaptiveDimensions = dimensions)

                KeyboardDisplayMode.ONE_HANDED_RIGHT ->
                    KeyboardModeConfig.oneHandedRight(postureInfo.screenWidthPx)
                        .copy(adaptiveDimensions = dimensions)

                KeyboardDisplayMode.SPLIT ->
                    KeyboardModeConfig(
                        mode = KeyboardDisplayMode.SPLIT,
                        widthFactor = 1.0f,
                        splitGapPx = dimensions.splitGapPx,
                        adaptiveDimensions = dimensions
                    )

                KeyboardDisplayMode.FLOATING ->
                    KeyboardModeConfig.floating().copy(adaptiveDimensions = dimensions)
            }

        applicationScope.launch {
            withContext(Dispatchers.IO) {
                when (mode) {
                    KeyboardDisplayMode.STANDARD -> {
                        // Clear the persisted explicit mode too, so a previously saved FLOATING (or a stale
                        // one-handed L/R) isn't re-resolved by determineMode on the next settings emission.
                        settingsRepository.updateKeyboardDisplayMode(null)
                        settingsRepository.updateOneHandedModeEnabled(false)
                    }

                    KeyboardDisplayMode.ONE_HANDED_LEFT,
                    KeyboardDisplayMode.ONE_HANDED_RIGHT
                    -> {
                        settingsRepository.updateKeyboardDisplayMode(mode)
                        settingsRepository.updateOneHandedModeEnabled(true)
                    }

                    KeyboardDisplayMode.SPLIT,
                    KeyboardDisplayMode.FLOATING
                    -> {
                        settingsRepository.updateKeyboardDisplayMode(mode)
                        settingsRepository.updateOneHandedModeEnabled(false)
                    }
                }
            }
        }
    }
}
