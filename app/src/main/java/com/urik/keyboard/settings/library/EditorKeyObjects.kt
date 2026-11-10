package com.urik.keyboard.settings.library

import org.json.JSONObject

/**
 * How the key editor turns a layout cell or a slide into a key object it can edit, and back to text. A slide's
 * binding must survive being opened: the result has to parse back to the same binding.
 */
internal object EditorKeyObjects {
    /**
     * Coerce a cell's or slot's raw JSON (string | object | absent) into a full key object the editor can recurse
     * on. Only a SLIDE ([inSlide]) can hold an action, chord or layer binding; anywhere else a binding object is
     * read as the character it shows.
     */
    fun asKeyObject(raw: Any?, inSlide: Boolean = false): JSONObject = when (raw) {
        is JSONObject -> when {
            raw.has("type") -> raw
            !inSlide -> JSONObject().put("type", "character").put("char", bindingLabel(raw))
            // A slide's binding ({action|chord|layer}) becomes the key object that keeps it — an action key,
            // a chord key, a layer slide — label included. Wrapped as a plain character, just OPENING the
            // slide turned the Tab action into the text "⇥" (白い熊, 2026-11-10).
            raw.has("action") -> JSONObject().put("type", "action").put("action", raw.optString("action"))
                .withLabelOf(raw)
            raw.has("chord") -> JSONObject().put("type", "chord").put("keys", raw.optString("chord"))
                .withLabelOf(raw)
            raw.has("layer") -> JSONObject().put("type", "layer").put("layer", raw.optString("layer"))
                .withLabelOf(raw)
            else -> JSONObject().put("type", "character").put("char", bindingLabel(raw))
        }
        is String -> JSONObject().put("type", "character").put("char", raw)
        else -> JSONObject().put("type", "character").put("char", "")
    }

    private fun JSONObject.withLabelOf(binding: JSONObject): JSONObject =
        binding.optString("label").takeIf { it.isNotEmpty() }?.let { put("label", it) } ?: this

    fun bindingLabel(o: JSONObject): String = when {
        o.has("text") -> o.optString("text")
        o.has("label") -> o.optString("label")
        o.has("action") -> o.optString("action")
        o.has("chord") -> o.optString("chord")
        o.has("layer") -> o.optString("layer")
        else -> ""
    }
}
