package com.urik.keyboard.settings.keyboardui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.service.GeometryBucket
import com.urik.keyboard.service.KeyboardLookKnobs
import com.urik.keyboard.service.LibraryLook
import com.urik.keyboard.settings.SettingsEvent
import com.urik.keyboard.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    // The app-wide Library-screen look, edited on the same page (not per-geometry).
    private val _libraryState = MutableStateFlow(LibraryLook().toLibraryUiState())
    val libraryState: StateFlow<LibraryUiState> = _libraryState.asStateFlow()
    private var currentLibrary = LibraryLook()

    // The custom-suggestion row contents (a global setting), edited next to the suggestion-bar look.
    val customSuggestions: StateFlow<String> =
        settingsRepository.settings
            .map { it.customSuggestions }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ""
            )

    // The key-preview bubble toggle (a global setting, surfaced in the Keys section). Default OFF.
    val keyPreviewEnabled: StateFlow<Boolean> =
        settingsRepository.settings
            .map { it.keyPreviewEnabled }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = true
            )

    // The keyboard display mode (a global setting, not per-geometry) — read from the SAME two settings
    // keys the one-handed toggle and the space-slide Actions menu drive, resolved exactly as
    // KeyboardModeManager.determineMode does: one-handed-enabled wins (left/right from keyboardDisplayMode),
    // else an explicit SPLIT, else STANDARD.
    val keyboardDisplayMode: StateFlow<KeyboardDisplayMode> =
        settingsRepository.settings
            .map { settings ->
                when {
                    settings.oneHandedModeEnabled ->
                        if (settings.keyboardDisplayMode == KeyboardDisplayMode.ONE_HANDED_RIGHT) {
                            KeyboardDisplayMode.ONE_HANDED_RIGHT
                        } else {
                            KeyboardDisplayMode.ONE_HANDED_LEFT
                        }

                    settings.keyboardDisplayMode == KeyboardDisplayMode.SPLIT -> KeyboardDisplayMode.SPLIT
                    settings.keyboardDisplayMode == KeyboardDisplayMode.FLOATING -> KeyboardDisplayMode.FLOATING
                    else -> KeyboardDisplayMode.STANDARD
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = KeyboardDisplayMode.STANDARD
            )

    /**
     * Persist a manual keyboard mode through the SAME two SettingsRepository keys
     * ([SettingsRepository.updateOneHandedModeEnabled] + [SettingsRepository.updateKeyboardDisplayMode])
     * that the one-handed toggle and `KeyboardModeManager.setManualMode` write — so the picker, the toggle
     * and the space-slide Actions menu stay one source of truth. The running keyboard re-resolves its live
     * mode from these via the settings flow it already observes.
     */
    fun updateKeyboardDisplayMode(mode: KeyboardDisplayMode) {
        viewModelScope.launch {
            when (mode) {
                KeyboardDisplayMode.STANDARD -> {
                    // Clear the persisted explicit mode too, so a previously saved FLOATING / SPLIT (or a
                    // stale one-handed L/R) isn't re-resolved by KeyboardModeManager.determineMode.
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

    init {
        // Follow the geometry the running keyboard is actually using (published by the IME service),
        // so rotating/folding while the keyboard is shown moves the selector + sliders to that bucket.
        viewModelScope.launch {
            settingsRepository.currentGeometry.collect { live ->
                if (live != null && live != _uiState.value.geometry) selectGeometry(live)
            }
        }
        viewModelScope.launch {
            currentLibrary = settingsRepository.getLibraryLook()
            _libraryState.value = currentLibrary.toLibraryUiState()
        }
    }

    fun updateLibSeparatorColor(c: Int) = persistLibrary(currentLibrary.copy(separatorColor = c))
    fun updateLibSeparatorThickness(dp: Int) = persistLibrary(currentLibrary.copy(separatorThicknessDp = dp))
    fun updateLibRowSpacing(dp: Int) = persistLibrary(currentLibrary.copy(rowSpacingDp = dp))
    fun updateLibIndent(dp: Int) = persistLibrary(currentLibrary.copy(indentDp = dp))
    fun updateLibHeadingFont(f: String) = persistLibrary(currentLibrary.copy(headingFont = f))
    fun updateLibHeadingWeight(w: Int) = persistLibrary(currentLibrary.copy(headingWeight = w))
    fun updateLibHeadingSize(sp: Int) = persistLibrary(currentLibrary.copy(headingSizeSp = sp))
    fun updateLibHeadingColor(c: Int) = persistLibrary(currentLibrary.copy(headingColor = c))
    fun updateLibNameFont(f: String) = persistLibrary(currentLibrary.copy(nameFont = f))
    fun updateLibNameWeight(w: Int) = persistLibrary(currentLibrary.copy(nameWeight = w))
    fun updateLibNameSize(sp: Int) = persistLibrary(currentLibrary.copy(nameSizeSp = sp))
    fun updateLibNameColor(c: Int) = persistLibrary(currentLibrary.copy(nameColor = c))
    fun updateLibBadgeFont(f: String) = persistLibrary(currentLibrary.copy(badgeFont = f))
    fun updateLibBadgeWeight(w: Int) = persistLibrary(currentLibrary.copy(badgeWeight = w))
    fun updateLibBadgeSize(sp: Int) = persistLibrary(currentLibrary.copy(badgeSizeSp = sp))
    fun updateLibBadgeColor(c: Int) = persistLibrary(currentLibrary.copy(badgeColor = c))

    private fun persistLibrary(updated: LibraryLook) {
        currentLibrary = updated
        _libraryState.value = updated.toLibraryUiState()
        viewModelScope.launch {
            settingsRepository.updateLibraryLook(updated)
                .onFailure { _events.emit(SettingsEvent.Error.KeyboardUiUpdateFailed) }
        }
    }

    private fun LibraryLook.toLibraryUiState() = LibraryUiState(
        separatorColor = separatorColor ?: LibraryLook.DEF_SEPARATOR_COLOR,
        separatorThicknessDp = separatorThicknessDp ?: LibraryLook.DEF_SEPARATOR_THICKNESS,
        rowSpacingDp = rowSpacingDp ?: LibraryLook.DEF_ROW_SPACING,
        indentDp = indentDp ?: LibraryLook.DEF_INDENT,
        headingFont = headingFont ?: "",
        headingWeight = headingWeight ?: LibraryLook.DEF_HEADING_WEIGHT,
        headingSizeSp = headingSizeSp ?: LibraryLook.DEF_HEADING_SIZE,
        headingColor = headingColor ?: LibraryLook.DEF_HEADING_COLOR,
        nameFont = nameFont ?: "",
        nameWeight = nameWeight ?: LibraryLook.DEF_NAME_WEIGHT,
        nameSizeSp = nameSizeSp ?: LibraryLook.DEF_NAME_SIZE,
        nameColor = nameColor ?: LibraryLook.DEF_NAME_COLOR,
        badgeFont = badgeFont ?: "",
        badgeWeight = badgeWeight ?: LibraryLook.DEF_BADGE_WEIGHT,
        badgeSizeSp = badgeSizeSp ?: LibraryLook.DEF_BADGE_SIZE,
        badgeColor = badgeColor ?: LibraryLook.DEF_BADGE_COLOR
    )

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

    fun updateTopRowHeight(pct: Int) = persist(current.copy(topRowHeightScale = pct / 100f))

    fun updateBottomRowHeight(pct: Int) = persist(current.copy(bottomRowHeightScale = pct / 100f))

    fun updateKeySpacingH(pct: Int) = persist(current.copy(keySpacingHScale = pct / 100f))

    fun updateKeySpacingV(pct: Int) = persist(current.copy(keySpacingVScale = pct / 100f))

    fun updateWidth(pct: Int) = persist(current.copy(keyboardWidthScale = pct / 100f))

    // The slider value is the split gap in dp (0..MAX_SPLIT_GAP_DP); splitFraction is that over the max.
    fun updateSplitFraction(dp: Int) =
        persist(current.copy(splitFraction = dp / KeyboardLookKnobs.MAX_SPLIT_GAP_DP))

    fun updateBottomLift(dp: Int) = persist(current.copy(bottomLiftDp = dp.toFloat()))

    fun updateHintScale(pct: Int) = persist(current.copy(hintScale = pct / 100f))

    fun updateHintColor(c: Int) = persist(current.copy(hintColor = c))

    fun updateHintFont(family: String) = persist(current.copy(hintFont = family))

    fun updateHintWeight(w: Int) = persist(current.copy(hintWeight = w))

    fun updateFontFamily(family: String) = persist(current.copy(fontFamily = family))

    fun updateKeyboardBgColor(c: Int) = persist(current.copy(keyboardBgColor = c))

    fun updateKeyBgColor(c: Int) = persist(current.copy(keyBgColor = c))

    fun updateFunctionalKeyBgColor(c: Int) = persist(current.copy(functionalKeyBgColor = c))

    fun updateKeyTextColor(c: Int) = persist(current.copy(keyTextColor = c))

    fun updateKeyBorderColor(c: Int) = persist(current.copy(keyBorderColor = c))

    fun updateCapsLockShiftColor(c: Int) = persist(current.copy(capsLockShiftColor = c))

    fun updateLabelWeight(w: Int) = persist(current.copy(labelWeight = w))

    fun updateHintTopColor(c: Int) = persist(current.copy(hintTopColor = c))

    fun updateHintTopScale(pct: Int) = persist(current.copy(hintTopScale = pct / 100f))

    fun updateHintTopFont(family: String) = persist(current.copy(hintTopFont = family))

    fun updateHintTopDistance(dp: Int) = persist(current.copy(hintTopMarginDp = dp.toFloat()))

    fun updateHintBottomColor(c: Int) = persist(current.copy(hintBottomColor = c))

    fun updateHintBottomScale(pct: Int) = persist(current.copy(hintBottomScale = pct / 100f))

    fun updateHintBottomFont(family: String) = persist(current.copy(hintBottomFont = family))

    fun updateHintBottomDistance(dp: Int) = persist(current.copy(hintBottomMarginDp = dp.toFloat()))

    fun updateHintLeftDistance(dp: Int) = persist(current.copy(hintLeftMarginDp = dp.toFloat()))

    fun updateHintRightDistance(dp: Int) = persist(current.copy(hintRightMarginDp = dp.toFloat()))

    fun updateClusterLeft(dp: Int) = persist(current.copy(clusterLeftOffsetDp = dp.toFloat()))

    fun updateClusterRight(dp: Int) = persist(current.copy(clusterRightOffsetDp = dp.toFloat()))

    fun updateSuggestionHeight(pct: Int) = persist(current.copy(suggestionBarHeightScale = pct / 100f))

    fun updateSuggestionBgColor(c: Int) = persist(current.copy(suggestionBgColor = c))

    fun updateSuggestionFont(family: String) = persist(current.copy(suggestionFont = family))

    fun updateSuggestionWeight(w: Int) = persist(current.copy(suggestionWeight = w))

    fun updateSuggestionSize(pct: Int) = persist(current.copy(suggestionTextScale = pct / 100f))

    fun updateSuggestionColor(c: Int) = persist(current.copy(suggestionColor = c))

    fun updateCustomSuggestions(raw: String) {
        viewModelScope.launch {
            settingsRepository
                .updateCustomSuggestions(raw)
                .onFailure { _events.emit(SettingsEvent.Error.CustomSuggestionsUpdateFailed) }
        }
    }

    fun updateKeyPreviewEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository
                .updateKeyPreviewEnabled(enabled)
                .onFailure { _events.emit(SettingsEvent.Error.KeyboardUiUpdateFailed) }
        }
    }

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
        topRowHeightPct = ((topRowHeightScale ?: 1f) * 100).toInt(),
        bottomRowHeightPct = ((bottomRowHeightScale ?: 1f) * 100).toInt(),
        keySpacingHPct = ((keySpacingHScale ?: 1f) * 100).toInt(),
        keySpacingVPct = ((keySpacingVScale ?: 1f) * 100).toInt(),
        keyboardWidthPct = ((keyboardWidthScale ?: 1f) * 100).toInt(),
        splitFractionPct = ((splitFraction ?: 0f) * KeyboardLookKnobs.MAX_SPLIT_GAP_DP).toInt(),
        bottomLiftDp = (bottomLiftDp ?: 0f).toInt(),
        hintScalePct = ((hintScale ?: 1f) * 100).toInt(),
        hintColor = hintColor ?: DEFAULT_HINT,
        hintFont = hintFont ?: "",
        hintWeight = hintWeight ?: 400,
        fontFamily = fontFamily ?: "",
        keyboardBgColor = keyboardBgColor ?: DEFAULT_KEYBOARD_BG,
        keyBgColor = keyBgColor ?: DEFAULT_KEY_BG,
        functionalKeyBgColor = functionalKeyBgColor ?: DEFAULT_KEY_BG,
        keyTextColor = keyTextColor ?: DEFAULT_KEY_TEXT,
        keyBorderColor = keyBorderColor ?: DEFAULT_KEY_BORDER,
        capsLockShiftColor = capsLockShiftColor ?: DEFAULT_KEY_TEXT,
        labelWeight = labelWeight ?: if (boldKeyLabels == false) 400 else 700,
        hintTopColor = hintTopColor ?: DEFAULT_KEY_TEXT,
        hintTopScalePct = ((hintTopScale ?: 1f) * 100).toInt(),
        hintTopFont = hintTopFont ?: "",
        hintTopDistanceDp = (hintTopMarginDp ?: 4f).toInt(),
        hintBottomColor = hintBottomColor ?: DEFAULT_KEY_TEXT,
        hintBottomScalePct = ((hintBottomScale ?: 1f) * 100).toInt(),
        hintBottomFont = hintBottomFont ?: "",
        hintBottomDistanceDp = (hintBottomMarginDp ?: 4f).toInt(),
        hintLeftDistanceDp = (hintLeftMarginDp ?: 4f).toInt(),
        hintRightDistanceDp = (hintRightMarginDp ?: 4f).toInt(),
        clusterLeftDp = (clusterLeftOffsetDp ?: 0f).toInt(),
        clusterRightDp = (clusterRightOffsetDp ?: 0f).toInt(),
        suggestionHeightPct = ((suggestionBarHeightScale ?: 1f) * 100).toInt(),
        suggestionBgColor = suggestionBgColor ?: DEFAULT_KEYBOARD_BG,
        suggestionFont = suggestionFont ?: "",
        suggestionWeight = suggestionWeight ?: 400,
        suggestionSizePct = ((suggestionTextScale ?: 1f) * 100).toInt(),
        suggestionColor = suggestionColor ?: DEFAULT_KEY_TEXT
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5000L

        // Effective fallbacks for the colour pickers/swatches — 白い熊's HighContrastYellow look.
        const val DEFAULT_KEYBOARD_BG = 0xFF000000.toInt()
        const val DEFAULT_KEY_BG = 0xFF000000.toInt()
        const val DEFAULT_KEY_TEXT = 0xFFFFFF00.toInt()
        const val DEFAULT_KEY_BORDER = 0xFFFFFF00.toInt()

        // Secondary chars default to the key text colour dimmed to ~70% (matches the renderer fallback).
        const val DEFAULT_HINT = 0xB4FFFF00.toInt()
    }
}

data class KeyboardUiUiState(
    val geometry: String = GeometryBucket.FOLDED_PORT.key,
    val cornerRadiusDp: Int = 0,
    val keyBorderWidthDp: Int = 1,
    val boldKeyLabels: Boolean = true,
    val keyFontScalePct: Int = 100,
    val keyHeightScalePct: Int = 100,
    val topRowHeightPct: Int = 100,
    val bottomRowHeightPct: Int = 100,
    val keySpacingHPct: Int = 100,
    val keySpacingVPct: Int = 100,
    val keyboardWidthPct: Int = 100,
    val splitFractionPct: Int = 0,
    val bottomLiftDp: Int = 0,
    val hintScalePct: Int = 100,
    val hintColor: Int = 0xB4FFFF00.toInt(),
    val hintFont: String = "",
    val hintWeight: Int = 400,
    val fontFamily: String = "",
    val keyboardBgColor: Int = 0xFF000000.toInt(),
    val keyBgColor: Int = 0xFF000000.toInt(),
    val functionalKeyBgColor: Int = 0xFF000000.toInt(),
    val keyTextColor: Int = 0xFFFFFF00.toInt(),
    val keyBorderColor: Int = 0xFFFFFF00.toInt(),
    val capsLockShiftColor: Int = 0xFFFFFF00.toInt(),
    val labelWeight: Int = 700,
    val hintTopColor: Int = 0xFFFFFF00.toInt(),
    val hintTopScalePct: Int = 100,
    val hintTopFont: String = "",
    val hintTopDistanceDp: Int = 4,
    val hintBottomColor: Int = 0xFFFFFF00.toInt(),
    val hintBottomScalePct: Int = 100,
    val hintBottomFont: String = "",
    val hintBottomDistanceDp: Int = 4,
    val hintLeftDistanceDp: Int = 4,
    val hintRightDistanceDp: Int = 4,
    val clusterLeftDp: Int = 0,
    val clusterRightDp: Int = 0,
    val suggestionHeightPct: Int = 100,
    val suggestionBgColor: Int = 0xFF000000.toInt(),
    val suggestionFont: String = "",
    val suggestionWeight: Int = 400,
    val suggestionSizePct: Int = 100,
    val suggestionColor: Int = 0xFFFFFF00.toInt()
)

data class LibraryUiState(
    val separatorColor: Int = LibraryLook.DEF_SEPARATOR_COLOR,
    val separatorThicknessDp: Int = LibraryLook.DEF_SEPARATOR_THICKNESS,
    val rowSpacingDp: Int = LibraryLook.DEF_ROW_SPACING,
    val indentDp: Int = LibraryLook.DEF_INDENT,
    val headingFont: String = "",
    val headingWeight: Int = LibraryLook.DEF_HEADING_WEIGHT,
    val headingSizeSp: Int = LibraryLook.DEF_HEADING_SIZE,
    val headingColor: Int = LibraryLook.DEF_HEADING_COLOR,
    val nameFont: String = "",
    val nameWeight: Int = LibraryLook.DEF_NAME_WEIGHT,
    val nameSizeSp: Int = LibraryLook.DEF_NAME_SIZE,
    val nameColor: Int = LibraryLook.DEF_NAME_COLOR,
    val badgeFont: String = "",
    val badgeWeight: Int = LibraryLook.DEF_BADGE_WEIGHT,
    val badgeSizeSp: Int = LibraryLook.DEF_BADGE_SIZE,
    val badgeColor: Int = LibraryLook.DEF_BADGE_COLOR
)
