package com.urik.keyboard.utils

import android.icu.util.ULocale
import java.util.Locale

/**
 * A language code's name in its OWN language (ja → "日本語", ru → "Русский", cs → "Čeština", gnu → "GNU"),
 * for user-facing pickers — the same convention the space bar uses. Mirrors
 * `KeyboardLayoutManager.nativeLanguageName` as a standalone, reusable helper.
 */
object LanguageDisplayNames {
    fun nativeName(code: String): String {
        if (code == "gnu") return "GNU"
        return try {
            val loc = ULocale.forLanguageTag(code)
            val name = loc.getDisplayName(loc)
            if (name.isNullOrEmpty() || name.length <= 2) {
                code.uppercase(Locale.ROOT)
            } else {
                name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
        } catch (_: Exception) {
            code.uppercase(Locale.ROOT)
        }
    }
}
