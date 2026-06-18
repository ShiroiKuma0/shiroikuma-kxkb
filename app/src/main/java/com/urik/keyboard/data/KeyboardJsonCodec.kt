package com.urik.keyboard.data

import com.urik.keyboard.model.KeyAppearance
import com.urik.keyboard.model.KeyAttributes
import com.urik.keyboard.model.KeyboardKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single, lossless place that converts a layout key between its JSON form and the [KeyboardKey] model —
 * both directions. [parseKey] is the canonical parser (the body that used to live inline in
 * [KeyboardRepository.parseKeyFromJson]); [emitKey] is its exact inverse. The contract is
 * `parseKey(emitKey(k)) == k` for a fixed `currentAction`, so the editor and the converter can round-trip a
 * key through the model without losing anything the model represents.
 *
 * Capability roadmap (M4 L2): new key types (compass/cluster/column/macro/chord/cycle/case) and the per-key
 * attributes/appearance blocks are added here as additive `type` branches; the internal model keeps Urik's
 * `FlickKey` for compass/cluster (rebase-safe). Anything a branch can't yet model is preserved verbatim.
 */
object KeyboardJsonCodec {
    private val SENTENCE_PUNCTUATION_CHARS = setOf('.', ',', ':', ';', '!', '?')

    private val FLICK_DIRECTIONS =
        listOf("up", "down", "left", "right", "upLeft", "upRight", "downLeft", "downRight")

    /** Parse one key object. `currentAction` resolves a `dynamic_action` key to the field's contextual action. */
    fun parseKey(keyData: JSONObject, currentAction: KeyboardKey.ActionType): KeyboardKey {
        val key = when (val type = keyData.getString("type")) {
            "character" -> {
                val char = keyData.getString("char")
                val keyType = parseKeyType(keyData.optString("keyType", "letter"))
                KeyboardKey.Character(char, keyType, keyData.optDouble("width", 0.0).toFloat())
            }

            "action" -> {
                val actionType = parseActionType(keyData.getString("action"), currentAction)
                KeyboardKey.Action(actionType, keyData.optDouble("width", 0.0).toFloat())
            }

            // "gap" is the futokxkb spelling of an empty slot; "spacer" is Urik's. Both → Spacer.
            "spacer", "gap" -> KeyboardKey.Spacer

            // Compass (directional) and cluster (horizontal band) keys are both Urik FlickKeys. Accept the
            // futokxkb canonical `compass`/`cluster` type names (band in `main`) AND the legacy `flick` shape
            // (band in `cluster`); `parseFlick` reads either. They converge on the same model.
            "flick", "compass", "cluster" -> parseFlick(keyData, currentAction, columnar = false)

            // A futokxkb `column`: a cluster band stacked vertically. Same parsing as a cluster/compass
            // flick key; the band lives in `main`, and its ends ride the up/down slides.
            "column" -> parseFlick(keyData, currentAction, columnar = true)

            // A futokxkb `macro`: commits its text literally on tap (a FlickKey whose multi-char centre is
            // committed directly — see FlickGestureDetector's centre-tap path).
            "macro" -> KeyboardKey.FlickKey(
                center = keyData.optString("text").ifEmpty { keyData.optString("char") },
                up = null, right = null, down = null, left = null,
                type = parseKeyType(keyData.optString("keyType", "letter")),
                tapKind = KeyboardKey.TapKind.MACRO,
                width = keyData.optDouble("width", 0.0).toFloat()
            )

            // A futokxkb `chord`: fires a modifier chord on tap (a FlickKey carrying a `center` chord binding,
            // which onFlickCommit already executes via sendChord).
            "chord" -> {
                val keys = keyData.getString("keys")
                KeyboardKey.FlickKey(
                    center = keyData.optString("label").ifEmpty { keys },
                    up = null, right = null, down = null, left = null,
                    type = parseKeyType(keyData.optString("keyType", "letter")),
                    bindings = mapOf("center" to KeyboardKey.FlickBinding.Chord(keys)),
                    tapKind = KeyboardKey.TapKind.CHORD,
                    width = keyData.optDouble("width", 0.0).toFloat()
                )
            }

            // A futokxkb `cycle`: repeated taps step through `taps` (a string of codepoints or a list).
            "cycle" -> {
                val taps = parseTaps(keyData.opt("taps"))
                KeyboardKey.FlickKey(
                    center = keyData.optString("label").ifEmpty { taps.firstOrNull().orEmpty() },
                    up = null, right = null, down = null, left = null,
                    type = parseKeyType(keyData.optString("keyType", "letter")),
                    cycleTaps = taps,
                    tapKind = KeyboardKey.TapKind.CYCLE,
                    width = keyData.optDouble("width", 0.0).toFloat()
                )
            }

            // A futokxkb `case` (CaseSelector): a base face (`normal`) plus a distinct face per shift state.
            // Modelled on the base FlickKey via `caseFaces`; `flickFace` picks the right one at render time.
            "case" -> {
                val base = parseCaseBranch(keyData.opt("normal"), currentAction)
                    ?: throw IllegalArgumentException("case key missing 'normal'")
                val baseFlick = asFlick(base) ?: return base
                val faces = LinkedHashMap<String, KeyboardKey.FlickKey>()
                for (state in CASE_STATES) {
                    parseCaseBranch(keyData.opt(state), currentAction)?.let { asFlick(it) }
                        ?.let { faces[state] = it }
                }
                baseFlick.copy(
                    caseFaces = faces,
                    shifted = faces["shifted"] ?: faces["shiftedManually"] ?: faces["shiftLocked"]
                        ?: baseFlick.shifted
                )
            }

            else -> throw IllegalArgumentException("Unknown key type: $type")
        }
        return decorate(key, keyData)
    }

    /** Attach the per-key [KeyAppearance] + [KeyAttributes] blocks, propagating them to nested faces. */
    private fun decorate(key: KeyboardKey, keyData: JSONObject): KeyboardKey {
        val app = parseAppearance(keyData.optJSONObject("appearance"))
        val attrs = parseAttributes(keyData.optJSONObject("attributes"))
        if (app == null && attrs == null) return key
        return when (key) {
            is KeyboardKey.Character ->
                key.copy(appearance = app ?: key.appearance, attributes = attrs ?: key.attributes)
            is KeyboardKey.Action ->
                key.copy(appearance = app ?: key.appearance, attributes = attrs ?: key.attributes)
            is KeyboardKey.FlickKey -> key.copy(
                appearance = app ?: key.appearance,
                attributes = attrs ?: key.attributes,
                shifted = key.shifted?.let {
                    it.copy(appearance = it.appearance ?: app, attributes = it.attributes ?: attrs)
                },
                caseFaces = key.caseFaces.mapValues { (_, f) ->
                    f.copy(appearance = f.appearance ?: app, attributes = f.attributes ?: attrs)
                }
            )
            KeyboardKey.Spacer -> key
        }
    }

    private fun parseAttributes(o: JSONObject?): KeyAttributes? {
        if (o == null) return null
        fun s(k: String): String? = o.optString(k, "").ifEmpty { null }
        fun b(k: String): Boolean? = if (o.has(k)) o.getBoolean(k) else null
        fun f(k: String): Float? = if (o.has(k)) o.getDouble(k).toFloat() else null
        val a = KeyAttributes(
            widthClass = s("widthClass") ?: s("width"),
            style = s("style"),
            moreKeyMode = s("moreKeyMode"),
            heightRows = f("heightRows") ?: f("rowSpan"),
            showPopup = b("showPopup"),
            longPressEnabled = b("longPressEnabled"),
            repeatableEnabled = b("repeatableEnabled"),
            anchored = b("anchored"),
            useKeySpecShortcut = b("useKeySpecShortcut"),
            shiftable = b("shiftable"),
            fastMoreKeys = b("fastMoreKeys")
        )
        return if (a == KeyAttributes()) null else a
    }

    private fun emitAttributes(a: KeyAttributes): JSONObject {
        val o = JSONObject()
        a.widthClass?.let { o.put("widthClass", it) }
        a.style?.let { o.put("style", it) }
        a.moreKeyMode?.let { o.put("moreKeyMode", it) }
        a.heightRows?.let { o.put("heightRows", it.toDouble()) }
        a.showPopup?.let { o.put("showPopup", it) }
        a.longPressEnabled?.let { o.put("longPressEnabled", it) }
        a.repeatableEnabled?.let { o.put("repeatableEnabled", it) }
        a.anchored?.let { o.put("anchored", it) }
        a.useKeySpecShortcut?.let { o.put("useKeySpecShortcut", it) }
        a.shiftable?.let { o.put("shiftable", it) }
        a.fastMoreKeys?.let { o.put("fastMoreKeys", it) }
        return o
    }

    private fun parseAppearance(o: JSONObject?): KeyAppearance? {
        if (o == null) return null
        fun f(k: String): Float? = if (o.has(k)) o.getDouble(k).toFloat() else null
        val a = KeyAppearance(
            color = parseColor(o.optString("color", "").ifEmpty { null }),
            fontScale = f("fontScale"),
            hintScale = f("hintScale"),
            backgroundColor = parseColor(o.optString("backgroundColor", "").ifEmpty { null }),
            borderColor = parseColor(o.optString("borderColor", "").ifEmpty { null }),
            labelOffsetX = f("labelOffsetX"),
            labelOffsetY = f("labelOffsetY"),
            clusterLeftOffset = f("clusterLeftOffset"),
            clusterRightOffset = f("clusterRightOffset"),
            flickTopOffset = f("flickTopOffset"),
            flickBottomOffset = f("flickBottomOffset"),
            flickLeftOffset = f("flickLeftOffset"),
            flickRightOffset = f("flickRightOffset")
        )
        return if (a == KeyAppearance()) null else a
    }

    private fun emitAppearance(a: KeyAppearance): JSONObject {
        val o = JSONObject()
        a.color?.let { o.put("color", colorHex(it)) }
        a.fontScale?.let { o.put("fontScale", it.toDouble()) }
        a.hintScale?.let { o.put("hintScale", it.toDouble()) }
        a.backgroundColor?.let { o.put("backgroundColor", colorHex(it)) }
        a.borderColor?.let { o.put("borderColor", colorHex(it)) }
        a.labelOffsetX?.let { o.put("labelOffsetX", it.toDouble()) }
        a.labelOffsetY?.let { o.put("labelOffsetY", it.toDouble()) }
        a.clusterLeftOffset?.let { o.put("clusterLeftOffset", it.toDouble()) }
        a.clusterRightOffset?.let { o.put("clusterRightOffset", it.toDouble()) }
        a.flickTopOffset?.let { o.put("flickTopOffset", it.toDouble()) }
        a.flickBottomOffset?.let { o.put("flickBottomOffset", it.toDouble()) }
        a.flickLeftOffset?.let { o.put("flickLeftOffset", it.toDouble()) }
        a.flickRightOffset?.let { o.put("flickRightOffset", it.toDouble()) }
        return o
    }

    /** Parse "#RRGGBB" or "#AARRGGBB" to an ARGB int (no android dependency, so the codec stays unit-testable). */
    private fun parseColor(s: String?): Int? {
        val hex = s?.removePrefix("#")?.takeIf { it.isNotBlank() } ?: return null
        val v = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> (0xFF000000L or v).toInt()
            8 -> v.toInt()
            else -> null
        }
    }

    private fun colorHex(c: Int): String = "#%08X".format(c.toLong() and 0xFFFFFFFFL)

    /** The shift-state branch names of a `case` key, in canonical order (most-specific shifted first). */
    private val CASE_STATES =
        listOf("shifted", "shiftedManually", "shiftLocked", "symbols", "symbolsShifted")

    /** A cycle key's taps are a string of single codepoints ("abc") or an explicit list of entries. */
    private fun parseTaps(raw: Any?): List<String> = when (raw) {
        is String -> raw.map { it.toString() }
        is JSONArray -> (0 until raw.length()).map { raw.getString(it) }
        else -> emptyList()
    }

    /** Parse a `case` branch, which is a full key object or a bare character string. */
    private fun parseCaseBranch(raw: Any?, currentAction: KeyboardKey.ActionType): KeyboardKey? = when (raw) {
        is JSONObject -> parseKey(raw, currentAction)
        is String -> raw.takeIf { it.isNotEmpty() }
            ?.let { KeyboardKey.Character(it, KeyboardKey.KeyType.LETTER) }
        else -> null
    }

    /**
     * Parse a compass/cluster/column key into a [KeyboardKey.FlickKey]. A position is either a plain string
     * (text/macro) or an object binding: {"text": …} | {"action": …, "label": …} | {"chord": …} | {"layer": …}.
     * The band lives in `cluster` (horizontal) or `main` (vertical/column); for a [columnar] key the band ends
     * fall onto the up/down slides when not given explicitly.
     */
    private fun parseFlick(
        keyData: JSONObject,
        currentAction: KeyboardKey.ActionType,
        columnar: Boolean
    ): KeyboardKey.FlickKey {
        fun parseFlickPosition(raw: Any?): Pair<String?, KeyboardKey.FlickBinding?> = when (raw) {
            is String -> raw.takeIf { it.isNotEmpty() } to null
            is JSONObject -> when {
                raw.has("text") -> raw.getString("text").takeIf { it.isNotEmpty() } to null
                raw.has("action") -> {
                    val name = raw.getString("action")
                    raw.optString("label").ifEmpty { name } to KeyboardKey.FlickBinding.Action(name)
                }
                raw.has("chord") -> {
                    val spec = raw.getString("chord")
                    raw.optString("label").ifEmpty { spec } to KeyboardKey.FlickBinding.Chord(spec)
                }
                raw.has("layer") -> {
                    val target = raw.getString("layer")
                    raw.optString("label").ifEmpty { target } to KeyboardKey.FlickBinding.Layer(target)
                }
                else -> null to null
            }
            else -> null to null
        }

        val keyType = when (keyData.optString("keyType", "")) {
            "letter" -> KeyboardKey.KeyType.LETTER
            "number" -> KeyboardKey.KeyType.NUMBER
            "symbol" -> KeyboardKey.KeyType.SYMBOL
            "punctuation" -> KeyboardKey.KeyType.PUNCTUATION
            else -> {
                // No explicit type: a sentence-punctuation centre (". , : ; ! ?") is a punctuation key, so it
                // routes to the non-letter handler (auto-spacing, no auto-shift). Else default to letter.
                val c = keyData.optString("char").firstOrNull()
                if (c != null && c in SENTENCE_PUNCTUATION_CHARS) {
                    KeyboardKey.KeyType.PUNCTUATION
                } else {
                    KeyboardKey.KeyType.LETTER
                }
            }
        }
        val flickObj = keyData.optJSONObject("flick")
        val bindings = mutableMapOf<String, KeyboardKey.FlickBinding>()
        fun pos(name: String): String? {
            val (label, binding) = parseFlickPosition(flickObj?.opt(name))
            if (binding != null) bindings[name] = binding
            return label
        }
        val band = keyData.optString("cluster").ifEmpty { keyData.optString("main") }
        val center = keyData.optString("char")
            .ifEmpty { band.getOrNull(band.length / 2)?.toString().orEmpty() }
        parseFlickPosition(flickObj?.opt("center")).second?.let { bindings["center"] = it }
        return KeyboardKey.FlickKey(
            center = center,
            up = pos("up") ?: if (columnar) band.firstOrNull()?.toString() else null,
            right = pos("right"),
            down = pos("down") ?: if (columnar) band.lastOrNull()?.toString() else null,
            left = pos("left"),
            type = keyType,
            upLeft = pos("upLeft"),
            upRight = pos("upRight"),
            downLeft = pos("downLeft"),
            downRight = pos("downRight"),
            bindings = bindings,
            clusterMains = band,
            columnar = columnar,
            shifted = keyData.optJSONObject("shifted")
                ?.let { parseKey(it, currentAction) as? KeyboardKey.FlickKey },
            width = keyData.optDouble("width", 0.0).toFloat()
        )
    }

    /** Coerce a key into a FlickKey so it can be a case face: a plain Character becomes a flat flick key. */
    private fun asFlick(key: KeyboardKey): KeyboardKey.FlickKey? = when (key) {
        is KeyboardKey.FlickKey -> key
        is KeyboardKey.Character -> KeyboardKey.FlickKey(
            center = key.value, up = null, right = null, down = null, left = null,
            type = key.type, width = key.width
        )
        else -> null
    }

    /** Emit one key object — the inverse of [parseKey]. Writes today's canonical JSON shape. */
    fun emitKey(key: KeyboardKey): JSONObject = when (key) {
        is KeyboardKey.Character ->
            JSONObject()
                .put("type", "character")
                .put("char", key.value)
                .put("keyType", keyTypeName(key.type))
                .withWidth(key.width)
                .withAppearance(key.appearance)
                .withAttributes(key.attributes)

        is KeyboardKey.Action ->
            JSONObject()
                .put("type", "action")
                .put("action", actionName(key.action))
                .withWidth(key.width)
                .withAppearance(key.appearance)
                .withAttributes(key.attributes)

        KeyboardKey.Spacer -> JSONObject().put("type", "spacer")

        is KeyboardKey.FlickKey -> when {
            key.caseFaces.isNotEmpty() -> emitCase(key)
            key.tapKind == KeyboardKey.TapKind.MACRO -> emitTapKey(key, "macro")
            key.tapKind == KeyboardKey.TapKind.CHORD -> emitTapKey(key, "chord")
            key.tapKind == KeyboardKey.TapKind.CYCLE -> emitTapKey(key, "cycle")
            key.columnar -> emitBandedFlick(key, "column", "main")
            else -> emitFlick(key)
        }
    }

    /** Emit a macro / chord / cycle tap key — the inverse of those parse branches. */
    private fun emitTapKey(key: KeyboardKey.FlickKey, typeName: String): JSONObject {
        val o = JSONObject().put("type", typeName).put("keyType", keyTypeName(key.type))
        when (typeName) {
            "macro" -> o.put("text", key.center)
            "chord" -> {
                o.put("keys", (key.bindings["center"] as? KeyboardKey.FlickBinding.Chord)?.spec ?: "")
                o.put("label", key.center)
            }
            "cycle" -> {
                o.put("taps", JSONArray(key.cycleTaps))
                o.put("label", key.center)
            }
        }
        return o.withWidth(key.width).withAppearance(key.appearance).withAttributes(key.attributes)
    }

    /** Emit a multi-state `case` key — the inverse of the `case` parse branch. */
    private fun emitCase(key: KeyboardKey.FlickKey): JSONObject {
        val o = JSONObject().put("type", "case")
        // `normal` is the base face with its case data stripped; each state is emitted as a sibling branch.
        // Routing through emitKey keeps a columnar base/face emitting as `column`.
        o.put("normal", emitKey(key.copy(caseFaces = emptyMap(), shifted = null)))
        for (state in CASE_STATES) key.caseFaces[state]?.let { o.put(state, emitKey(it)) }
        return o
    }

    private fun emitFlick(key: KeyboardKey.FlickKey): JSONObject = emitBandedFlick(key, "flick", "cluster")

    /** Emit a compass/cluster/column flick key. [bandField] is `cluster` (horizontal) or `main` (column). */
    private fun emitBandedFlick(key: KeyboardKey.FlickKey, typeName: String, bandField: String): JSONObject {
        val o = JSONObject()
            .put("type", typeName)
            .put("char", key.center)
            .put("keyType", keyTypeName(key.type))
        if (key.clusterMains.isNotEmpty()) o.put(bandField, key.clusterMains)

        val flick = JSONObject()
        // Centre carries only a binding (its text is always `char`); parse ignores any label here.
        key.bindings["center"]?.let { flick.put("center", bindingObject(it, null)) }
        for (dir in FLICK_DIRECTIONS) {
            val text = flickText(key, dir)
            val binding = key.bindings[dir]
            when {
                binding != null -> flick.put(dir, bindingObject(binding, text))
                text != null -> flick.put(dir, text)
            }
        }
        if (flick.length() > 0) o.put("flick", flick)

        key.shifted?.let { o.put("shifted", emitKey(it)) }
        o.withWidth(key.width)
        o.withAppearance(key.appearance)
        o.withAttributes(key.attributes)
        return o
    }

    private fun bindingObject(binding: KeyboardKey.FlickBinding, label: String?): JSONObject {
        val obj = when (binding) {
            is KeyboardKey.FlickBinding.Action -> JSONObject().put("action", binding.name)
            is KeyboardKey.FlickBinding.Chord -> JSONObject().put("chord", binding.spec)
            is KeyboardKey.FlickBinding.Layer -> JSONObject().put("layer", binding.target)
        }
        if (label != null) obj.put("label", label)
        return obj
    }

    private fun flickText(key: KeyboardKey.FlickKey, dir: String): String? = when (dir) {
        "up" -> key.up
        "down" -> key.down
        "left" -> key.left
        "right" -> key.right
        "upLeft" -> key.upLeft
        "upRight" -> key.upRight
        "downLeft" -> key.downLeft
        "downRight" -> key.downRight
        else -> null
    }

    private fun JSONObject.withWidth(width: Float): JSONObject =
        if (width > 0f) put("width", width.toDouble()) else this

    private fun JSONObject.withAppearance(a: KeyAppearance?): JSONObject =
        if (a != null) put("appearance", emitAppearance(a)) else this

    private fun JSONObject.withAttributes(a: KeyAttributes?): JSONObject =
        if (a != null) put("attributes", emitAttributes(a)) else this

    private fun parseKeyType(name: String): KeyboardKey.KeyType = when (name) {
        "letter" -> KeyboardKey.KeyType.LETTER
        "number" -> KeyboardKey.KeyType.NUMBER
        "symbol" -> KeyboardKey.KeyType.SYMBOL
        "punctuation" -> KeyboardKey.KeyType.PUNCTUATION
        else -> KeyboardKey.KeyType.LETTER
    }

    private fun keyTypeName(type: KeyboardKey.KeyType): String = when (type) {
        KeyboardKey.KeyType.LETTER -> "letter"
        KeyboardKey.KeyType.NUMBER -> "number"
        KeyboardKey.KeyType.SYMBOL -> "symbol"
        KeyboardKey.KeyType.PUNCTUATION -> "punctuation"
    }

    private fun parseActionType(
        name: String,
        currentAction: KeyboardKey.ActionType
    ): KeyboardKey.ActionType = when (name) {
        "shift" -> KeyboardKey.ActionType.SHIFT
        "backspace" -> KeyboardKey.ActionType.BACKSPACE
        "space" -> KeyboardKey.ActionType.SPACE
        "mode_switch_numbers" -> KeyboardKey.ActionType.MODE_SWITCH_NUMBERS
        "mode_switch_letters" -> KeyboardKey.ActionType.MODE_SWITCH_LETTERS
        "mode_switch_symbols" -> KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS
        "mode_switch_symbols_secondary" -> KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY
        "caps_lock" -> KeyboardKey.ActionType.CAPS_LOCK
        "dynamic_action" -> currentAction
        "dakuten" -> KeyboardKey.ActionType.DAKUTEN
        "small_kana" -> KeyboardKey.ActionType.SMALL_KANA
        "next_candidate" -> KeyboardKey.ActionType.NEXT_CANDIDATE
        "commit_candidate" -> KeyboardKey.ActionType.COMMIT_CANDIDATE
        "handakuten" -> KeyboardKey.ActionType.HANDAKUTEN
        "emoji" -> KeyboardKey.ActionType.EMOJI
        "language_switch" -> KeyboardKey.ActionType.LANGUAGE_SWITCH
        "tab" -> KeyboardKey.ActionType.TAB
        else -> KeyboardKey.ActionType.ENTER
    }

    private fun actionName(action: KeyboardKey.ActionType): String = when (action) {
        KeyboardKey.ActionType.SHIFT -> "shift"
        KeyboardKey.ActionType.BACKSPACE -> "backspace"
        KeyboardKey.ActionType.SPACE -> "space"
        KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> "mode_switch_numbers"
        KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> "mode_switch_letters"
        KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> "mode_switch_symbols"
        KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY -> "mode_switch_symbols_secondary"
        KeyboardKey.ActionType.CAPS_LOCK -> "caps_lock"
        KeyboardKey.ActionType.DAKUTEN -> "dakuten"
        KeyboardKey.ActionType.SMALL_KANA -> "small_kana"
        KeyboardKey.ActionType.NEXT_CANDIDATE -> "next_candidate"
        KeyboardKey.ActionType.COMMIT_CANDIDATE -> "commit_candidate"
        KeyboardKey.ActionType.HANDAKUTEN -> "handakuten"
        KeyboardKey.ActionType.EMOJI -> "emoji"
        KeyboardKey.ActionType.LANGUAGE_SWITCH -> "language_switch"
        KeyboardKey.ActionType.TAB -> "tab"
        // The contextual action key is stored as "dynamic_action" (the runtime resolves SEARCH/SEND/… and
        // ENTER per field); emit that spelling so a stored dynamic_action round-trips.
        KeyboardKey.ActionType.ENTER,
        KeyboardKey.ActionType.SEARCH,
        KeyboardKey.ActionType.SEND,
        KeyboardKey.ActionType.DONE,
        KeyboardKey.ActionType.GO,
        KeyboardKey.ActionType.NEXT,
        KeyboardKey.ActionType.PREVIOUS -> "dynamic_action"
    }
}
