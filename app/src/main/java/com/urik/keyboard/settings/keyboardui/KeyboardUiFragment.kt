package com.urik.keyboard.settings.keyboardui

import android.net.Uri
import android.os.Bundle
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
    private lateinit var heightPref: SeekBarPreference
    private lateinit var widthPref: SeekBarPreference
    private lateinit var liftPref: SeekBarPreference
    private lateinit var spacingPref: SeekBarPreference
    private lateinit var fontPref: SeekBarPreference
    private lateinit var hintPref: SeekBarPreference
    private lateinit var fontFamilyPref: Preference
    private lateinit var cornerPref: SeekBarPreference
    private lateinit var borderPref: SeekBarPreference
    private lateinit var weightPref: SeekBarPreference
    private lateinit var keyboardBgColorPref: ColorSwatchPreference
    private lateinit var keyBgColorPref: ColorSwatchPreference
    private lateinit var keyTextColorPref: ColorSwatchPreference
    private lateinit var keyBorderColorPref: ColorSwatchPreference
    private var testField: EditText? = null

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

        val sizeCategory =
            PreferenceCategory(context).apply {
                key = "kb_ui_cat_size"
                title = resources.getString(R.string.keyboard_ui_category_size)
                layoutResource = R.layout.preference_category_kxkb
            }
        heightPref = seekBar("kb_ui_height", R.string.keyboard_ui_key_height, min = 50, max = 200)
        widthPref = seekBar("kb_ui_width", R.string.keyboard_ui_width, min = 50, max = 100)
        liftPref = seekBar("kb_ui_lift", R.string.keyboard_ui_bottom_lift, min = 0, max = 200)
        spacingPref = seekBar("kb_ui_spacing", R.string.keyboard_ui_key_spacing, min = 0, max = 200)
        fontPref = seekBar("kb_ui_font", R.string.keyboard_ui_font_scale, min = 50, max = 200)
        hintPref = seekBar("kb_ui_hint", R.string.keyboard_ui_hint_size, min = 50, max = 200)

        val keysCategory =
            PreferenceCategory(context).apply {
                key = "kb_ui_cat_keys"
                title = resources.getString(R.string.keyboard_ui_category_keys)
                layoutResource = R.layout.preference_category_kxkb
            }
        cornerPref = seekBar("kb_ui_corner", R.string.keyboard_ui_corner_radius, min = 0, max = 24)
        borderPref = seekBar("kb_ui_border", R.string.keyboard_ui_border_width, min = 0, max = 8)
        weightPref = seekBar("kb_ui_weight", R.string.keyboard_ui_label_weight, min = 100, max = 900)

        val textCategory =
            PreferenceCategory(context).apply {
                key = "kb_ui_cat_text"
                title = resources.getString(R.string.keyboard_ui_category_text)
                layoutResource = R.layout.preference_category_kxkb
            }
        fontFamilyPref =
            Preference(context).apply {
                key = "kb_ui_font_family"
                isPersistent = false
                layoutResource = R.layout.preference_item_kxkb
                title = resources.getString(R.string.keyboard_ui_font_family)
            }

        val coloursCategory =
            PreferenceCategory(context).apply {
                key = "kb_ui_cat_colours"
                title = resources.getString(R.string.keyboard_ui_category_colours)
                layoutResource = R.layout.preference_category_kxkb
            }
        keyboardBgColorPref = colorPref("kb_ui_col_keyboard_bg", R.string.keyboard_ui_colour_keyboard_bg)
        keyBgColorPref = colorPref("kb_ui_col_key_bg", R.string.keyboard_ui_colour_key_bg)
        keyTextColorPref = colorPref("kb_ui_col_key_text", R.string.keyboard_ui_colour_key_text)
        keyBorderColorPref = colorPref("kb_ui_col_key_border", R.string.keyboard_ui_colour_key_border)

        screen.addPreference(geometryCategory)
        geometryCategory.addPreference(geometryPref)
        screen.addPreference(sizeCategory)
        sizeCategory.addPreference(heightPref)
        sizeCategory.addPreference(widthPref)
        sizeCategory.addPreference(liftPref)
        sizeCategory.addPreference(spacingPref)
        screen.addPreference(textCategory)
        textCategory.addPreference(fontPref)
        textCategory.addPreference(hintPref)
        textCategory.addPreference(fontFamilyPref)
        screen.addPreference(keysCategory)
        keysCategory.addPreference(cornerPref)
        keysCategory.addPreference(borderPref)
        keysCategory.addPreference(weightPref)
        screen.addPreference(coloursCategory)
        coloursCategory.addPreference(keyboardBgColorPref)
        coloursCategory.addPreference(keyBgColorPref)
        coloursCategory.addPreference(keyTextColorPref)
        coloursCategory.addPreference(keyBorderColorPref)

        preferenceScreen = screen
    }

    private fun colorPref(prefKey: String, titleRes: Int): ColorSwatchPreference =
        ColorSwatchPreference(preferenceManager.context).apply {
            key = prefKey
            isPersistent = false
            title = resources.getString(titleRes)
        }

    private fun hex(color: Int): String = String.format("#%08X", color)

    private fun handleFontImport(uri: Uri) {
        val name = KeyboardFonts.importFont(requireContext(), uri)
        if (name != null) {
            viewModel.updateFontFamily(name)
            Toast.makeText(
                requireContext(),
                getString(R.string.font_imported, name),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(requireContext(), R.string.font_import_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun seekBar(prefKey: String, titleRes: Int, min: Int, max: Int): SeekBarPreference =
        SeekBarPreference(preferenceManager.context).apply {
            layoutResource = R.layout.preference_seekbar_kxkb
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
        heightPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateHeightScale(newValue as Int)
            true
        }
        widthPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateWidth(newValue as Int)
            true
        }
        liftPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateBottomLift(newValue as Int)
            true
        }
        spacingPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateKeySpacing(newValue as Int)
            true
        }
        fontFamilyPref.setOnPreferenceClickListener {
            FontPicker.show(
                requireContext(),
                viewModel.uiState.value.fontFamily,
                onPick = { viewModel.updateFontFamily(it) },
                onImport = { importFontLauncher.launch(arrayOf("*/*")) }
            )
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
        keyTextColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.keyTextColor) { viewModel.updateKeyTextColor(it) }
            true
        }
        keyBorderColorPref.setOnPreferenceClickListener {
            ColorPicker.show(requireContext(), viewModel.uiState.value.keyBorderColor) { viewModel.updateKeyBorderColor(it) }
            true
        }
        hintPref.setOnPreferenceChangeListener { _, newValue ->
            viewModel.updateHintScale(newValue as Int)
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

        viewModel.selectGeometry(geometryPref.value ?: defaultGeometry())

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        geometryPref.value = state.geometry
                        heightPref.value = state.keyHeightScalePct
                        widthPref.value = state.keyboardWidthPct
                        liftPref.value = state.bottomLiftDp
                        spacingPref.value = state.keySpacingPct
                        fontPref.value = state.keyFontScalePct
                        hintPref.value = state.hintScalePct
                        fontFamilyPref.summary = KeyboardFonts.displayName(requireContext(), state.fontFamily)
                        keyboardBgColorPref.summary = hex(state.keyboardBgColor)
                        keyboardBgColorPref.color = state.keyboardBgColor
                        keyBgColorPref.summary = hex(state.keyBgColor)
                        keyBgColorPref.color = state.keyBgColor
                        keyTextColorPref.summary = hex(state.keyTextColor)
                        keyTextColorPref.color = state.keyTextColor
                        keyBorderColorPref.summary = hex(state.keyBorderColor)
                        keyBorderColorPref.color = state.keyBorderColor
                        cornerPref.value = state.cornerRadiusDp
                        borderPref.value = state.keyBorderWidthDp
                        weightPref.value = state.labelWeight
                    }
                }
                launch {
                    viewModel.events.collect { event -> eventHandler.handle(event) }
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
