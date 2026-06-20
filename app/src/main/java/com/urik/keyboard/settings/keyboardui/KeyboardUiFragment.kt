package com.urik.keyboard.settings.keyboardui

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.urik.keyboard.R
import com.urik.keyboard.service.GeometryBucket
import com.urik.keyboard.service.KeyboardFonts
import com.urik.keyboard.settings.SettingsEventHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * "Keyboard UI" settings — per-geometry size & look sliders. Edits the per-geometry baseline knobs in the
 * look store (see [KeyboardUiViewModel]); changes live-apply to the running keyboard via the store flow.
 */
@AndroidEntryPoint
class KeyboardUiFragment : PreferenceFragmentCompat() {
    private lateinit var viewModel: KeyboardUiViewModel
    private lateinit var eventHandler: SettingsEventHandler

    private lateinit var geometryPref: ListPreference
    private lateinit var localePref: ListPreference
    private lateinit var heightPref: SeekBarPreference
    private lateinit var topRowHeightPref: SeekBarPreference
    private lateinit var bottomRowHeightPref: SeekBarPreference
    private lateinit var widthPref: SeekBarPreference
    private lateinit var splitPref: SeekBarPreference
    private lateinit var liftPref: SeekBarPreference
    private lateinit var spacingHPref: SeekBarPreference
    private lateinit var spacingVPref: SeekBarPreference
    private lateinit var fontPref: SeekBarPreference
    private lateinit var hintPref: SeekBarPreference
    private lateinit var hintFontPref: Preference
    private lateinit var hintWeightPref: SeekBarPreference
    private lateinit var hintColorPref: ColorSwatchPreference
    private lateinit var fontFamilyPref: Preference
    private lateinit var cornerPref: SeekBarPreference
    private lateinit var borderPref: SeekBarPreference
    private lateinit var weightPref: SeekBarPreference
    private lateinit var keyboardBgColorPref: ColorSwatchPreference
    private lateinit var keyBgColorPref: ColorSwatchPreference
    private lateinit var functionalKeyBgColorPref: ColorSwatchPreference
    private lateinit var keyTextColorPref: ColorSwatchPreference
    private lateinit var keyBorderColorPref: ColorSwatchPreference
    private lateinit var capsLockShiftColorPref: ColorSwatchPreference
    private lateinit var hintTopColorPref: ColorSwatchPreference
    private lateinit var hintTopScalePref: SeekBarPreference
    private lateinit var hintTopFontPref: Preference
    private lateinit var hintBottomColorPref: ColorSwatchPreference
    private lateinit var hintBottomScalePref: SeekBarPreference
    private lateinit var hintBottomFontPref: Preference
    // Compass-key positions.
    private lateinit var topRowPositionPref: SeekBarPreference
    private lateinit var bottomRowPositionPref: SeekBarPreference
    private lateinit var leftColumnPositionPref: SeekBarPreference
    private lateinit var rightColumnPositionPref: SeekBarPreference
    // Cluster-key main-character positions.
    private lateinit var clusterLeftPref: SeekBarPreference
    private lateinit var clusterRightPref: SeekBarPreference
    // Suggestion / candidate bar.
    private lateinit var suggestionHeightPref: SeekBarPreference
    private lateinit var suggestionBgPref: ColorSwatchPreference
    private lateinit var suggestionFontPref: Preference
    private lateinit var suggestionWeightPref: SeekBarPreference
    private lateinit var suggestionSizePref: SeekBarPreference
    private lateinit var suggestionColorPref: ColorSwatchPreference
    // Custom-suggestion row contents (a global setting, edited here next to the suggestion-bar look).
    private lateinit var customSuggestionsPref: EditTextPreference
    // Library screen look (app-wide, not per-geometry).
    private lateinit var libSepColorPref: ColorSwatchPreference
    private lateinit var libSepThicknessPref: SeekBarPreference
    private lateinit var libRowSpacingPref: SeekBarPreference
    private lateinit var libIndentPref: SeekBarPreference
    private lateinit var libHeadingFontPref: Preference
    private lateinit var libHeadingWeightPref: SeekBarPreference
    private lateinit var libHeadingSizePref: SeekBarPreference
    private lateinit var libHeadingColorPref: ColorSwatchPreference
    private lateinit var libNameFontPref: Preference
    private lateinit var libNameWeightPref: SeekBarPreference
    private lateinit var libNameSizePref: SeekBarPreference
    private lateinit var libNameColorPref: ColorSwatchPreference
    private lateinit var libBadgeFontPref: Preference
    private lateinit var libBadgeWeightPref: SeekBarPreference
    private lateinit var libBadgeSizePref: SeekBarPreference
    private lateinit var libBadgeColorPref: ColorSwatchPreference
    private var testField: EditText? = null

    /** Where a freshly imported font is applied — set per font picker before launching the SAF import. */
    private var onFontImported: (String) -> Unit = {}

    private val importFontLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { handleFontImport(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[KeyboardUiViewModel::class.java]
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // A bottom test field so the keyboard shows over this screen and slider changes preview live.
        val preferenceView = super.onCreateView(inflater, container, savedInstanceState)
        val wrapper = inflater.inflate(R.layout.preference_fragment_with_test_field, container, false)
        val preferenceContainer = wrapper.findViewById<ViewGroup>(R.id.preference_container)
        preferenceContainer.addView(preferenceView)
        testField = wrapper.findViewById(R.id.test_field)
        return wrapper
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        eventHandler = SettingsEventHandler(requireContext())

        val geometryCategory =
            PreferenceCategory(context).apply {
                key = "kb_ui_cat_geometry"
                title = resources.getString(R.string.keyboard_ui_category_geometry)
                layoutResource = R.layout.preference_category_kxkb_first
            }

        geometryPref =
            ListPreference(context).apply {
                key = "kb_ui_geometry"
                isPersistent = false
                layoutResource = R.layout.preference_item_kxkb
                title = resources.getString(R.string.keyboard_ui_geometry)
                entries =
                    arrayOf(
                        resources.getString(R.string.keyboard_ui_geometry_folded_port),
                        resources.getString(R.string.keyboard_ui_geometry_folded_land),
                        resources.getString(R.string.keyboard_ui_geometry_semi_port),
                        resources.getString(R.string.keyboard_ui_geometry_semi_land),
                        resources.getString(R.string.keyboard_ui_geometry_unfolded_port),
                        resources.getString(R.string.keyboard_ui_geometry_unfolded_land)
                    )
                entryValues = GeometryBucket.entries.map { it.key }.toTypedArray()
                value = defaultGeometry()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }

        // --- KEYBOARD: whole-keyboard geometry, background, key spacing (per axis). ---
        val keyboardCategory = sectionCategory("kb_ui_cat_keyboard", R.string.keyboard_ui_section_keyboard)
        localePref =
            ListPreference(context).apply {
                key = "kb_ui_app_locale"
                isPersistent = false
                layoutResource = R.layout.preference_item_kxkb
                title = resources.getString(R.string.keyboard_ui_app_language)
                entries =
                    arrayOf(
                        resources.getString(R.string.keyboard_ui_app_language_system),
                        "English", "日本語", "Русский", "Čeština"
                    )
                entryValues = arrayOf("", "en", "ja", "ru", "cs")
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        heightPref = seekBar("kb_ui_height", R.string.keyboard_ui_item_height, min = 50, max = 200)
        topRowHeightPref = seekBar("kb_ui_top_row_height", R.string.keyboard_ui_item_top_row_height, min = 50, max = 200)
        bottomRowHeightPref = seekBar("kb_ui_bottom_row_height", R.string.keyboard_ui_item_bottom_row_height, min = 50, max = 200)
        widthPref = seekBar("kb_ui_width", R.string.keyboard_ui_item_width, min = 50, max = 100)
        splitPref = seekBar("kb_ui_split", R.string.keyboard_ui_item_split, min = 0, max = 200)
        liftPref = seekBar("kb_ui_lift", R.string.keyboard_ui_bottom_lift, min = 0, max = 200)
        keyboardBgColorPref = colorPref("kb_ui_col_keyboard_bg", R.string.keyboard_ui_item_background)
        spacingHPref = seekBar("kb_ui_spacing_h", R.string.keyboard_ui_item_h_gap, min = 0, max = 300, sub = true)
        spacingVPref = seekBar("kb_ui_spacing_v", R.string.keyboard_ui_item_v_gap, min = 0, max = 300, sub = true)

        // --- KEYS: by function — primary char / secondary char / key body. ---
        val keysCategory = sectionCategory("kb_ui_cat_keys", R.string.keyboard_ui_category_keys)
        // Primary character.
        fontFamilyPref = fontEntry("kb_ui_font_family", R.string.keyboard_ui_item_font, sub = true)
        weightPref = seekBar("kb_ui_weight", R.string.keyboard_ui_item_weight, min = 100, max = 900, sub = true)
        fontPref = seekBar("kb_ui_font", R.string.keyboard_ui_item_size, min = 50, max = 400, sub = true)
        keyTextColorPref = colorPref("kb_ui_col_key_text", R.string.keyboard_ui_item_colour, sub = true)
        // Secondary character (general — the rows inherit these).
        hintFontPref = fontEntry("kb_ui_hint_font", R.string.keyboard_ui_item_font, sub = true)
        hintWeightPref = seekBar("kb_ui_hint_weight", R.string.keyboard_ui_item_weight, min = 100, max = 900, sub = true)
        hintPref = seekBar("kb_ui_hint", R.string.keyboard_ui_item_size, min = 50, max = 400, sub = true)
        hintColorPref = colorPref("kb_ui_hint_col", R.string.keyboard_ui_item_colour, sub = true)
        // Key body.
        keyBgColorPref = colorPref("kb_ui_col_key_bg", R.string.keyboard_ui_item_background, sub = true)
        functionalKeyBgColorPref =
            colorPref("kb_ui_col_functional_key_bg", R.string.keyboard_ui_item_functional_key_colour, sub = true)
        cornerPref = seekBar("kb_ui_corner", R.string.keyboard_ui_corner_radius, min = 0, max = 24, sub = true)
        borderPref = seekBar("kb_ui_border", R.string.keyboard_ui_border_width, min = 0, max = 8, sub = true)
        keyBorderColorPref = colorPref("kb_ui_col_key_border", R.string.keyboard_ui_item_border_colour, sub = true)
        capsLockShiftColorPref = colorPref("kb_ui_col_caps_shift", R.string.keyboard_ui_item_caps_shift, sub = true)

        // --- ROWS: the horizontal bands, top to bottom — suggestion bar, then the secondary char rows. ---
        val rowsCategory = sectionCategory("kb_ui_cat_rows", R.string.keyboard_ui_section_rows)
        // Suggestion bar (the top-most row).
        suggestionHeightPref = seekBar("kb_ui_sug_height", R.string.keyboard_ui_item_height, min = 50, max = 200, sub = true)
        suggestionBgPref = colorPref("kb_ui_sug_bg", R.string.keyboard_ui_item_background, sub = true)
        suggestionFontPref = fontEntry("kb_ui_sug_font", R.string.keyboard_ui_item_font, sub = true)
        suggestionWeightPref = seekBar("kb_ui_sug_weight", R.string.keyboard_ui_item_weight, min = 100, max = 900, sub = true)
        suggestionSizePref = seekBar("kb_ui_sug_size", R.string.keyboard_ui_item_size, min = 50, max = 400, sub = true)
        suggestionColorPref = colorPref("kb_ui_sug_col", R.string.keyboard_ui_item_colour, sub = true)
        customSuggestionsPref =
            EditTextPreference(context).apply {
                key = "kb_ui_custom_suggestions"
                isPersistent = false
                layoutResource = R.layout.preference_item_kxkb_l2
                title = resources.getString(R.string.keyboard_ui_custom_suggestions)
                dialogTitle = resources.getString(R.string.keyboard_ui_custom_suggestions)
                dialogMessage = resources.getString(R.string.keyboard_ui_custom_suggestions_dialog)
                setOnBindEditTextListener { editText ->
                    editText.inputType =
                        InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    editText.minLines = 4
                    editText.setSelection(editText.text.length)
                }
                // Summary: a one-line preview of the configured entries, or the explanatory hint when empty.
                summaryProvider =
                    Preference.SummaryProvider<EditTextPreference> { pref ->
                        val entries = pref.text.orEmpty()
                        if (entries.isBlank()) {
                            resources.getString(R.string.keyboard_ui_custom_suggestions_summary)
                        } else {
                            entries.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("  ")
                        }
                    }
            }
        hintTopColorPref = colorPref("kb_ui_hint_top_col", R.string.keyboard_ui_item_colour, sub = true)
        hintTopScalePref = seekBar("kb_ui_hint_top_size", R.string.keyboard_ui_item_size, min = 50, max = 400, sub = true)
        hintTopFontPref = fontEntry("kb_ui_hint_top_font", R.string.keyboard_ui_item_font, sub = true)
        hintBottomColorPref = colorPref("kb_ui_hint_bot_col", R.string.keyboard_ui_item_colour, sub = true)
        hintBottomScalePref = seekBar("kb_ui_hint_bot_size", R.string.keyboard_ui_item_size, min = 50, max = 400, sub = true)
        hintBottomFontPref = fontEntry("kb_ui_hint_bot_font", R.string.keyboard_ui_item_font, sub = true)

        // --- COMPASS KEYS: positions of the secondary-character rows / columns. ---
        val compassCategory = sectionCategory("kb_ui_cat_compass", R.string.keyboard_ui_section_compass)
        topRowPositionPref = seekBar("kb_ui_hint_top_dist", R.string.keyboard_ui_item_top_row_pos, min = 0, max = 60)
        bottomRowPositionPref = seekBar("kb_ui_hint_bot_dist", R.string.keyboard_ui_item_bottom_row_pos, min = 0, max = 60)
        leftColumnPositionPref = seekBar("kb_ui_hint_left_dist", R.string.keyboard_ui_item_left_col_pos, min = 0, max = 60)
        rightColumnPositionPref = seekBar("kb_ui_hint_right_dist", R.string.keyboard_ui_item_right_col_pos, min = 0, max = 60)

        // --- CLUSTER KEYS: individual main-character positions (rendered once cluster keys land). ---
        val clusterCategory = sectionCategory("kb_ui_cat_cluster", R.string.keyboard_ui_section_cluster)
        clusterLeftPref = seekBar("kb_ui_cluster_left", R.string.keyboard_ui_item_left_char_pos, min = 0, max = 48)
        clusterRightPref = seekBar("kb_ui_cluster_right", R.string.keyboard_ui_item_right_char_pos, min = 0, max = 48)

        screen.addPreference(geometryCategory)
        geometryCategory.addPreference(geometryPref)

        screen.addPreference(keyboardCategory)
        keyboardCategory.addPreference(localePref)
        keyboardCategory.addPreference(heightPref)
        keyboardCategory.addPreference(topRowHeightPref)
        keyboardCategory.addPreference(bottomRowHeightPref)
        keyboardCategory.addPreference(widthPref)
        keyboardCategory.addPreference(splitPref)
        keyboardCategory.addPreference(liftPref)
        keyboardCategory.addPreference(keyboardBgColorPref)
        keyboardCategory.addPreference(subHeader(R.string.keyboard_ui_sub_key_spacing))
        keyboardCategory.addPreference(spacingHPref)
        keyboardCategory.addPreference(spacingVPref)

        screen.addPreference(keysCategory)
        keysCategory.addPreference(subHeader(R.string.keyboard_ui_sub_primary))
        keysCategory.addPreference(fontFamilyPref)
        keysCategory.addPreference(weightPref)
        keysCategory.addPreference(fontPref)
        keysCategory.addPreference(keyTextColorPref)
        keysCategory.addPreference(subHeader(R.string.keyboard_ui_sub_secondary))
        keysCategory.addPreference(hintFontPref)
        keysCategory.addPreference(hintWeightPref)
        keysCategory.addPreference(hintPref)
        keysCategory.addPreference(hintColorPref)
        keysCategory.addPreference(subHeader(R.string.keyboard_ui_sub_key_body))
        keysCategory.addPreference(keyBgColorPref)
        keysCategory.addPreference(functionalKeyBgColorPref)
        keysCategory.addPreference(cornerPref)
        keysCategory.addPreference(borderPref)
        keysCategory.addPreference(keyBorderColorPref)
        keysCategory.addPreference(capsLockShiftColorPref)

        screen.addPreference(rowsCategory)
        rowsCategory.addPreference(subHeader(R.string.keyboard_ui_section_suggestion))
        rowsCategory.addPreference(suggestionHeightPref)
        rowsCategory.addPreference(suggestionBgPref)
        rowsCategory.addPreference(suggestionFontPref)
        rowsCategory.addPreference(suggestionWeightPref)
        rowsCategory.addPreference(suggestionSizePref)
        rowsCategory.addPreference(suggestionColorPref)
        rowsCategory.addPreference(customSuggestionsPref)
        rowsCategory.addPreference(subHeader(R.string.keyboard_ui_sub_top_row))
        rowsCategory.addPreference(hintTopColorPref)
        rowsCategory.addPreference(hintTopScalePref)
        rowsCategory.addPreference(hintTopFontPref)
        rowsCategory.addPreference(subHeader(R.string.keyboard_ui_sub_bottom_row))
        rowsCategory.addPreference(hintBottomColorPref)
        rowsCategory.addPreference(hintBottomScalePref)
        rowsCategory.addPreference(hintBottomFontPref)

        screen.addPreference(compassCategory)
        compassCategory.addPreference(topRowPositionPref)
        compassCategory.addPreference(bottomRowPositionPref)
        compassCategory.addPreference(leftColumnPositionPref)
        compassCategory.addPreference(rightColumnPositionPref)

        screen.addPreference(clusterCategory)
        clusterCategory.addPreference(clusterLeftPref)
        clusterCategory.addPreference(clusterRightPref)

        val libraryCategory = sectionCategory("kb_ui_library_section", R.string.library_section)
        libSepColorPref = colorPref("lib_sep_color", R.string.keyboard_ui_item_colour, sub = true)
        libSepThicknessPref = seekBar("lib_sep_thickness", R.string.library_item_thickness, 0, 8, sub = true)
        libRowSpacingPref = seekBar("lib_row_spacing", R.string.library_item_row_spacing, 0, 24, sub = true)
        libIndentPref = seekBar("lib_indent", R.string.library_item_indent, 0, 96, sub = true)
        libHeadingFontPref = fontEntry("lib_heading_font", R.string.keyboard_ui_item_font, sub = true)
        libHeadingWeightPref = seekBar("lib_heading_weight", R.string.keyboard_ui_item_weight, 100, 900, sub = true)
        libHeadingSizePref = seekBar("lib_heading_size", R.string.keyboard_ui_item_size, 8, 48, sub = true)
        libHeadingColorPref = colorPref("lib_heading_color", R.string.keyboard_ui_item_colour, sub = true)
        libNameFontPref = fontEntry("lib_name_font", R.string.keyboard_ui_item_font, sub = true)
        libNameWeightPref = seekBar("lib_name_weight", R.string.keyboard_ui_item_weight, 100, 900, sub = true)
        libNameSizePref = seekBar("lib_name_size", R.string.keyboard_ui_item_size, 8, 48, sub = true)
        libNameColorPref = colorPref("lib_name_color", R.string.keyboard_ui_item_colour, sub = true)
        libBadgeFontPref = fontEntry("lib_badge_font", R.string.keyboard_ui_item_font, sub = true)
        libBadgeWeightPref = seekBar("lib_badge_weight", R.string.keyboard_ui_item_weight, 100, 900, sub = true)
        libBadgeSizePref = seekBar("lib_badge_size", R.string.keyboard_ui_item_size, 8, 48, sub = true)
        libBadgeColorPref = colorPref("lib_badge_color", R.string.keyboard_ui_item_colour, sub = true)

        screen.addPreference(libraryCategory)
        libraryCategory.addPreference(subHeader(R.string.library_sub_separators))
        libraryCategory.addPreference(libSepColorPref)
        libraryCategory.addPreference(libSepThicknessPref)
        libraryCategory.addPreference(subHeader(R.string.library_sub_spacing))
        libraryCategory.addPreference(libRowSpacingPref)
        libraryCategory.addPreference(libIndentPref)
        libraryCategory.addPreference(subHeader(R.string.library_sub_heading))
        libraryCategory.addPreference(libHeadingFontPref)
        libraryCategory.addPreference(libHeadingWeightPref)
        libraryCategory.addPreference(libHeadingSizePref)
        libraryCategory.addPreference(libHeadingColorPref)
        libraryCategory.addPreference(subHeader(R.string.library_sub_name))
        libraryCategory.addPreference(libNameFontPref)
        libraryCategory.addPreference(libNameWeightPref)
        libraryCategory.addPreference(libNameSizePref)
        libraryCategory.addPreference(libNameColorPref)
        libraryCategory.addPreference(subHeader(R.string.library_sub_badge))
        libraryCategory.addPreference(libBadgeFontPref)
        libraryCategory.addPreference(libBadgeWeightPref)
        libraryCategory.addPreference(libBadgeSizePref)
        libraryCategory.addPreference(libBadgeColorPref)

        preferenceScreen = screen
    }

    /** A top-level section header (big bold word-underlined heading with a full-width divider above it). */
    private fun sectionCategory(prefKey: String, titleRes: Int): PreferenceCategory =
        PreferenceCategory(preferenceManager.context).apply {
            key = prefKey
            title = resources.getString(titleRes)
            layoutResource = R.layout.preference_category_kxkb
        }

    /** A non-clickable sub-category heading (one level under a section), indented + word-underlined. */
    private fun subHeader(titleRes: Int): Preference =
        Preference(preferenceManager.context).apply {
            isPersistent = false
            isSelectable = false
            layoutResource = R.layout.preference_subcategory_kxkb
            title = resources.getString(titleRes)
        }

    private fun fontEntry(prefKey: String, titleRes: Int, sub: Boolean = false): Preference =
        Preference(preferenceManager.context).apply {
            key = prefKey
            isPersistent = false
            layoutResource = itemLayout(sub)
            title = resources.getString(titleRes)
        }

    private fun colorPref(prefKey: String, titleRes: Int, sub: Boolean = false): ColorSwatchPreference =
        ColorSwatchPreference(preferenceManager.context).apply {
            key = prefKey
            isPersistent = false
            layoutResource = itemLayout(sub)
            title = resources.getString(titleRes)
        }

    private fun itemLayout(sub: Boolean) =
        if (sub) R.layout.preference_item_kxkb_l2 else R.layout.preference_item_kxkb

    private fun hex(color: Int): String = String.format("#%08X", color)

    /** Open the font picker for [current], applying the choice (or a fresh import) via [apply]. */
    private fun showFontPicker(current: String, apply: (String) -> Unit) {
        FontPicker.show(
            requireContext(),
            current,
            onPick = apply,
            onImport = {
                onFontImported = apply
                importFontLauncher.launch(arrayOf("*/*"))
            }
        )
    }

    private fun handleFontImport(uri: Uri) {
        val name = KeyboardFonts.importFont(requireContext(), uri)
        if (name != null) {
            onFontImported(name)
            Toast.makeText(
                requireContext(),
                getString(R.string.font_imported, name),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(requireContext(), R.string.font_import_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun seekBar(prefKey: String, titleRes: Int, min: Int, max: Int, sub: Boolean = false): SeekBarPreference =
        SeekBarPreference(preferenceManager.context).apply {
            layoutResource = if (sub) R.layout.preference_seekbar_kxkb_l2 else R.layout.preference_seekbar_kxkb
            key = prefKey
            isPersistent = false
            title = resources.getString(titleRes)
            this.min = min
            this.max = max
            showSeekBarValue = true
            isAdjustable = true
            updatesContinuously = true
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        geometryPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.selectGeometry(newValue as String)
            true
        }
        // App interface language — independent of the phone locale and the keyboard's layout language.
        localePref.value = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
        localePref.setOnPreferenceChangeListener { _, newValue ->
            val tag = newValue as String
            val locales =
                if (tag.isEmpty()) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                else androidx.core.os.LocaleListCompat.forLanguageTags(tag)
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
            true
        }
        heightPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateHeightScale(newValue as Int)
            true
        }
        topRowHeightPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateTopRowHeight(newValue as Int)
            true
        }
        bottomRowHeightPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateBottomRowHeight(newValue as Int)
            true
        }
        widthPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateWidth(newValue as Int)
            true
        }
        splitPref.setOnPreferenceChangeListener { _, v ->
            viewModel.updateSplitFraction(v as Int)
            true
        }
        liftPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateBottomLift(newValue as Int)
            true
        }
        spacingHPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateKeySpacingH(newValue as Int)
            true
        }
        spacingVPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateKeySpacingV(newValue as Int)
            true
        }
        fontFamilyPref.setOnPreferenceClickListener {
            showFontPicker(viewModel.uiState.value.fontFamily) { viewModel.updateFontFamily(it) }
            true
        }
        keyboardBgColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.keyboardBgColor) { viewModel.updateKeyboardBgColor(it) }
            true
        }
        keyBgColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.keyBgColor) { viewModel.updateKeyBgColor(it) }
            true
        }
        functionalKeyBgColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.functionalKeyBgColor) {
                viewModel.updateFunctionalKeyBgColor(it)
            }
            true
        }
        keyTextColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.keyTextColor) { viewModel.updateKeyTextColor(it) }
            true
        }
        keyBorderColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.keyBorderColor) { viewModel.updateKeyBorderColor(it) }
            true
        }
        capsLockShiftColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.capsLockShiftColor) { viewModel.updateCapsLockShiftColor(it) }
            true
        }
        hintPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateHintScale(newValue as Int)
            true
        }
        hintFontPref.setOnPreferenceClickListener {
            showFontPicker(viewModel.uiState.value.hintFont) { viewModel.updateHintFont(it) }
            true
        }
        hintWeightPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateHintWeight(newValue as Int)
            true
        }
        hintColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.hintColor) { viewModel.updateHintColor(it) }
            true
        }
        fontPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateFontScale(newValue as Int)
            true
        }
        cornerPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateCornerRadius(newValue as Int)
            true
        }
        borderPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateBorderWidth(newValue as Int)
            true
        }
        weightPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateLabelWeight(newValue as Int)
            true
        }
        hintTopColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.hintTopColor) { viewModel.updateHintTopColor(it) }
            true
        }
        hintTopScalePref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateHintTopScale(newValue as Int)
            true
        }
        hintTopFontPref.setOnPreferenceClickListener {
            showFontPicker(viewModel.uiState.value.hintTopFont) { viewModel.updateHintTopFont(it) }
            true
        }
        hintBottomColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.hintBottomColor) { viewModel.updateHintBottomColor(it) }
            true
        }
        hintBottomScalePref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateHintBottomScale(newValue as Int)
            true
        }
        hintBottomFontPref.setOnPreferenceClickListener {
            showFontPicker(viewModel.uiState.value.hintBottomFont) { viewModel.updateHintBottomFont(it) }
            true
        }
        topRowPositionPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateHintTopDistance(newValue as Int)
            true
        }
        bottomRowPositionPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateHintBottomDistance(newValue as Int)
            true
        }
        leftColumnPositionPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateHintLeftDistance(newValue as Int)
            true
        }
        rightColumnPositionPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateHintRightDistance(newValue as Int)
            true
        }
        clusterLeftPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateClusterLeft(newValue as Int)
            true
        }
        clusterRightPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateClusterRight(newValue as Int)
            true
        }
        suggestionHeightPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateSuggestionHeight(newValue as Int)
            true
        }
        suggestionBgPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.suggestionBgColor) { viewModel.updateSuggestionBgColor(it) }
            true
        }
        suggestionFontPref.setOnPreferenceClickListener {
            showFontPicker(viewModel.uiState.value.suggestionFont) { viewModel.updateSuggestionFont(it) }
            true
        }
        suggestionWeightPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateSuggestionWeight(newValue as Int)
            true
        }
        suggestionSizePref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateSuggestionSize(newValue as Int)
            true
        }
        suggestionColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.suggestionColor) { viewModel.updateSuggestionColor(it) }
            true
        }
        customSuggestionsPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateCustomSuggestions(newValue as String)
            true
        }

        libSepColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.libraryState.value.separatorColor) { viewModel.updateLibSeparatorColor(it) }
            true
        }
        libSepThicknessPref.setOnPreferenceChangeListener { _, v -> viewModel.updateLibSeparatorThickness(v as Int); true }
        libRowSpacingPref.setOnPreferenceChangeListener { _, v -> viewModel.updateLibRowSpacing(v as Int); true }
        libIndentPref.setOnPreferenceChangeListener { _, v -> viewModel.updateLibIndent(v as Int); true }
        libHeadingFontPref.setOnPreferenceClickListener {
            showFontPicker(viewModel.libraryState.value.headingFont) { viewModel.updateLibHeadingFont(it) }
            true
        }
        libHeadingWeightPref.setOnPreferenceChangeListener { _, v -> viewModel.updateLibHeadingWeight(v as Int); true }
        libHeadingSizePref.setOnPreferenceChangeListener { _, v -> viewModel.updateLibHeadingSize(v as Int); true }
        libHeadingColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.libraryState.value.headingColor) { viewModel.updateLibHeadingColor(it) }
            true
        }
        libNameFontPref.setOnPreferenceClickListener {
            showFontPicker(viewModel.libraryState.value.nameFont) { viewModel.updateLibNameFont(it) }
            true
        }
        libNameWeightPref.setOnPreferenceChangeListener { _, v -> viewModel.updateLibNameWeight(v as Int); true }
        libNameSizePref.setOnPreferenceChangeListener { _, v -> viewModel.updateLibNameSize(v as Int); true }
        libNameColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.libraryState.value.nameColor) { viewModel.updateLibNameColor(it) }
            true
        }
        libBadgeFontPref.setOnPreferenceClickListener {
            showFontPicker(viewModel.libraryState.value.badgeFont) { viewModel.updateLibBadgeFont(it) }
            true
        }
        libBadgeWeightPref.setOnPreferenceChangeListener { _, v -> viewModel.updateLibBadgeWeight(v as Int); true }
        libBadgeSizePref.setOnPreferenceChangeListener { _, v -> viewModel.updateLibBadgeSize(v as Int); true }
        libBadgeColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.libraryState.value.badgeColor) { viewModel.updateLibBadgeColor(it) }
            true
        }

        viewModel.selectGeometry(geometryPref.value ?: defaultGeometry())

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        geometryPref.value = state.geometry
                        heightPref.value = state.keyHeightScalePct
                        topRowHeightPref.value = state.topRowHeightPct
                        bottomRowHeightPref.value = state.bottomRowHeightPct
                        widthPref.value = state.keyboardWidthPct
                        splitPref.value = state.splitFractionPct
                        liftPref.value = state.bottomLiftDp
                        spacingHPref.value = state.keySpacingHPct
                        spacingVPref.value = state.keySpacingVPct
                        fontPref.value = state.keyFontScalePct
                        hintPref.value = state.hintScalePct
                        hintFontPref.summary = KeyboardFonts.displayName(requireContext(), state.hintFont)
                        hintWeightPref.value = state.hintWeight
                        hintColorPref.summary = hex(state.hintColor)
                        hintColorPref.color = state.hintColor
                        fontFamilyPref.summary = KeyboardFonts.displayName(requireContext(), state.fontFamily)
                        keyboardBgColorPref.summary = hex(state.keyboardBgColor)
                        keyboardBgColorPref.color = state.keyboardBgColor
                        keyBgColorPref.summary = hex(state.keyBgColor)
                        keyBgColorPref.color = state.keyBgColor
                        functionalKeyBgColorPref.summary = hex(state.functionalKeyBgColor)
                        functionalKeyBgColorPref.color = state.functionalKeyBgColor
                        keyTextColorPref.summary = hex(state.keyTextColor)
                        keyTextColorPref.color = state.keyTextColor
                        keyBorderColorPref.summary = hex(state.keyBorderColor)
                        keyBorderColorPref.color = state.keyBorderColor
                        capsLockShiftColorPref.summary = hex(state.capsLockShiftColor)
                        capsLockShiftColorPref.color = state.capsLockShiftColor
                        cornerPref.value = state.cornerRadiusDp
                        borderPref.value = state.keyBorderWidthDp
                        weightPref.value = state.labelWeight
                        hintTopColorPref.summary = hex(state.hintTopColor)
                        hintTopColorPref.color = state.hintTopColor
                        hintTopScalePref.value = state.hintTopScalePct
                        hintTopFontPref.summary = KeyboardFonts.displayName(requireContext(), state.hintTopFont)
                        hintBottomColorPref.summary = hex(state.hintBottomColor)
                        hintBottomColorPref.color = state.hintBottomColor
                        hintBottomScalePref.value = state.hintBottomScalePct
                        hintBottomFontPref.summary = KeyboardFonts.displayName(requireContext(), state.hintBottomFont)
                        topRowPositionPref.value = state.hintTopDistanceDp
                        bottomRowPositionPref.value = state.hintBottomDistanceDp
                        leftColumnPositionPref.value = state.hintLeftDistanceDp
                        rightColumnPositionPref.value = state.hintRightDistanceDp
                        clusterLeftPref.value = state.clusterLeftDp
                        clusterRightPref.value = state.clusterRightDp
                        suggestionHeightPref.value = state.suggestionHeightPct
                        suggestionBgPref.summary = hex(state.suggestionBgColor)
                        suggestionBgPref.color = state.suggestionBgColor
                        suggestionFontPref.summary = KeyboardFonts.displayName(requireContext(), state.suggestionFont)
                        suggestionWeightPref.value = state.suggestionWeight
                        suggestionSizePref.value = state.suggestionSizePct
                        suggestionColorPref.summary = hex(state.suggestionColor)
                        suggestionColorPref.color = state.suggestionColor
                    }
                }
                launch {
                    viewModel.events.collect { event -> eventHandler.handle(event) }
                }
                launch {
                    viewModel.customSuggestions.collect { raw ->
                        if (customSuggestionsPref.text != raw) customSuggestionsPref.text = raw
                    }
                }
                launch {
                    viewModel.libraryState.collect { lib ->
                        libSepColorPref.summary = hex(lib.separatorColor)
                        libSepColorPref.color = lib.separatorColor
                        libSepThicknessPref.value = lib.separatorThicknessDp
                        libRowSpacingPref.value = lib.rowSpacingDp
                        libIndentPref.value = lib.indentDp
                        libHeadingFontPref.summary = KeyboardFonts.displayName(requireContext(), lib.headingFont)
                        libHeadingWeightPref.value = lib.headingWeight
                        libHeadingSizePref.value = lib.headingSizeSp
                        libHeadingColorPref.summary = hex(lib.headingColor)
                        libHeadingColorPref.color = lib.headingColor
                        libNameFontPref.summary = KeyboardFonts.displayName(requireContext(), lib.nameFont)
                        libNameWeightPref.value = lib.nameWeight
                        libNameSizePref.value = lib.nameSizeSp
                        libNameColorPref.summary = hex(lib.nameColor)
                        libNameColorPref.color = lib.nameColor
                        libBadgeFontPref.summary = KeyboardFonts.displayName(requireContext(), lib.badgeFont)
                        libBadgeWeightPref.value = lib.badgeWeight
                        libBadgeSizePref.value = lib.badgeSizeSp
                        libBadgeColorPref.summary = hex(lib.badgeColor)
                        libBadgeColorPref.color = lib.badgeColor
                    }
                }
            }
        }
    }

    // Defaults to the bucket the running keyboard is actually using (large/fold-aware), so editing the
    // selected geometry immediately affects the visible keyboard. The selector still switches buckets.
    private fun defaultGeometry(): String =
        GeometryBucket.fromConfiguration(resources.configuration).key

    override fun onDestroyView() {
        super.onDestroyView()
        testField = null
    }
}
