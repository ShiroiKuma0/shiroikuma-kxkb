package com.urik.keyboard.data

import android.content.Context
import org.json.JSONObject

/** One entry in the layout registry. */
data class LayoutEntry(
    val id: String,
    val lang: String,
    val name: String,
    val kind: String,
    val width: String,
    /**
     * For a custom layout created by editing a stock one: the bundled layout id it replaces. Such a copy
     * *shadows* its stock — it takes the stock's slot (same name) in the Library and switcher and the stock
     * is hidden; deleting the copy reinstates the stock. Null for bundled layouts and stand-alone duplicates.
     */
    val derivedFrom: String? = null
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

        /** Drop the cache so a freshly saved/deleted custom layout is reflected on the next [load]. */
        fun invalidate() {
            synchronized(this) { cached = null }
        }

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
                // Stock (bundled) and custom (the user's copies/edits) layouts COEXIST in the list — the
                // Library pills them apart ("stock"/"custom"); a custom no longer hides its origin stock.
                // `derivedFrom` is retained only for re-sync provenance and delete-reinstate. Custom ids are
                // always fresh, so appending never collides with a bundled id. A stock layout may carry a
                // user-set display-name override (the asset itself stays read-only).
                val overrides = CustomLayoutStore.stockNameOverrides(context)
                val stock = list.map { e -> overrides[e.id]?.let { e.copy(name = it) } ?: e }
                val effective = stock + CustomLayoutStore.customEntries(context)
                LayoutRegistry(defaults, effective)
            } catch (_: Exception) {
                LayoutRegistry(emptyMap(), CustomLayoutStore.customEntries(context))
            }
    }
}
