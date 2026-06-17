package com.urik.keyboard.settings.library

import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.urik.keyboard.R
import com.urik.keyboard.data.LayoutEntry
import com.urik.keyboard.data.LayoutRegistry
import com.urik.keyboard.service.KeyboardFonts
import com.urik.keyboard.service.LibraryLook
import com.urik.keyboard.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The Library — browse every layout in the registry, grouped under a big underlined language heading, and
 * tap one to make it the active layout for that language. The change applies the next time the keyboard is
 * shown (the active layout is resolved per-language on input start). The whole list look (separators,
 * spacing, indent, per-category fonts/sizes/colours) is settable on the kxkb UI page ([LibraryLook]).
 */
@AndroidEntryPoint
class LibraryFragment : Fragment() {
    @Inject lateinit var settingsRepository: SettingsRepository

    private lateinit var registry: LayoutRegistry
    private lateinit var listContainer: LinearLayout
    private var look = LibraryLook()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        registry = LayoutRegistry.load(requireContext())
        listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        return ScrollView(requireContext()).apply {
            isFillViewport = true
            setBackgroundColor(Color.BLACK)
            addView(
                listContainer,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rebuild()
    }

    /** Re-read the look + active layouts, then rebuild the whole list (also called after Apply / on resume). */
    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        lifecycleScope.launch {
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
            setOnClickListener { apply(entry) }
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

    private fun apply(entry: LayoutEntry) {
        lifecycleScope.launch {
            val result = settingsRepository.setActiveLayoutForLanguage(entry.lang, entry.id)
            if (result.isSuccess) {
                rebuild()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.library_applied_toast, entry.name, langDisplay(entry.lang)),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(requireContext(), R.string.library_apply_failed, Toast.LENGTH_SHORT).show()
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
