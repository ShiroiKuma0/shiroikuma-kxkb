package com.urik.keyboard.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Emits a futokxkb-style (futokxkb v2) compass-keyboard YAML from one of our layout JSON objects — the
 * inverse of `tools/gnu_yaml_to_json.py`'s INPUT schema. The converter reads such a YAML and writes the
 * exact JSON [KeyboardJsonCodec] parses; this emitter walks that JSON back to a YAML the converter would
 * read to an equivalent layout. The round trip is JSON → YAML (here) → JSON (converter): the two JSONs are
 * equivalent layouts, not byte-identical, since a few of the converter's derivations are lossy in reverse
 * (noted inline below).
 *
 * We hand-emit (no YAML library): the schema is just maps / lists / scalars. Strings are quoted only when
 * they need it (so plain glyphs read like the reference YAMLs).
 *
 * Schema produced (mirrors the reference futokxkb `kxkb` YAML files):
 *   name: "<name>"
 *   attributes: { … }            (top-level, only if present in JSON)
 *   numberRowMode: <mode>
 *   overrideWidths: { Custom1: …, … }
 *   topBar: [ … ]                (our extension; the converter passes it through, so we round-trip it)
 *   rows:                        (modes.letters)
 *     - letters: [ <key>, … ]
 *       attributes: { … }        (a uniform per-row attributes map factored back out, when present)
 *   altPages:                    (modes.numbers / symbols / symbols_secondary, in that order)
 *     - - letters: [ … ]
 *
 * Per key, the converter SPLITS futokxkb's single `attributes:` into our `appearance` + `attributes`
 * blocks. We RECOMBINE them into one `attributes:` map (appearance keys + attribute keys; `widthClass`
 * is written back as the futokxkb `width` token). Key `type`s map back as:
 *   character→(plain spec / base) · action→base(`!code/…`) · spacer→gap · compass/cluster/column→same ·
 *   macro/chord/cycle→same · case→case(branches recursed).
 */
object KeyboardYamlEmitter {

    private val DIRS =
        listOf("up", "down", "left", "right", "upLeft", "upRight", "downLeft", "downRight")
    private val CASE_BRANCHES =
        listOf("normal", "shifted", "shiftedManually", "shiftLocked", "symbols", "symbolsShifted")

    /** Map an Urik action name (as the codec emits it) back to a futokxkb `!code/<X>` token + icon. */
    private data class ActionSpec(val code: String, val icon: String?)

    // Inverse of gnu_yaml_to_json.py's CODE_NATIVE (native Urik action keys), plus the dynamic action.
    private val ACTION_TO_SPEC = mapOf(
        "shift" to ActionSpec("key_shift", "shift_key"),
        "backspace" to ActionSpec("key_delete", "delete_key"),
        "space" to ActionSpec("key_space", "space_key"),
        "tab" to ActionSpec("key_tab", "tab_key"),
        "language_switch" to ActionSpec("key_language_switch", null),
        // dynamic_action / enter both round-trip through the `key_enter` native key.
        "enter" to ActionSpec("key_enter", "enter_key"),
        "dynamic_action" to ActionSpec("key_enter", "enter_key")
    )

    // Inverse of ICON_LABEL: a rendered glyph (the only thing we still have in JSON) → its `!icon/<X>` token.
    // Lets an icon-only flick position (e.g. "↶") re-emit as `!icon/action_undo|!code/action_undo` so the
    // converter routes it back to the same editor action. We INTENTIONALLY OMIT the four directional arrows
    // (↑ ↓ ← →): unlike these symbols, an arrow doubles as legitimate literal content (the sym page authors
    // a plain "→" character), so re-tokenising a bare arrow would wrongly turn content into an action. A
    // genuine arrow *action* always carries an explicit `{action}` binding in JSON, which emits correctly via
    // [actionBindingToSpec] regardless — so the only arrows we'd re-tokenise here are the literal ones. The
    // remaining glyphs below never appear as content, so re-tokenising them is unambiguous.
    private val GLYPH_TO_ICON_CODE = mapOf(
        "↶" to ("action_undo" to "action_undo"),
        "↷" to ("action_redo" to "action_redo"),
        "⌄" to ("action_hide_keyboard" to "action_hide_keyboard"),
        "🎙" to ("action_voice_input" to "action_voice_input"),
        "✂" to ("action_cut" to "action_cut"),
        "⧉" to ("action_copy" to "action_copy"),
        "⎘" to ("action_paste" to "action_paste"),
        "全" to ("action_select_all" to "action_select_all")
    )

    // Appearance keys live first in the recombined attributes map (matches the reference ordering loosely).
    private val APPEARANCE_KEYS = listOf(
        "color", "fontScale", "hintScale", "backgroundColor", "borderColor",
        "labelOffsetX", "labelOffsetY", "clusterLeftOffset", "clusterRightOffset",
        "flickTopOffset", "flickBottomOffset", "flickLeftOffset", "flickRightOffset"
    )

    /** Emit the whole layout JSON as a futokxkb-style YAML string. */
    fun emit(layout: JSONObject): String {
        val sb = StringBuilder()
        layout.optString("name").takeIf { it.isNotEmpty() }
            ?.let { sb.append("name: ").append(quote(it)).append('\n') }
        layout.optJSONObject("attributes")?.let {
            sb.append("attributes: ").append(inlineMap(it)).append('\n')
        }
        layout.optString("numberRowMode").takeIf { it.isNotEmpty() }
            ?.let { sb.append("numberRowMode: ").append(scalar(it)).append('\n') }
        layout.optJSONObject("overrideWidths")?.let {
            sb.append("overrideWidths: ").append(inlineMap(it)).append('\n')
        }
        layout.optJSONArray("topBar")?.let { arr ->
            sb.append("topBar: [ ")
            sb.append((0 until arr.length()).joinToString(", ") { quote(arr.optString(it)) })
            sb.append(" ]\n")
        }

        val modes = layout.optJSONObject("modes") ?: JSONObject()

        // rows: the base (`letters`) page.
        sb.append('\n').append("rows:\n")
        emitPage(sb, modes.optJSONObject("letters")?.optJSONArray("rows"), indent = 0)

        // altPages: numbers / symbols / symbols_secondary / numpad, in the converter's argv order.
        val altModes = listOf("numbers", "symbols", "symbols_secondary", "numpad").mapNotNull { name ->
            modes.optJSONObject(name)?.optJSONArray("rows")
        }
        if (altModes.isNotEmpty()) {
            sb.append('\n').append("altPages:\n")
            for (rows in altModes) {
                sb.append("  -\n")
                emitPage(sb, rows, indent = 2)
            }
        }
        return sb.toString()
    }

    /**
     * Emit a page (a `rows` array) as a list of `- letters: [ … ]` entries. [indent] is the base indent in
     * spaces of the `-` bullet (0 for the top-level `rows:`, 2 for an `altPages` entry's inner list).
     */
    private fun emitPage(sb: StringBuilder, rows: JSONArray?, indent: Int) {
        if (rows == null) return
        val pad = " ".repeat(indent)
        for (r in 0 until rows.length()) {
            val row = rows.optJSONArray(r) ?: continue
            // Every authored row uses the `letters:` slot (the base page and alt pages both parse identically;
            // the futokxkb `bottom:` distinction is cosmetic — the converter reads letters→numbers→bottom).
            sb.append(pad).append("  - letters:\n")
            for (c in 0 until row.length()) {
                val key = row.optJSONObject(c) ?: continue
                sb.append(pad).append("      - ").append(emitKey(key)).append('\n')
            }
            // Factor out a row-uniform attribute map if every key shares one identical `width` token: the
            // reference YAMLs hoist `attributes: { width: CustomN }` to the row. We keep it simple and only
            // hoist a shared widthClass (the common case); per-key attributes stay inline otherwise.
            sharedWidthClass(row)?.let {
                sb.append(pad).append("    attributes: { width: ").append(scalar(it)).append(" }\n")
            }
        }
    }

    /** If every key in [row] carries the same `attributes.widthClass`, return it (to hoist to the row). */
    private fun sharedWidthClass(row: JSONArray): String? {
        if (row.length() == 0) return null
        var shared: String? = null
        for (c in 0 until row.length()) {
            val wc = row.optJSONObject(c)?.optJSONObject("attributes")?.optString("widthClass").orEmpty()
            if (wc.isEmpty()) return null
            if (shared == null) shared = wc else if (shared != wc) return null
        }
        return shared
    }

    /** Emit one key JSON object as an inline YAML flow node (a scalar string or a `{ … }` map). */
    private fun emitKey(key: JSONObject): String = when (key.optString("type")) {
        "character" -> emitCharacter(key)
        "action" -> emitAction(key)
        "spacer", "gap" -> "{ type: gap }"
        "compass", "flick" -> emitCompass(key)
        "cluster" -> emitCluster(key)
        "column" -> emitColumn(key)
        "macro" -> emitMacro(key)
        "chord" -> emitChord(key)
        "cycle" -> emitCycle(key)
        "case" -> emitCase(key)
        else -> "{ type: gap }"
    }

    /**
     * A plain character key. With no decoration it can be a bare scalar (the reference's `"@"` rows); if it
     * carries appearance/attributes it becomes a `base` key with a `spec`. moreKeys ride through as a list.
     */
    private fun emitCharacter(key: JSONObject): String {
        val char = key.optString("char")
        val attrs = recombinedAttributes(key)
        val more = key.optString("moreKeys")
        if (attrs == null && more.isEmpty()) return quote(char)
        val parts = mutableListOf("type: base", "spec: ${quote(char)}")
        if (more.isNotEmpty()) parts.add("moreKeys: " + listFlow(more.split("\n").filter { it.isNotEmpty() }))
        attrs?.let { parts.add("attributes: $it") }
        return "{ " + parts.joinToString(", ") + " }"
    }

    /** An action key → a futokxkb `base` whose `spec` is the `!icon/…|!code/…` token for that native action. */
    private fun emitAction(key: JSONObject): String {
        val spec = actionToSpec(key.optString("action"))
        val attrs = recombinedAttributes(key)
        val parts = mutableListOf("type: base", "spec: ${quote(spec)}")
        attrs?.let { parts.add("attributes: $it") }
        return "{ " + parts.joinToString(", ") + " }"
    }

    private fun emitCompass(key: JSONObject): String {
        val parts = mutableListOf("type: compass")
        parts.add("primary: ${quote(primaryOf(key))}")
        emitFlickParts(key, parts)
        recombinedAttributes(key)?.let { parts.add("attributes: $it") }
        return "{ " + parts.joinToString(", ") + " }"
    }

    private fun emitCluster(key: JSONObject): String {
        val parts = mutableListOf("type: cluster")
        val main = key.optString("cluster").ifEmpty { key.optString("main") }
        if (main.isNotEmpty()) parts.add("main: ${quote(main)}")
        // The converter derives left/right of a cluster from the band neighbours, so don't re-emit those
        // (they'd duplicate). Authored non-neighbour slides DO need re-emitting.
        emitFlickParts(key, parts, skipDerivedClusterNeighbours = main)
        recombinedAttributes(key)?.let { parts.add("attributes: $it") }
        return "{ " + parts.joinToString(", ") + " }"
    }

    private fun emitColumn(key: JSONObject): String {
        val parts = mutableListOf("type: column")
        val main = key.optString("main").ifEmpty { key.optString("cluster") }
        if (main.isNotEmpty()) parts.add("main: ${quote(main)}")
        // The converter derives up/down of a column from the band ends — skip those, keep the rest.
        emitFlickParts(key, parts, skipColumnEnds = true)
        recombinedAttributes(key)?.let { parts.add("attributes: $it") }
        return "{ " + parts.joinToString(", ") + " }"
    }

    private fun emitMacro(key: JSONObject): String =
        "{ type: macro, text: ${quote(key.optString("text"))} }"

    private fun emitChord(key: JSONObject): String {
        val keys = key.optString("keys")
        val label = key.optString("label").ifEmpty { keys }
        return "{ type: chord, keys: ${quote(keys)}, label: ${quote(label)} }"
    }

    private fun emitCycle(key: JSONObject): String {
        val taps = when (val t = key.opt("taps")) {
            is JSONArray -> (0 until t.length()).joinToString("") { t.optString(it) }
            is String -> t
            else -> ""
        }
        val label = key.optString("label").ifEmpty { taps.take(1) }
        return "{ type: cycle, taps: ${quote(taps)}, label: ${quote(label)} }"
    }

    private fun emitCase(key: JSONObject): String {
        val parts = mutableListOf("type: case")
        for (branch in CASE_BRANCHES) {
            val b = key.optJSONObject(branch) ?: continue
            parts.add("$branch: ${emitKey(b)}")
        }
        recombinedAttributes(key)?.let { parts.add("attributes: $it") }
        return "{ " + parts.joinToString(", ") + " }"
    }

    /**
     * Append the present flick directions of [key] as `dir: <value>` parts. A text slide emits as a quoted
     * scalar (re-tokenising an icon glyph back to `!icon/…|!code/…`); a binding object emits as its futokxkb
     * shape: action→`label|!code/…`, chord→`{ chord: …, label: … }`, layer→`label|!code/key_to_…`.
     */
    private fun emitFlickParts(
        key: JSONObject,
        parts: MutableList<String>,
        skipDerivedClusterNeighbours: String? = null,
        skipColumnEnds: Boolean = false
    ) {
        val flick = key.optJSONObject("flick") ?: return
        // Derived cluster neighbours (left/right of the band centre) — don't re-author them.
        val derived = mutableSetOf<String>()
        skipDerivedClusterNeighbours?.takeIf { it.isNotEmpty() }?.let { band ->
            val centre = band.length / 2
            if (centre - 1 >= 0) derived.add("left")
            if (centre + 1 < band.length) derived.add("right")
        }
        if (skipColumnEnds) { derived.add("up"); derived.add("down") }

        for (dir in DIRS) {
            if (dir in derived) continue
            val raw = flick.opt(dir) ?: continue
            val rendered = flickValue(raw) ?: continue
            parts.add("$dir: $rendered")
        }
    }

    /** A flick-position raw JSON value → its futokxkb YAML flow form (a quoted scalar or an inline map). */
    private fun flickValue(raw: Any?): String? = when (raw) {
        is String -> raw.takeIf { it.isNotEmpty() }?.let { quote(reTokenizeIcon(it)) }
        is JSONObject -> when {
            raw.has("chord") -> {
                val spec = raw.optString("chord")
                val label = raw.optString("label").ifEmpty { spec }
                "{ chord: ${quote(spec)}, label: ${quote(label)} }"
            }
            raw.has("action") -> {
                val label = raw.optString("label")
                quote(actionBindingToSpec(raw.optString("action"), label))
            }
            raw.has("layer") -> {
                val target = raw.optString("layer")
                val label = raw.optString("label").ifEmpty { target }
                quote(layerToSpec(target, label))
            }
            raw.has("text") -> raw.optString("text").takeIf { it.isNotEmpty() }
                ?.let { quote(reTokenizeIcon(it)) }
            raw.has("type") -> emitKey(raw) // a structured nested key (rare on a slide)
            else -> null
        }
        else -> null
    }

    /** The compass primary (centre): its char, re-tokenised if it's an icon glyph or a centre binding. */
    private fun primaryOf(key: JSONObject): String {
        val centerBinding = key.optJSONObject("flick")?.opt("center")
        if (centerBinding is JSONObject) {
            when {
                centerBinding.has("action") ->
                    return actionBindingToSpec(centerBinding.optString("action"), centerBinding.optString("label"))
                centerBinding.has("layer") ->
                    return layerToSpec(centerBinding.optString("layer"),
                        centerBinding.optString("label").ifEmpty { key.optString("char") })
            }
        }
        return reTokenizeIcon(key.optString("char"))
    }

    /** An action name on a native action key → `!icon/<X>|!code/<code>` or `!code/<code>`. */
    private fun actionToSpec(action: String): String {
        val spec = ACTION_TO_SPEC[action]
            ?: return "!code/$action" // unknown action: pass the name through as a code token (lossy in name).
        return if (spec.icon != null) "!icon/${spec.icon}|!code/${spec.code}" else "!code/${spec.code}"
    }

    /** An action *binding* (on a flick/centre) → `label|!code/<X>` (the converter reads the code, keeps label). */
    private fun actionBindingToSpec(action: String, label: String): String {
        // Map our generic editor action name back to the futokxkb !code token where we know it; else reuse it.
        val code = ACTION_BINDING_TO_CODE[action] ?: action
        val lbl = label.ifEmpty { reTokenizeIcon(label) }
        return if (lbl.isEmpty()) "!code/$code" else "$lbl|!code/$code"
    }

    /** A layer target → `label|!code/key_to_<layer>_layout`. */
    private fun layerToSpec(target: String, label: String): String {
        val code = LAYER_TO_CODE[target] ?: "key_to_${target}_layout"
        return if (label.isEmpty()) "!code/$code" else "$label|!code/$code"
    }

    // Inverse of CODE_ACTION (generic editor actions) — the names the converter resolves at runtime.
    private val ACTION_BINDING_TO_CODE = mapOf(
        "escape" to "key_escape", "tab" to "key_tab", "enter" to "key_enter", "space" to "key_space",
        "ctrl" to "key_ctrl", "undo" to "action_undo", "redo" to "action_redo",
        "hide" to "action_hide_keyboard", "voice" to "action_voice_input",
        "arrow_up" to "action_up", "arrow_down" to "action_down",
        "arrow_left" to "action_left", "arrow_right" to "action_right",
        "next_language" to "action_next_language_layout",
        "cut" to "action_cut", "copy" to "action_copy", "paste" to "action_paste",
        "select_all" to "action_select_all", "settings" to "action_settings"
    )

    // Inverse of CODE_LAYER.
    private val LAYER_TO_CODE = mapOf(
        "alt0" to "key_to_alt_0_layout", "alt1" to "key_to_alt_1_layout",
        "alpha0" to "key_to_alpha_0_layout", "alpha1" to "key_to_alpha_1_layout"
    )

    /** Re-tokenise a bare icon glyph (rendered in JSON) back to its `!icon/<X>|!code/<X>` spec when known. */
    private fun reTokenizeIcon(s: String): String {
        val pair = GLYPH_TO_ICON_CODE[s] ?: return s
        return "!icon/${pair.first}|!code/${pair.second}"
    }

    /**
     * Recombine our split `appearance` + `attributes` blocks into the single futokxkb `attributes:` map the
     * converter reads — `widthClass` is written back as the `width` token. Returns the inline `{ … }` flow
     * string, or null when the key carries no attributes (so an undecorated key stays a bare scalar).
     */
    private fun recombinedAttributes(key: JSONObject): String? {
        val out = LinkedHashMap<String, Any>()
        key.optJSONObject("appearance")?.let { app ->
            for (k in APPEARANCE_KEYS) if (app.has(k)) out[k] = app.get(k)
        }
        key.optJSONObject("attributes")?.let { at ->
            for (k in at.keys()) {
                val v = at.get(k)
                if (k == "widthClass") out["width"] = v else out[k] = v
            }
        }
        if (out.isEmpty()) return null
        return "{ " + out.entries.joinToString(", ") { (k, v) -> "$k: ${scalar(v)}" } + " }"
    }

    // ---- scalar / quoting helpers ----------------------------------------------------------------

    /** An inline `{ k: v, … }` map from a JSON object (used for top-level attributes/overrideWidths). */
    private fun inlineMap(o: JSONObject): String {
        val keys = o.keys().asSequence().toList()
        if (keys.isEmpty()) return "{}"
        return "{ " + keys.joinToString(", ") { k -> "$k: ${scalar(o.get(k))}" } + " }"
    }

    /** A `[ a, b, c ]` flow list of quoted strings. */
    private fun listFlow(items: List<String>): String =
        "[ " + items.joinToString(", ") { quote(it) } + " ]"

    /** Render a JSON scalar: numbers/bools bare, strings quoted only when needed. */
    private fun scalar(v: Any?): String = when (v) {
        is Boolean, is Int, is Long, is Double, is Float -> v.toString()
        is String -> if (needsQuote(v) || v.isEmpty()) quote(v) else v
        else -> quote(v.toString())
    }

    /** Quote a YAML string (double-quoted, escaping `\` and `"` and control chars). */
    private fun quote(s: String): String {
        val esc = StringBuilder("\"")
        for (ch in s) when (ch) {
            '\\' -> esc.append("\\\\")
            '"' -> esc.append("\\\"")
            '\n' -> esc.append("\\n")
            '\t' -> esc.append("\\t")
            else -> esc.append(ch)
        }
        return esc.append('"').toString()
    }

    /** A bare YAML scalar would be misread if it holds flow/indicator chars or looks like a number/bool. */
    private fun needsQuote(s: String): Boolean {
        if (s.isEmpty()) return true
        if (s.toDoubleOrNull() != null) return true
        if (s in setOf("true", "false", "null", "yes", "no", "on", "off")) return true
        return s.any { it in ":{}[],&*#?|-<>=!%@`\"'\n\t" } || s.first() == ' ' || s.last() == ' '
    }
}
