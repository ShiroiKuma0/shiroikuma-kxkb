package com.urik.keyboard.data

import android.content.Context
import org.json.JSONObject

/** One entry in the layout registry. */
data class LayoutEntry(
    val id: String,
    val lang: String,
    val name: String,
    val kind: String,
    val width: String
)

/**
 * Per-language available layouts, loaded once from `assets/layouts/registry.json`. The runtime resolves the
 * active layout for a language (registry default until the user overrides via the 1D switcher); the Library
 * and switcher enumerate [forLanguage]. Designed to scale to many layouts per language.
 *
 * Falls back to an empty registry (→ the bundled per-locale `<lang>.json` files) if the manifest is missing.
 */
class LayoutRegistry private constructor(
    private val defaults: Map<String, String>,
    val entries: List<LayoutEntry>
) {
    fun defaultFor(lang: String): String? = defaults[lang]

    fun forLanguage(lang: String): List<LayoutEntry> = entries.filter { it.lang == lang }

    fun has(id: String): Boolean = entries.any { it.id == id }

    companion object {
        @Volatile
        private var cached: LayoutRegistry? = null

        fun load(context: Context): LayoutRegistry =
            cached ?: synchronized(this) { cached ?: parse(context).also { cached = it } }

        private fun parse(context: Context): LayoutRegistry =
            try {
                val json =
                    context.assets.open("layouts/registry.json").bufferedReader().use { it.readText() }
                val root = JSONObject(json)
                val defaults = mutableMapOf<String, String>()
                root.optJSONObject("defaults")?.let { d ->
                    d.keys().forEach { key -> defaults[key] = d.getString(key) }
                }
                val list = mutableListOf<LayoutEntry>()
                root.optJSONArray("layouts")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            LayoutEntry(
                                id = o.getString("id"),
                                lang = o.getString("lang"),
                                name = o.optString("name", o.getString("id")),
                                kind = o.optString("kind", ""),
                                width = o.optString("width", "")
                            )
                        )
                    }
                }
                LayoutRegistry(defaults, list)
            } catch (_: Exception) {
                LayoutRegistry(emptyMap(), emptyList())
            }
    }
}
