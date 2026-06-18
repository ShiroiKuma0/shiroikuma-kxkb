package com.urik.keyboard.settings.library

import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.urik.keyboard.R
import com.urik.keyboard.data.CustomLayoutStore
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.data.LayoutEntry
import com.urik.keyboard.data.LayoutRegistry
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.AdaptiveDimensions
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.GeometryBucket
import com.urik.keyboard.service.KeyboardFonts
import com.urik.keyboard.service.KeyboardLookKnobs
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.LibraryLook
import com.urik.keyboard.service.PostureDetector
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.ui.keyboard.components.KeyboardLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Library — browse every layout grouped under a per-language heading. Tapping a layout renders it as a
 * true live keyboard (the real renderer + the resolved look knobs) in a panel at the bottom, without
 * switching to it; an "Activate" button there makes it that language's active layout while staying here.
 * The list look (separators, spacing, indent, per-category fonts) is settable on the kxkb UI page.
 */
@AndroidEntryPoint
class LibraryFragment : Fragment() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var keyboardRepository: KeyboardRepository
    @Inject lateinit var themeManager: ThemeManager
    @Inject lateinit var characterVariationService: CharacterVariationService
    @Inject lateinit var languageManager: LanguageManager
    @Inject lateinit var cacheMemoryManager: CacheMemoryManager

    private lateinit var registry: LayoutRegistry
    private lateinit var listContainer: LinearLayout
    private lateinit var previewContainer: LinearLayout
    private var rootFrame: android.widget.FrameLayout? = null
    private var look = LibraryLook()

    // The real keyboard renderer, with no-op callbacks (the preview is non-interactive).
    private val layoutManager by lazy {
        KeyboardLayoutManager(
            context = requireContext(),
            onKeyClick = {},
            onAcceleratedDeletionChanged = {},
            onSymbolsLongPress = {},
            characterVariationService = characterVariationService,
            languageManager = languageManager,
            themeManager = themeManager,
            cacheMemoryManager = cacheMemoryManager
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        registry = LayoutRegistry.load(requireContext())
        listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(
                listContainer,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        previewContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(scroll)
            addView(previewContainer)
        }
        return FrameLayout(requireContext()).apply {
            addView(
                content,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            rootFrame = this
        }
    }

    /** A toast-style "flash" in 白い熊's look (black box, yellow text, yellow border) — replaces system toasts. */
    private fun flash(message: String) {
        val root = rootFrame ?: return
        val tv = TextView(requireContext()).apply {
            text = message
            setTextColor(0xFFFFFF00.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF000000.toInt())
                setStroke(dp(2), 0xFFFFFF00.toInt())
                cornerRadius = dp(8).toFloat()
            }
            alpha = 0f
        }
        root.addView(
            tv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(24) }
        )
        tv.animate().alpha(1f).setDuration(150).withEndAction {
            tv.postDelayed({
                tv.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(tv) }
            }, 1400)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        lifecycleScope.launch {
            registry = LayoutRegistry.load(requireContext())
            look = settingsRepository.getLibraryLook()
            val langs = registry.entries.map { it.lang }.distinct()
            val active = langs.associateWith { lang ->
                settingsRepository.getActiveLayoutForLanguage(lang) ?: registry.defaultFor(lang)
            }
            listContainer.removeAllViews()
            for (lang in langs) {
                listContainer.addView(heading(langDisplay(lang)))
                val entries = registry.forLanguage(lang)
                entries.forEachIndexed { i, entry ->
                    listContainer.addView(layoutRow(entry, entry.id == active[lang]))
                    if (i < entries.lastIndex) listContainer.addView(separator())
                }
            }
        }
    }

    private fun heading(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextColor(look.headingColor ?: LibraryLook.DEF_HEADING_COLOR)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, (look.headingSizeSp ?: LibraryLook.DEF_HEADING_SIZE).toFloat())
        typeface = KeyboardFonts.weightedTypeface(
            requireContext(), look.headingFont ?: "", look.headingWeight ?: LibraryLook.DEF_HEADING_WEIGHT
        )
        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun layoutRow(entry: LayoutEntry, isActive: Boolean): View {
        val spacing = look.rowSpacingDp ?: LibraryLook.DEF_ROW_SPACING
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(look.indentDp ?: LibraryLook.DEF_INDENT), dp(spacing), dp(8), dp(spacing))
            isClickable = true
            isFocusable = true
            setOnClickListener { preview(entry) }
        }
        val nameWeight = look.nameWeight ?: LibraryLook.DEF_NAME_WEIGHT
        val name = TextView(requireContext()).apply {
            text = entry.name
            setTextColor(look.nameColor ?: LibraryLook.DEF_NAME_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (look.nameSizeSp ?: LibraryLook.DEF_NAME_SIZE).toFloat())
            typeface = KeyboardFonts.weightedTypeface(
                requireContext(), look.nameFont ?: "", if (isActive) maxOf(nameWeight, 700) else nameWeight
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val badge = TextView(requireContext()).apply {
            text = badgeText(entry, isActive)
            setTextColor(look.badgeColor ?: LibraryLook.DEF_BADGE_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (look.badgeSizeSp ?: LibraryLook.DEF_BADGE_SIZE).toFloat())
            typeface = KeyboardFonts.weightedTypeface(
                requireContext(), look.badgeFont ?: "", look.badgeWeight ?: LibraryLook.DEF_BADGE_WEIGHT
            )
        }
        row.addView(name)
        row.addView(badge)
        return row
    }

    private fun separator(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(look.separatorThicknessDp ?: LibraryLook.DEF_SEPARATOR_THICKNESS)
        )
        setBackgroundColor(look.separatorColor ?: LibraryLook.DEF_SEPARATOR_COLOR)
    }

    /** Render the tapped layout as a true keyboard in the bottom panel, without activating it. */
    private fun preview(entry: LayoutEntry) {
        lifecycleScope.launch {
            val layout = keyboardRepository.loadLayoutById(entry.id) ?: return@launch
            val settings = settingsRepository.settings.first()
            val density = resources.displayMetrics.density
            val posture = PostureDetector(requireContext(), lifecycleScope).postureInfo.value
            // Resolve the look for the geometry the live keyboard is actually using (the IME publishes it).
            // The fold-aware IME bucket can differ from the settings Activity's Configuration bucket, and
            // the whole per-geometry look (spacing, square keys, colours, borders) hangs off it.
            val geometry = settingsRepository.currentGeometry.first()
                ?: GeometryBucket.fromConfiguration(resources.configuration).key
            val knobs = settingsRepository.resolveLookKnobs(entry.lang, entry.id, geometry)
            // Render at the live keyboard's actual height when the IME has published one (its on-keyboard
            // resize lives in a per-combo fork that a different previewed layout wouldn't otherwise pick up).
            val baseDims = AdaptiveDimensions.compute(posture, settings.keySize, density)
            val liveHeightScale = settingsRepository.getCurrentKeyHeightScale()
            val dims = knobs.applyTo(baseDims, density).let { d ->
                if (liveHeightScale != null) {
                    d.copy(keyHeightPx = (baseDims.keyHeightPx * liveHeightScale).toInt().coerceAtLeast(1))
                } else {
                    d
                }
            }

            layoutManager.updateKeySize(settings.keySize)
            layoutManager.updateKeyLabelSize(settings.keyLabelSize)
            layoutManager.updateSpaceBarSize(settings.spaceBarSize)
            layoutManager.updateNumberHints(settings.showNumberHints)
            layoutManager.updateAdaptiveDimensions(dims)
            val keyboardView = layoutManager.createKeyboardView(layout, KeyboardState())
            val bg = knobs.keyboardBgColor ?: themeManager.currentTheme.value.colors.keyboardBackground
            keyboardView.setBackgroundColor(bg)

            previewContainer.removeAllViews()
            previewContainer.setBackgroundColor(bg)
            previewContainer.addView(previewHeader(entry))
            previewContainer.addView(keyboardView)
            previewContainer.visibility = View.VISIBLE
        }
    }

    /** The "<name>   [Activate]" bar above the live preview. */
    private fun previewHeader(entry: LayoutEntry): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = dp(10)
        setPadding(p, p, p, dp(4))
        addView(
            TextView(requireContext()).apply {
                text = "${entry.name} · ${langDisplay(entry.lang)}"
                setTextColor(0xFFFFFF00.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        addView(pillButton(getString(R.string.library_duplicate)) { duplicate(entry) })
        if (CustomLayoutStore.hasLayout(requireContext(), entry.id)) {
            addView(pillButton(getString(R.string.library_edit)) {
                startActivity(KeyboardEditorActivity.intent(requireContext(), entry))
            })
            addView(pillButton(getString(R.string.library_delete)) { delete(entry) })
        }
        addView(pillButton(getString(R.string.library_activate)) { activate(entry) })
    }

    /** A black-box, yellow-text, yellow-border action button (matches 白い熊's look). */
    private fun pillButton(label: String, onClick: () -> Unit): Button = Button(requireContext()).apply {
        text = label
        setTextColor(0xFFFFFF00.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF000000.toInt())
            setStroke(dp(2), 0xFFFFFF00.toInt())
            cornerRadius = dp(4).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) }
        setOnClickListener { onClick() }
    }

    /** Copy a layout into the editable custom store (it then appears in the Library and is activatable). */
    private fun duplicate(entry: LayoutEntry) {
        lifecycleScope.launch {
            val raw = CustomLayoutStore.rawJson(requireContext(), entry.id)
            if (raw == null) {
                flash(getString(R.string.library_duplicate_failed))
                return@launch
            }
            val newId = CustomLayoutStore.freshId(requireContext(), entry.id)
            val newEntry = entry.copy(id = newId, name = "${entry.name} copy")
            CustomLayoutStore.saveLayout(requireContext(), newEntry, raw)
            rebuild()
            flash(getString(R.string.library_duplicated_toast, newEntry.name))
        }
    }

    /** Remove a custom layout (and reset any language that had it active back to the registry default). */
    private fun delete(entry: LayoutEntry) {
        lifecycleScope.launch {
            CustomLayoutStore.deleteLayout(requireContext(), entry.id)
            if (settingsRepository.getActiveLayoutForLanguage(entry.lang) == entry.id) {
                registry = LayoutRegistry.load(requireContext())
                registry.defaultFor(entry.lang)?.let { settingsRepository.setActiveLayoutForLanguage(entry.lang, it) }
            }
            previewContainer.visibility = View.GONE
            rebuild()
            flash(getString(R.string.library_deleted_toast, entry.name))
        }
    }

    private fun activate(entry: LayoutEntry) {
        lifecycleScope.launch {
            val result = settingsRepository.setActiveLayoutForLanguage(entry.lang, entry.id)
            if (result.isSuccess) {
                rebuild()
                flash(getString(R.string.library_applied_toast, entry.name, langDisplay(entry.lang)))
            } else {
                flash(getString(R.string.library_apply_failed))
            }
        }
    }

    private fun badgeText(entry: LayoutEntry, isActive: Boolean): String {
        val parts = listOf(entry.kind, entry.width)
            .filter { it.isNotBlank() }
            .map { it.replaceFirstChar { c -> c.uppercase() } }
            .toMutableList()
        if (isActive) parts.add(getString(R.string.library_active_word))
        return parts.joinToString(" · ")
    }

    private fun langDisplay(lang: String): String = when (lang) {
        "gnu" -> "GNU"
        "ja" -> "日本語"
        "cs" -> "Čeština"
        "ru" -> "Русский"
        "en" -> "English"
        else -> Locale.forLanguageTag(lang)
            .let { it.getDisplayLanguage(it) }
            .ifBlank { lang }
            .replaceFirstChar { it.uppercase() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
