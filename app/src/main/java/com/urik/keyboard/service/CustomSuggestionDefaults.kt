package com.urik.keyboard.service

import com.urik.keyboard.settings.KeyboardSettings

/**
 * Built-in custom-suggestion rows per language, and the resolution of a language's EFFECTIVE row.
 *
 * Each language can have its own toolbar (the static row shown in the suggestion bar). A fresh install gets
 * a sensible per-language default (English's general set, plus locale-appropriate quotes/marks for Czech,
 * Russian and Japanese); the user can override any language individually in the kxkb UI page. Stored
 * overrides live in [KeyboardSettings.customSuggestionsByLang]; a language without an override (including
 * English) shows its built-in default.
 */
object CustomSuggestionDefaults {
    // Czech: low-high quotes „…“ in place of English “…”.
    private val CS = listOf(
        "+", "-", "*", "#", "„…“", ":@)", "\"…\"", "(…)", "[Paste]", "[All]", "[…]",
        "{{yyyy-MM-dd ", "☺", "❤", "♡ ", "{…}", "{{yyyy-MM-dd_HH-mm-ss"
    ).joinToString("\n")

    // Russian: guillemets «…» plus the № sign.
    private val RU = listOf(
        "+", "-", "*", "#", "«…»", "№", ":@)", "\"…\"", "(…)", "[Paste]", "[All]", "[…]",
        "{{yyyy-MM-dd ", "☺", "❤", "♡ ", "{…}", "{{yyyy-MM-dd_HH-mm-ss"
    ).joinToString("\n")

    // Japanese: corner brackets 「…」 in place of English “…”.
    private val JA = listOf(
        "+", "-", "*", "#", "「…」", ":@)", "\"…\"", "(…)", "[Paste]", "[All]", "[…]",
        "{{yyyy-MM-dd ", "☺", "❤", "♡ ", "{…}", "{{yyyy-MM-dd_HH-mm-ss"
    ).joinToString("\n")

    /** The built-in default row for [lang] (English's general set for any language without its own). */
    fun forLanguage(lang: String): String = when (lang.substringBefore('-')) {
        "cs" -> CS
        "ru" -> RU
        "ja" -> JA
        else -> KeyboardSettings.DEFAULT_CUSTOM_SUGGESTIONS
    }

    /**
     * The effective raw row for [lang]: the user's [overrides] entry (an explicit empty string = "cleared",
     * honoured) wins; otherwise the built-in [forLanguage] default (English included).
     */
    fun effective(lang: String, overrides: Map<String, String>): String {
        val base = lang.substringBefore('-')
        return overrides[base] ?: forLanguage(base)
    }
}
