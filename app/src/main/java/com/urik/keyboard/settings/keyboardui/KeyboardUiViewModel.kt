package com.urik.keyboard.settings.keyboardui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.service.GeometryBucket
import com.urik.keyboard.service.KeyboardLookKnobs
import com.urik.keyboard.settings.SettingsEvent
import com.urik.keyboard.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Edits the **per-geometry baseline** look knobs (the `*|*|geometry` layer of the look store). The per-
 * (language·layout) fork is done on-keyboard by the resize gesture (1C); the settings Activity can't know
 * the live combo, so it tunes the geometry baseline only. Writes live-apply via the look-store flow the
 * IME service observes.
 */
@HiltViewModel
class KeyboardUiViewModel
@Inject
constructor(private val settingsRepository: SettingsRepository) : ViewModel() {
    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    private val _uiState = MutableStateFlow(KeyboardUiUiState())
    val uiState: StateFlow<KeyboardUiUiState> = _uiState.asStateFlow()

    /** The stored baseline (nullable fields) for the selected geometry, edited field-by-field. */
    private var current = KeyboardLookKnobs()

    init {
        // Follow the geometry the running keyboard is actually using (published by the IME service),
        // so rotating/folding while the keyboard is shown moves the selector + sliders to that bucket.
        viewModelScope.launch {
            settingsRepository.currentGeometry.collect { live ->
                if (live != null && live != _uiState.value.geometry) selectGeometry(live)
            }
        }
    }

    fun selectGeometry(geometry: String) {
        viewModelScope.launch {
            val knobs = settingsRepository.getGeometryBaselineLook(geometry) ?: KeyboardLookKnobs()
            current = knobs
            _uiState.value = knobs.toUiState(geometry)
        }
    }

    fun updateCornerRadius(dp: Int) = persist(current.copy(cornerRadiusDp = dp.toFloat()))

    fun updateBorderWidth(dp: Int) = persist(current.copy(keyBorderWidthDp = dp.toFloat()))

    fun updateBold(on: Boolean) = persist(current.copy(boldKeyLabels = on))

    fun updateFontScale(pct: Int) = persist(current.copy(keyFontScale = pct / 100f))

    fun updateHeightScale(pct: Int) = persist(current.copy(keyHeightScale = pct / 100f))

    fun updateKeySpacing(pct: Int) = persist(current.copy(keySpacingScale = pct / 100f))

    private fun persist(updated: KeyboardLookKnobs) {
        val geometry = _uiState.value.geometry
        current = updated
        _uiState.value = updated.toUiState(geometry)
        viewModelScope.launch {
            settingsRepository
                .updateGeometryBaselineLook(geometry, updated)
                .onFailure { _events.emit(SettingsEvent.Error.KeyboardUiUpdateFailed) }
        }
    }

    private fun KeyboardLookKnobs.toUiState(geometry: String) = KeyboardUiUiState(
        geometry = geometry,
        cornerRadiusDp = cornerRadiusDp?.toInt() ?: 0,
        keyBorderWidthDp = keyBorderWidthDp?.toInt() ?: 1,
        boldKeyLabels = boldKeyLabels ?: true,
        keyFontScalePct = ((keyFontScale ?: 1f) * 100).toInt(),
        keyHeightScalePct = ((keyHeightScale ?: 1f) * 100).toInt(),
        keySpacingPct = ((keySpacingScale ?: 1f) * 100).toInt()
    )
}

data class KeyboardUiUiState(
    val geometry: String = GeometryBucket.FOLDED_PORT.key,
    val cornerRadiusDp: Int = 0,
    val keyBorderWidthDp: Int = 1,
    val boldKeyLabels: Boolean = true,
    val keyFontScalePct: Int = 100,
    val keyHeightScalePct: Int = 100,
    val keySpacingPct: Int = 100
)
