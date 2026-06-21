package com.urik.keyboard.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-private store for user layouts (the editable half of the Library). Layout JSON lives in
 * `filesDir/layouts/<id>.json`; a sidecar `custom_registry.json` holds their [LayoutEntry] metadata so they
 * appear in the Library and the space-slide switcher alongside the bundled ones. The runtime
 * ([KeyboardRepository]) resolves a layout id from here first, falling back to the bundled assets. This is
 * the authoritative, self-contained store — nothing here depends on any external directory.
 */
object CustomLayoutStore {
    private fun dir(context: Context): File = File(context.filesDir, "layouts").apply { mkdirs() }

    private fun layoutFile(context: Context, id: String): File = File(dir(context), "$id.json")

    private fun registryFile(context: Context): File = File(dir(context), "custom_registry.json")

    private fun stockNamesFile(context: Context): File = File(dir(context), "stock_names.json")

    fun hasLayout(context: Context, id: String): Boolean = layoutFile(context, id).exists()

    /** User display-name overrides for BUNDLED (stock) layouts, `id → name`. Empty if none set. */
    fun stockNameOverrides(context: Context): Map<String, String> = try {
        val f = stockNamesFile(context)
        if (!f.exists()) {
            emptyMap()
        } else {
            JSONObject(f.readText()).let { o -> buildMap { o.keys().forEach { k -> put(k, o.getString(k)) } } }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Set (or, with a blank [name], clear) the display-name override for a bundled stock layout [id]. */
    fun setStockName(context: Context, id: String, name: String) {
        val o = try {
            JSONObject(stockNamesFile(context).readText())
        } catch (_: Exception) {
            JSONObject()
        }
        if (name.isBlank()) o.remove(id) else o.put(id, name)
        stockNamesFile(context).writeText(o.toString(2))
    }

    /** The custom-store layout JSON text for [id], or null when there's no override (use the bundled asset). */
    fun customLayoutText(context: Context, id: String): String? {
        val f = layoutFile(context, id)
        return if (f.exists()) f.readText().takeIf { it.isNotBlank() } else null
    }

    /** The custom registry entries (empty if none / unreadable). */
    fun customEntries(context: Context): List<LayoutEntry> = try {
        val f = registryFile(context)
        if (!f.exists()) emptyList()
        else JSONArray(f.readText()).let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LayoutEntry(
                    id = o.getString("id"),
                    lang = o.getString("lang"),
                    name = o.optString("name", o.getString("id")),
                    kind = o.optString("kind", ""),
                    width = o.optString("width", ""),
                    derivedFrom = o.optString("derivedFrom", "").takeIf { it.isNotEmpty() }
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Raw layout JSON for [id] — custom store first, then the bundled asset. Null if neither parses. */
    fun rawJson(context: Context, id: String): JSONObject? = try {
        val custom = layoutFile(context, id)
        val text =
            if (custom.exists()) custom.readText()
            else context.assets.open("layouts/$id.json").bufferedReader().use { it.readText() }
        JSONObject(text)
    } catch (_: Exception) {
        null
    }

    /** Save a custom layout (JSON + registry entry), then invalidate the registry cache so it shows up. */
    fun saveLayout(context: Context, entry: LayoutEntry, json: JSONObject) {
        layoutFile(context, entry.id).writeText(json.toString(2))
        val entries = customEntries(context).filter { it.id != entry.id } + entry
        writeRegistry(context, entries)
        LayoutRegistry.invalidate()
    }

    fun deleteLayout(context: Context, id: String) {
        layoutFile(context, id).delete()
        writeRegistry(context, customEntries(context).filter { it.id != id })
        LayoutRegistry.invalidate()
    }

    private fun writeRegistry(context: Context, entries: List<LayoutEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("lang", e.lang)
                    .put("name", e.name)
                    .put("kind", e.kind)
                    .put("width", e.width)
                    .apply { e.derivedFrom?.let { put("derivedFrom", it) } }
            )
        }
        registryFile(context).writeText(arr.toString(2))
    }

    /** A fresh, unused id derived from [baseId] (e.g. "en_3p2_5r9c_copy", "..._copy2", …). */
    fun freshId(context: Context, baseId: String): String {
        var candidate = "${baseId}_copy"
        var n = 2
        while (hasLayout(context, candidate) || LayoutRegistry.load(context).has(candidate)) {
            candidate = "${baseId}_copy$n"
            n++
        }
        return candidate
    }
}
