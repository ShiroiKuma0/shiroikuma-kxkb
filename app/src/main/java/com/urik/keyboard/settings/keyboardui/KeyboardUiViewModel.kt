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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Edits the look knobs for the CURRENT (app·layout·geometry) combo — the app·layout the keyboard was last
 * shown in (published by the IME as [SettingsRepository.currentSizeTarget]) plus the selected geometry. So a
 * height / split / colour / mode change here sticks to that one layout, per app and per geometry, and never
 * bleeds into others — matching the on-keyboard resize gesture. Falls back to the per-geometry baseline only
 * when no real combo has been published yet. Writes live-apply via the look-store flow the IME observes.
 *
 * The "Apply to all keyboards" toggle ([applyEverywhere], default ON) replaces the per-combo write with a
 * store-wide fan-out of just the touched field: style knobs to every baseline and combo, dimension knobs
 * confined to the selected geometry (see [SettingsRepository.applyLookDeltaEverywhere]).
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

    // The legacy single custom-suggestion row (English's value / migration source).
    val customSuggestions: StateFlow<String> =
        settingsRepository.settings
            .map { it.customSuggestions }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ""
            )

    // Per-language custom-suggestion overrides — each language's row is edited individually in the UI.
    val customSuggestionsByLang: StateFlow<Map<String, String>> =
        settingsRepository.settings
            .map { it.customSuggestionsByLang }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyMap()
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

    // The "Apply to all keyboards" toggle (default ON): edits fan out via the repository instead of forking
    // only the current combo. Eager so [persist] can read .value before the fragment's collector attaches.
    val applyEverywhere: StateFlow<Boolean> =
        settingsRepository.lookApplyEverywhere
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = true
            )

    fun updateApplyEverywhere(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository
                .setLookApplyEverywhere(enabled)
                .onFailure { _events.emit(SettingsEvent.Error.KeyboardUiUpdateFailed) }
        }
    }

    // The keyboard display mode for the current combo (per app·layout·geometry). Backed by [current] — refreshed
    // whenever the combo/geometry is (re)loaded or a mode is picked — so the picker reflects THIS layout's mode.
    private val _displayMode = MutableStateFlow(KeyboardDisplayMode.STANDARD)
    val keyboardDisplayMode: StateFlow<KeyboardDisplayMode> = _displayMode.asStateFlow()

    /**
     * Pick the keyboard mode for the current (app·layout·geometry) combo: store it as a per-combo look-override
     * knob (via [persist]) so it sticks to THIS layout only. Also retires the legacy global mode scalars, so a
     * value left by an older build can't bleed into layouts with no mode of their own. The running keyboard
     * re-resolves its live mode from the per-combo override the IME observes.
     */
    fun updateKeyboardDisplayMode(mode: KeyboardDisplayMode) {
        persist(current.copy(displayMode = mode))
        viewModelScope.launch {
            settingsRepository.updateKeyboardDisplayMode(null)
            settingsRepository.updateOneHandedModeEnabled(false)
        }
    }

    // The last REAL app's size-override key (`app|layoutId|geometry`), published by the IME — the target for
    // the "Reset layout to default" button (the app the user was typing in, not the settings app).
    @Volatile private var sizeTarget: String? = null

    init {
        // Follow the live geometry AND the live app·layout combo as ONE paired stream. These used to be two
        // independent collectors, and the sizeTarget one re-selected with `_uiState.value.geometry` — which,
        // on the racy first emission, was still the FOLDED_PORT data-class default. Its load completed last,
        // stamped the UI onto folded_port, and every slider then wrote to `app|layout|folded_port` while the
        // live keyboard resolved `app|layout|<real geometry>` — no slider had any visible effect. Pairing via
        // combine means the geometry used is always the IME-published live one, never a stale UI default.
        viewModelScope.launch {
            combine(
                settingsRepository.currentGeometry,
                settingsRepository.currentSizeTarget
            ) { geometry, target -> geometry to target }
                .collect { (geometry, target) ->
                    sizeTarget = target
                    selectGeometry(geometry ?: _uiState.value.geometry)
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

    // The in-flight selectGeometry load; cancelled by the next call so a slower, older read can never land
    // after a newer one and clobber `current`/the UI with stale values.
    private var loadJob: Job? = null

    fun selectGeometry(geometry: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Show the EFFECTIVE look for this combo: the frozen per-geometry baseline (the old shared defaults)
            // overlaid by the per-combo override (the resize gesture's size + any prior per-combo edit). The UI
            // no longer writes the baseline, so overlaying it here keeps the sliders in step with the on-screen
            // preview and, on the first edit, folds those values into the combo so it stays self-contained.
            val baseline = settingsRepository.getGeometryBaselineLook(geometry) ?: KeyboardLookKnobs()
            val combo = comboFor(geometry)
            val override = if (combo != null) {
                settingsRepository.getSizeOverride(combo.first, combo.second, combo.third) ?: KeyboardLookKnobs()
            } else {
                KeyboardLookKnobs()
            }
            val knobs = baseline.overlay(override)
            current = knobs
            _displayMode.value = knobs.displayMode ?: KeyboardDisplayMode.STANDARD
            _uiState.value = knobs.toUiState(geometry)
        }
    }

    /** The current real (app·layout) published by the IME + [geometry] → the per-combo look key; null pre-publish. */
    private fun comboFor(geometry: String): Triple<String, String, String>? {
        val parts = sizeTarget?.split("|") ?: return null
        if (parts.size != 3) return null
        return Triple(parts[0], parts[1], geometry)
    }

    fun updateCornerRadius(dp: Int) = persist(current.copy(cornerRadiusDp = dp.toFloat()))

    fun updateBorderWidth(dp: Int) = persist(current.copy(keyBorderWidthDp = dp.toFloat()))

    fun updateBold(on: Boolean) = persist(current.copy(boldKeyLabels = on))

    fun updateFontScale(pct: Int) = persist(current.copy(keyFontScale = pct / 100f))

    fun updatePrimaryOffsetX(dp: Int) = persist(current.copy(primaryOffsetXDp = dp.toFloat()))

    fun updatePrimaryOffsetY(dp: Int) = persist(current.copy(primaryOffsetYDp = dp.toFloat()))

    fun updateCompassFontScale(pct: Int) = persist(current.copy(compassFontScale = pct / 100f))

    fun updateExtraStripFontScale(pct: Int) = persist(current.copy(extraStripFontScale = pct / 100f))

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

    /** Save one language's custom-suggestion row. */
    fun updateCustomSuggestionsForLanguage(lang: String, raw: String) {
        viewModelScope.launch {
            settingsRepository
                .updateCustomSuggestionsForLanguage(lang, raw)
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

    /**
     * Escape hatch: reset THIS geometry's size knobs to a known-usable state — a large height, full width, no
     * split, no bottom-lift, no per-axis key gaps and no per-row height tweaks — so a resize/look that left the
     * keyboard unusable can be recovered from the settings screen. Colours/fonts are preserved.
     */
    /**
     * "Reset layout to default": clear the size override for the app the user was last typing in (published by
     * the IME as `app|layoutId|geometry`, NOT the settings app), so that app reverts to the default size while
     * every other app keeps its own. Returns the target app's package for the confirmation toast, or null if
     * there's nothing to reset.
     */
    fun resetCurrentApp(): String? {
        val parts = sizeTarget?.split("|") ?: return null
        if (parts.size != 3) return null
        val (app, layoutId, geometry) = parts
        viewModelScope.launch {
            settingsRepository
                .clearSizeOverride(app, layoutId, geometry)
                .onFailure { _events.emit(SettingsEvent.Error.KeyboardUiUpdateFailed) }
        }
        return app
    }

    private fun persist(updated: KeyboardLookKnobs) {
        val geometry = _uiState.value.geometry
        val before = current
        current = updated
        _displayMode.value = updated.displayMode ?: KeyboardDisplayMode.STANDARD
        _uiState.value = updated.toUiState(geometry)
        viewModelScope.launch {
            val result = if (applyEverywhere.value) {
                // Apply-to-all: fan ONLY the touched field(s) out — style knobs to every geometry's baseline
                // and every stored combo, dimension knobs to this geometry's keys. Not the full effective
                // snapshot, which would blast this combo's sizes onto every keyboard.
                settingsRepository.applyLookDeltaEverywhere(updated.changedFrom(before), geometry)
            } else {
                val combo = comboFor(geometry)
                if (combo != null) {
                    settingsRepository.updateSizeOverride(combo.first, combo.second, combo.third, updated)
                } else {
                    // No real app·layout has been published yet — fall back to the per-geometry baseline.
                    settingsRepository.updateGeometryBaselineLook(geometry, updated)
                }
            }
            result.onFailure { _events.emit(SettingsEvent.Error.KeyboardUiUpdateFailed) }
        }
    }

    private fun KeyboardLookKnobs.toUiState(geometry: String) = KeyboardUiUiState(
        geometry = geometry,
        cornerRadiusDp = cornerRadiusDp?.toInt() ?: 0,
        keyBorderWidthDp = keyBorderWidthDp?.toInt() ?: 1,
        boldKeyLabels = boldKeyLabels ?: true,
        keyFontScalePct = ((keyFontScale ?: 1f) * 100).toInt(),
        primaryOffsetXDp = (primaryOffsetXDp ?: 0f).toInt(),
        primaryOffsetYDp = (primaryOffsetYDp ?: 0f).toInt(),
        compassFontScalePct = ((compassFontScale ?: 2f) * 100).toInt(),
        extraStripFontScalePct = ((extraStripFontScale ?: 2.5f) * 100).toInt(),
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
    val primaryOffsetXDp: Int = 0,
    val primaryOffsetYDp: Int = 0,
    val compassFontScalePct: Int = 200,
    val extraStripFontScalePct: Int = 250,
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
