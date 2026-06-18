package com.urik.keyboard.settings.library

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.urik.keyboard.R
import com.urik.keyboard.data.CustomLayoutStore
import com.urik.keyboard.data.KeyboardJsonCodec
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.data.LayoutEntry
import com.urik.keyboard.model.KeyAppearance
import com.urik.keyboard.model.KeyAttributes
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.AdaptiveDimensions
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.GeometryBucket
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.PostureDetector
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.settings.keyboardui.ColorPicker
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.ui.keyboard.components.KeyboardLayoutManager
import com.urik.keyboard.utils.CacheMemoryManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The recursive per-key edit screen (M4 L2 step 8). A full-screen Activity that edits ONE JSON key inside a
 * custom layout, identified by a navigation [path] from the layout root. The top-level path is
 * `[mode, row, col]`; drilling into a compass/cluster/column slot or a `case` shift-state branch appends a
 * token (a flick direction, a case-state name, …) and launches this Activity again on that sub-key — so the
 * recursion is just "open me on a deeper path".
 *
 * Every read/write goes through the lossless [KeyboardJsonCodec]: the target key JSON is `parseKey`d to a
 * [KeyboardKey] so we can derive its editor type and pre-fill fields; on Apply we build a fresh [KeyboardKey]
 * from the UI and `emitKey` it back into the tree, then auto-save (mirroring [KeyboardEditorActivity]).
 * Fields the codec preserves but the editor doesn't surface ride through untouched.
 */
@AndroidEntryPoint
class KeyEditActivity : AppCompatActivity() {
    @Inject lateinit var keyboardRepository: KeyboardRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var themeManager: ThemeManager
    @Inject lateinit var characterVariationService: CharacterVariationService
    @Inject lateinit var languageManager: LanguageManager
    @Inject lateinit var cacheMemoryManager: CacheMemoryManager

    private lateinit var entry: LayoutEntry

    /** The whole layout JSON, loaded fresh; we mutate the slice [path] points at and save the whole thing. */
    private lateinit var working: JSONObject

    /** Navigation path from the layout root to the edited key: [mode, rowIdx, colIdx, then nested tokens…]. */
    private lateinit var path: List<String>

    /** The edited key object itself (a live reference inside [working] once [bind] runs). */
    private lateinit var keyObj: JSONObject

    private val mode: String get() = path[0]

    /** The current editor type (one of [TYPES]); changing the Type spinner rewrites [keyObj] in place. */
    private var currentType: String = "base"

    private lateinit var fieldsContainer: LinearLayout
    private lateinit var previewContainer: LinearLayout
    private lateinit var typeSpinner: Spinner

    // Live collectors registered by the per-section builders; Apply runs them all into [keyObj].
    private val collectors = mutableListOf<() -> Unit>()

    private val layoutManager by lazy {
        KeyboardLayoutManager(
            context = this,
            onKeyClick = {},
            onAcceleratedDeletionChanged = {},
            onSymbolsLongPress = {},
            characterVariationService = characterVariationService,
            languageManager = languageManager,
            themeManager = themeManager,
            cacheMemoryManager = cacheMemoryManager
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        entry = LayoutEntry(
            id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return },
            lang = intent.getStringExtra(EXTRA_LANG) ?: "",
            name = intent.getStringExtra(EXTRA_NAME) ?: "",
            kind = intent.getStringExtra(EXTRA_KIND) ?: "",
            width = intent.getStringExtra(EXTRA_WIDTH) ?: ""
        )
        path = intent.getStringArrayExtra(EXTRA_PATH)?.toList() ?: run { finish(); return }
        val loaded = CustomLayoutStore.rawJson(this, entry.id)
        if (loaded == null || !loaded.has("modes")) { finish(); return }
        working = loaded
        keyObj = resolveKey() ?: run { finish(); return }
        currentType = editorType(keyObj)

        setContentView(buildRoot())
        rebuildFields()
    }

    // ---- navigation -----------------------------------------------------------------------------

    /**
     * Walk [path] from the root to the edited key object, normalizing nested slots/branches into full key
     * objects so anything below the top level is always an addressable [JSONObject]. The first three tokens
     * are mode / row / col; each further token names a flick direction ("up", "downLeft", "center") or a
     * `case` shift-state branch ("normal", "shifted", …). Writes the normalization back so it persists.
     */
    private fun resolveKey(): JSONObject? = try {
        val rows = working.getJSONObject("modes").getJSONObject(path[0]).getJSONArray("rows")
        val row = rows.getJSONArray(path[1].toInt())
        val c = path[2].toInt()
        if (c < 0 || c >= row.length()) {
            null
        } else {
            // Most top-level cells are bare strings ("a") or binding objects, not full key objects — coerce
            // (string|binding|absent → {type:character,char:…}) and write back so the editor can edit them and
            // changes persist. Without this, getJSONObject(c) threw on a string cell and the screen just closed.
            var obj = asKeyObject(row.opt(c))
            row.put(c, obj)
            for (i in 3 until path.size) {
                obj = drillInto(obj, path[i])
            }
            obj
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Resolve the child key object at [token] inside [parent], converting a bare-string slot into a full
     * `{type:character,char:…}` object in place. A flick direction lives under `parent.flick.<dir>`; a case
     * branch lives under `parent.<state>`.
     */
    private fun drillInto(parent: JSONObject, token: String): JSONObject {
        if (token in CASE_STATES) {
            val raw = parent.opt(token)
            val child = asKeyObject(raw)
            parent.put(token, child)
            return child
        }
        // Flick direction (or "center").
        val flick = parent.optJSONObject("flick") ?: JSONObject().also { parent.put("flick", it) }
        val raw = flick.opt(token)
        val child = asKeyObject(raw)
        flick.put(token, child)
        return child
    }

    /** Coerce a slot's raw JSON (string | object | absent) into a full key object the editor can recurse on. */
    private fun asKeyObject(raw: Any?): JSONObject = when (raw) {
        is JSONObject ->
            // A binding object ({action|chord|layer|text}) isn't a key object → wrap its text as a character.
            if (raw.has("type")) raw
            else JSONObject().put("type", "character").put("char", bindingLabel(raw))
        is String -> JSONObject().put("type", "character").put("char", raw)
        else -> JSONObject().put("type", "character").put("char", "")
    }

    private fun bindingLabel(o: JSONObject): String = when {
        o.has("text") -> o.optString("text")
        o.has("label") -> o.optString("label")
        o.has("action") -> o.optString("action")
        o.has("chord") -> o.optString("chord")
        o.has("layer") -> o.optString("layer")
        else -> ""
    }

    private fun isTopLevel(): Boolean = path.size == 3

    // ---- UI scaffold ----------------------------------------------------------------------------

    private fun buildRoot(): View {
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.keyedit_title)
            setNavigationOnClickListener { finish() }
        }
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val breadcrumb = TextView(this).apply {
            text = breadcrumbText()
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), dp(2), dp(14), dp(8))
        }

        typeSpinner = Spinner(this).apply {
            adapter = simpleAdapter(TYPES.map { typeLabel(it) })
            setSelection(TYPES.indexOf(currentType).coerceAtLeast(0))
        }
        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val chosen = TYPES[pos]
                if (chosen != currentType) {
                    convertType(chosen)
                    currentType = chosen
                    rebuildFields()
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        fieldsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val scrollInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(24))
            addView(sectionLabel(getString(R.string.keyedit_type)))
            addView(typeSpinner)
            addView(fieldsContainer)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(scrollInner)
        }

        val applyBtn = pill(getString(R.string.keyedit_apply)) { applyAndFinish() }.apply {
            background = pillBg(filled = true)
            setTextColor(Color.BLACK)
        }
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(14), dp(6), dp(14), dp(6))
            addView(applyBtn)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(toolbar)
            addView(breadcrumb)
            addView(scroll)
            addView(bottomBar)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        return root
    }

    /**
     * Breadcrumb: `base · row N, col M` for a top-level key, then ` › <token>` per drill level. The leading
     * word is the page (literal `base` for the main page); "·" separates page from row/col, "›" the drills.
     */
    private fun breadcrumbText(): String {
        val sb = StringBuilder(
            getString(R.string.keyedit_breadcrumb, modeShort(mode), path[1].toInt() + 1, path[2].toInt() + 1)
        )
        for (i in 3 until path.size) sb.append(" › ").append(path[i])
        return sb.toString()
    }

    // ---- type conversion --------------------------------------------------------------------------

    /**
     * Convert [keyObj] in place to the chosen editor [type], preserving compatible fields by routing through
     * the codec: parse the current object to a [KeyboardKey], remap it to the new shape, and `emitKey`. We
     * keep the centre glyph / band across compatible conversions; appearance + attributes survive untouched.
     */
    private fun convertType(type: String) {
        commitFields() // pull whatever the user has typed so far into keyObj before we rewrite it.
        val current = parseSafely(keyObj)
        val app = current?.appearance
        val attrs = current?.attributes
        val centre = centreGlyphOf(current)
        val rebuilt: JSONObject = when (type) {
            "base" -> JSONObject().put("type", "character").put("char", centre).put("keyType", "letter")
            "gap" -> JSONObject().put("type", "gap")
            "compass" -> KeyboardJsonCodec.emitKey(
                KeyboardKey.FlickKey(centre, null, null, null, null, KeyboardKey.KeyType.LETTER)
            )
            "cluster" -> KeyboardJsonCodec.emitKey(
                KeyboardKey.FlickKey(
                    centre, null, null, null, null, KeyboardKey.KeyType.LETTER,
                    clusterMains = (current as? KeyboardKey.FlickKey)?.clusterMains ?: centre
                )
            )
            "column" -> KeyboardJsonCodec.emitKey(
                KeyboardKey.FlickKey(
                    centre, null, null, null, null, KeyboardKey.KeyType.LETTER,
                    clusterMains = (current as? KeyboardKey.FlickKey)?.clusterMains ?: centre,
                    columnar = true
                )
            )
            "macro" -> JSONObject().put("type", "macro").put("text", centre).put("keyType", "letter")
            "chord" -> JSONObject().put("type", "chord").put("keys", "").put("label", centre)
            "cycle" -> JSONObject().put("type", "cycle")
                .put("taps", JSONArray(listOf(centre).filter { it.isNotEmpty() })).put("label", centre)
            "case" -> JSONObject().put("type", "case").put(
                "normal", JSONObject().put("type", "character").put("char", centre).put("keyType", "letter")
            )
            else -> JSONObject().put("type", "character").put("char", centre).put("keyType", "letter")
        }
        app?.let { rebuilt.put("appearance", emitAppearanceJson(it)) }
        attrs?.let { rebuilt.put("attributes", emitAttributesJson(it)) }
        replaceKeyObj(rebuilt)
    }

    /** Swap the contents of the live [keyObj] (so the parent's reference stays valid) with [next]. */
    private fun replaceKeyObj(next: JSONObject) {
        val keys = keyObj.keys().asSequence().toList()
        for (k in keys) keyObj.remove(k)
        for (k in next.keys()) keyObj.put(k, next.get(k))
    }

    private fun centreGlyphOf(key: KeyboardKey?): String = when (key) {
        is KeyboardKey.Character -> key.value
        is KeyboardKey.FlickKey -> key.center
        else -> keyObj.optString("char").ifEmpty { keyObj.optString("text") }
    }

    private fun parseSafely(o: JSONObject): KeyboardKey? = try {
        KeyboardJsonCodec.parseKey(o, KeyboardKey.ActionType.ENTER)
    } catch (_: Exception) {
        null
    }

    /** Map a JSON key object to one of the editor [TYPES] using the parsed model where possible. */
    private fun editorType(o: JSONObject): String = when (o.optString("type")) {
        "character" -> "base"
        "action" -> "base" // actions edit through the base panel's [Special key…] path.
        "spacer", "gap" -> "gap"
        "case" -> "case"
        "macro" -> "macro"
        "chord" -> "chord"
        "cycle" -> "cycle"
        "column" -> "column"
        "compass", "flick" -> if (o.has("cluster") && !o.optString("cluster").isNullOrEmpty()) "cluster" else "compass"
        "cluster" -> "cluster"
        else -> "base"
    }

    // ---- field panels ---------------------------------------------------------------------------

    private fun rebuildFields() {
        fieldsContainer.removeAllViews()
        collectors.clear()
        appearanceColors.clear()
        appearanceToggles.clear()

        if (isTopLevel()) buildPositionRow()

        when (currentType) {
            "base" -> buildBaseFields()
            "compass" -> buildCompassFields(columnar = false, cluster = false)
            "cluster" -> buildCompassFields(columnar = false, cluster = true)
            "column" -> buildCompassFields(columnar = true, cluster = true)
            "macro" -> buildMacroFields()
            "chord" -> buildChordFields()
            "cycle" -> buildCycleFields()
            "case" -> buildCaseFields()
            "gap" -> {} // no fields
        }

        buildAppearanceSection()
        buildAttributesSection()
        refreshPreview()
    }

    // -- Position in row (top-level only) --
    private fun buildPositionRow() {
        fieldsContainer.addView(sectionLabel(getString(R.string.keyedit_position)))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(vararg btns: View): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            btns.forEach {
                addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4); topMargin = dp(4) })
            }
        }
        grid.addView(row(
            pill(getString(R.string.keyedit_move_left)) { moveKey(-1) },
            pill(getString(R.string.keyedit_move_right)) { moveKey(1) }
        ))
        grid.addView(row(
            pill(getString(R.string.keyedit_move_up)) { moveRow(-1) },
            pill(getString(R.string.keyedit_move_down)) { moveRow(1) }
        ))
        grid.addView(row(
            pill(getString(R.string.keyedit_insert_left)) { insertSibling(0) },
            pill(getString(R.string.keyedit_insert_right)) { insertSibling(1) }
        ))
        grid.addView(row(
            pill(getString(R.string.keyedit_duplicate)) { duplicateKey() },
            pill(getString(R.string.keyedit_delete)) { deleteKey() }.apply { setTextColor(RED); background = pillBg(filled = false, stroke = RED) }
        ))
        fieldsContainer.addView(grid)
    }

    // -- base --
    private fun buildBaseFields() {
        val specInit = keyObj.optString("char").ifEmpty {
            if (keyObj.optString("type") == "action") "!action/" + keyObj.optString("action") else ""
        }
        val specField = labelledField(getString(R.string.keyedit_spec), specInit)
        val pickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill(getString(R.string.keyedit_special_key)) { specialKeyPicker(specField) }
                .apply { (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(6) })
            addView(pill(getString(R.string.keyedit_icon)) { iconPicker(specField) })
        }
        fieldsContainer.addView(pickRow)
        val hintField = labelledField(getString(R.string.keyedit_hint), keyObj.optString("hint"))
        val codeField = labelledField(getString(R.string.keyedit_code_override), keyObj.optString("code"))
        val moreKeys = labelledMultiline(getString(R.string.keyedit_morekeys), keyObj.optString("moreKeys"))

        collectors += {
            val spec = specField.text.toString()
            // A spec beginning with "!action/" re-emits as an action key; otherwise it's a character.
            if (spec.startsWith("!action/")) {
                replaceKeyObj(JSONObject().put("type", "action")
                    .put("action", spec.removePrefix("!action/").ifEmpty { "dynamic_action" }))
            } else {
                keyObj.put("type", "character").remove("action")
                keyObj.put("char", spec)
                if (!keyObj.has("keyType")) keyObj.put("keyType", "letter")
                putOrRemove("hint", hintField.text.toString())
                putOrRemove("code", codeField.text.toString())
                putOrRemove("moreKeys", moreKeys.text.toString())
            }
        }
    }

    /**
     * Special-key picker: a scrollable list of every [KeyboardKey.ActionType] with a friendly label. Picking
     * one sets the spec field to `!action/<ENUM_NAME>`, so the base-key commit path re-emits an action key.
     */
    private fun specialKeyPicker(target: EditText) {
        val labels = SPECIAL_KEYS.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.keyedit_special_key))
            .setItems(labels) { _, which ->
                target.setText("!action/" + SPECIAL_KEYS[which].first.name)
            }
            .setNegativeButton(getString(R.string.keyedit_cancel), null)
            .show()
    }

    /**
     * Icon picker: a grid of the renderable icon glyphs (the converter's ICON_LABEL values). Picking one
     * inserts the glyph itself as the spec — i.e. a plain character key that displays that glyph.
     */
    private fun iconPicker(target: EditText) {
        val grid = android.widget.GridView(this).apply {
            numColumns = 6
            setPadding(dp(12), dp(8), dp(12), dp(8))
            adapter = object : ArrayAdapter<String>(
                this@KeyEditActivity, android.R.layout.simple_list_item_1, ICON_GLYPHS
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    TextView(this@KeyEditActivity).apply {
                        text = ICON_GLYPHS[position]
                        setTextColor(YELLOW)
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                        setPadding(dp(6), dp(10), dp(6), dp(10))
                    }
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.keyedit_icon))
            .setView(grid)
            .setNegativeButton(getString(R.string.keyedit_cancel), null)
            .create()
        grid.setOnItemClickListener { _, _, position, _ ->
            target.setText(ICON_GLYPHS[position])
            dialog.dismiss()
        }
        dialog.show()
    }

    // -- compass / cluster / column --
    private fun buildCompassFields(columnar: Boolean, cluster: Boolean) {
        if (cluster) {
            val bandLabel = if (columnar) R.string.keyedit_main_band_column else R.string.keyedit_main_band_cluster
            val bandField = labelledField(
                getString(bandLabel),
                keyObj.optString("main").ifEmpty { keyObj.optString("cluster") }
            )
            collectors += {
                val band = bandField.text.toString()
                // column stores the band in `main`; cluster/flick in `cluster`.
                if (columnar) { keyObj.put("main", band); keyObj.remove("cluster") }
                else { keyObj.put("cluster", band); keyObj.remove("main") }
                if (keyObj.optString("char").isEmpty() && band.isNotEmpty()) {
                    keyObj.put("char", band[band.length / 2].toString())
                }
            }
        } else {
            // compass: a `slide` compact-string convenience field rides ahead of the per-direction slots.
            val slideField = labelledField(getString(R.string.keyedit_compass_slide), keyObj.optString("slide"))
            collectors += { putOrRemove("slide", slideField.text.toString()) }
        }
        // label (blank = primary) for compass; (blank = main) for cluster/column.
        val labelStr = if (cluster) R.string.keyedit_label_band else R.string.keyedit_label_compass
        val labelField = labelledField(getString(labelStr), keyObj.optString("char"))
        collectors += { putOrRemove("char", labelField.text.toString()) }
        if (cluster) {
            val iconField = labelledField(getString(R.string.keyedit_icon_field), keyObj.optString("icon"))
            collectors += { putOrRemove("icon", iconField.text.toString()) }
        }

        if (!cluster) {
            // primary (tap) row → drills into "center" (compass only; the band centre is the cluster tap).
            fieldsContainer.addView(sectionSub(getString(R.string.keyedit_primary_tap)))
            fieldsContainer.addView(slotRow("center", getString(R.string.keyedit_primary_tap), allowEmptyAdd = false))
        }

        fieldsContainer.addView(sectionLabel(getString(R.string.keyedit_slides)))
        val dirs = when {
            columnar -> COLUMN_DIRS
            cluster -> CLUSTER_DIRS
            else -> COMPASS_DIRS
        }
        for (dir in dirs) fieldsContainer.addView(slotRow(dir, dirLabel(dir)))
    }

    /**
     * A single slide-slot row: dir name + the current-value summary (e.g. `chord: C-e` or `(empty)`) + the
     * [Edit][✕] pills, or `(empty)` + a single `Add key` pill when the slot is empty.
     */
    private fun slotRow(token: String, displayName: String, allowEmptyAdd: Boolean = true): View {
        val flick = keyObj.optJSONObject("flick")
        val raw = flick?.opt(token)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        container.addView(TextView(this).apply {
            text = displayName
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
        })
        val summary = slotSummary(raw)
        container.addView(TextView(this).apply {
            text = summary ?: getString(R.string.keyedit_empty)
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (summary == null && allowEmptyAdd) {
            container.addView(pill(getString(R.string.keyedit_add_key)) { addSlot(token) })
        } else {
            container.addView(pill(getString(R.string.keyedit_edit)) { drillSlot(token) }
                .apply { (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(4) })
            if (summary != null) container.addView(pill("✕") { clearSlot(token) })
        }
        return container
    }

    /** The compact label for a slot's contents: `chord: C-e`, `action: paste`, `layer: alt0`, or the text. */
    private fun slotSummary(raw: Any?): String? = when (raw) {
        null, JSONObject.NULL -> null
        is String -> raw.ifEmpty { null }
        is JSONObject -> when {
            raw.has("chord") -> "chord: " + raw.optString("chord")
            raw.has("action") -> "action: " + raw.optString("action")
            raw.has("layer") -> "layer: " + raw.optString("layer")
            raw.has("text") -> raw.optString("text").ifEmpty { null }
            raw.optString("type") == "character" -> raw.optString("char").ifEmpty { null }
            raw.has("type") -> typeSummary(raw)
            else -> bindingLabel(raw).ifEmpty { null }
        }
        else -> raw.toString()
    }

    /** A nested key-object summary like `cluster: aev` or `compass: a` for case branches / structured slots. */
    private fun typeSummary(o: JSONObject): String {
        val t = editorType(o)
        val detail = when (t) {
            "cluster", "column" -> o.optString("main").ifEmpty { o.optString("cluster") }
                .ifEmpty { o.optString("char") }
            "macro" -> o.optString("text")
            "chord" -> o.optString("keys")
            else -> o.optString("char").ifEmpty { o.optString("label") }
        }
        return if (detail.isEmpty()) t else "$t: $detail"
    }

    private fun addSlot(token: String) {
        commitFields()
        val flick = keyObj.optJSONObject("flick") ?: JSONObject().also { keyObj.put("flick", it) }
        flick.put(token, JSONObject().put("type", "character").put("char", ""))
        saveWorking()
        drillSlot(token)
    }

    private fun drillSlot(token: String) {
        commitFields()
        saveWorking()
        launchChild(path + token)
    }

    private fun clearSlot(token: String) {
        commitFields()
        keyObj.optJSONObject("flick")?.remove(token)
        saveWorking()
        rebuildFields()
    }

    // -- macro / chord / cycle --
    private fun buildMacroFields() {
        val textField = labelledField(getString(R.string.keyedit_macro_text), keyObj.optString("text"))
        val labelField = labelledField(getString(R.string.keyedit_macro_label), keyObj.optString("label"))
        collectors += {
            keyObj.put("text", textField.text.toString())
            putOrRemove("label", labelField.text.toString())
        }
    }

    private fun buildChordFields() {
        val keysField = labelledField(getString(R.string.keyedit_chord_keys), keyObj.optString("keys"))
        val labelField = labelledField(getString(R.string.keyedit_label_chord), keyObj.optString("label"))
        collectors += {
            keyObj.put("keys", keysField.text.toString())
            putOrRemove("label", labelField.text.toString())
        }
    }

    private fun buildCycleFields() {
        val tapsInit = when (val t = keyObj.opt("taps")) {
            is JSONArray -> (0 until t.length()).joinToString("\n") { t.optString(it) }
            is String -> t.map { it.toString() }.joinToString("\n")
            else -> ""
        }
        val tapsField = labelledMultiline(getString(R.string.keyedit_cycle_taps), tapsInit)
        val labelField = labelledField(getString(R.string.keyedit_cycle_label), keyObj.optString("label"))
        collectors += {
            val taps = tapsField.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            keyObj.put("taps", JSONArray(taps))
            putOrRemove("label", labelField.text.toString())
        }
    }

    // -- case --
    private fun buildCaseFields() {
        fieldsContainer.addView(sectionLabel(getString(R.string.keyedit_case_states)))
        for (state in CASE_BRANCHES) {
            fieldsContainer.addView(caseRow(state))
        }
    }

    private fun caseRow(state: String): View {
        val raw = keyObj.opt(state)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        container.addView(TextView(this).apply {
            text = state
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
        })
        val summary = slotSummary(raw)
        container.addView(TextView(this).apply {
            text = summary ?: getString(R.string.keyedit_empty)
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        container.addView(pill(getString(R.string.keyedit_edit)) { drillCase(state) })
        if (summary != null && state != "normal") {
            container.addView(pill("✕") { clearCase(state) }
                .apply { (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(4) })
        }
        return container
    }

    private fun drillCase(state: String) {
        commitFields()
        if (keyObj.opt(state) !is JSONObject) {
            keyObj.put(state, JSONObject().put("type", "character").put("char", ""))
        }
        saveWorking()
        launchChild(path + state)
    }

    private fun clearCase(state: String) {
        commitFields()
        keyObj.remove(state)
        saveWorking()
        rebuildFields()
    }

    // -- appearance (live) --
    private var appearanceCollector: (() -> Unit)? = null

    /** A colour appearance field: its model JSON key → a reader yielding the hex (or null = Inherited). */
    private val appearanceColors = mutableMapOf<String, () -> String?>()

    /** A scalar appearance field: its model JSON key → a reader yielding the override double (or null = theme). */
    private val appearanceToggles = mutableMapOf<String, () -> Double?>()

    private fun buildAppearanceSection() {
        val header = if (currentType == "case") R.string.keyedit_appearance_case else R.string.keyedit_appearance
        fieldsContainer.addView(sectionLabel(getString(header)))
        // The single-key live preview sits between the header and the rows.
        fieldsContainer.addView(previewContainer)
        val app = keyObj.optJSONObject("appearance") ?: JSONObject()

        colorRow(getString(R.string.keyedit_text_color), "color", app)
        toggleRow(getString(R.string.keyedit_font_size), "fontScale", app)
        toggleRow(getString(R.string.keyedit_hint_size), "hintScale", app)
        colorRow(getString(R.string.keyedit_bg_color), "backgroundColor", app)
        colorRow(getString(R.string.keyedit_border_color), "borderColor", app)
        toggleRow(getString(R.string.keyedit_h_offset), "labelOffsetX", app)
        toggleRow(getString(R.string.keyedit_v_offset), "labelOffsetY", app)
        if (currentType == "cluster" || currentType == "column") {
            toggleRow(getString(R.string.keyedit_cluster_left), "clusterLeftOffset", app)
            toggleRow(getString(R.string.keyedit_cluster_right), "clusterRightOffset", app)
        }
        if (currentType == "compass" || currentType == "cluster" || currentType == "column") {
            toggleRow(getString(R.string.keyedit_sec_top), "flickTopOffset", app)
            toggleRow(getString(R.string.keyedit_sec_bottom), "flickBottomOffset", app)
            toggleRow(getString(R.string.keyedit_sec_left), "flickLeftOffset", app)
            toggleRow(getString(R.string.keyedit_sec_right), "flickRightOffset", app)
        }

        appearanceCollector = {
            val o = JSONObject()
            appearanceColors.forEach { (key, reader) -> reader()?.let { o.put(key, it) } }
            appearanceToggles.forEach { (key, reader) -> reader()?.let { o.put(key, it) } }
            if (o.length() > 0) keyObj.put("appearance", o) else keyObj.remove("appearance")
        }
    }

    /**
     * A colour appearance row: a label, then on the right an `Inherited` caption + a tappable colour swatch.
     * Tapping the swatch opens [ColorPicker]; a long-press clears it back to Inherited.
     */
    private fun colorRow(labelText: String, jsonKey: String, app: JSONObject) {
        var current: Int? = parseHex(app.optString(jsonKey, ""))

        val caption = TextView(this).apply {
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(28)).apply { marginStart = dp(10) }
        }
        fun render() {
            caption.text = if (current == null) getString(R.string.keyedit_inherited) else hexOf(current!!)
            swatch.background = swatchBg(current)
        }
        render()
        swatch.setOnClickListener {
            ColorPicker.show(this, current ?: YELLOW) { picked -> current = picked; render(); refreshPreview() }
        }
        // Long-press clears back to Inherited (the screenshots' "clear" affordance).
        swatch.setOnLongClickListener { current = null; render(); refreshPreview(); true }

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(caption)
            addView(swatch)
        }
        fieldsContainer.addView(appearanceRow(labelText, right))
        appearanceColors[jsonKey] = { current?.let { hexOf(it) } }
    }

    /**
     * A scalar appearance row: a `… — theme` label and a Switch on the right. Off = inherit the theme value;
     * on = apply the stored override (default 0.0 when first turned on so the codec records a concrete number).
     */
    private fun toggleRow(labelText: String, jsonKey: String, app: JSONObject) {
        val initial: Double? = if (app.has(jsonKey)) app.optDouble(jsonKey) else null
        var override = initial ?: 0.0
        val sw = Switch(this).apply {
            isChecked = initial != null
            setOnCheckedChangeListener { _, _ -> refreshPreview() }
        }
        fieldsContainer.addView(appearanceRow(labelText, sw))
        appearanceToggles[jsonKey] = { if (sw.isChecked) override else null }
    }

    /** Layout helper: a label filling the left and an arbitrary control pinned to the right. */
    private fun appearanceRow(labelText: String, right: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(this@KeyEditActivity).apply {
            text = labelText
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(right)
    }

    private fun swatchBg(color: Int?): GradientDrawable = GradientDrawable().apply {
        setColor(color ?: Color.BLACK)
        setStroke(dp(2), YELLOW)
        cornerRadius = dp(4).toFloat()
    }

    // -- attributes (blank/inherit = use row/keyboard default) --
    private var attributesCollector: (() -> Unit)? = null

    private fun buildAttributesSection() {
        fieldsContainer.addView(sectionLabel(getString(R.string.keyedit_attributes)))
        val a = keyObj.optJSONObject("attributes") ?: JSONObject()

        val widthSel = optionDropdown(getString(R.string.keyedit_attr_width), WIDTH_OPTIONS,
            a.optString("widthClass").ifEmpty { numStr(a, "width") })
        val styleSel = optionDropdown(getString(R.string.keyedit_attr_style), STYLE_OPTIONS, a.optString("style"))
        val moreModeSel = optionDropdown(getString(R.string.keyedit_attr_morekeymode), MOREKEY_OPTIONS,
            a.optString("moreKeyMode"))
        val heightF = labelledField(getString(R.string.keyedit_attr_heightrows), numStr(a, "heightRows"))

        val tri = TRISTATE_ATTRS.associateWith { triState(it, a) }

        attributesCollector = {
            val o = JSONObject()
            widthSel()?.let { o.put("widthClass", it) }
            styleSel()?.let { o.put("style", it) }
            moreModeSel()?.let { o.put("moreKeyMode", it) }
            putNum(o, "heightRows", heightF)
            tri.forEach { (name, spinner) ->
                when (spinner.selectedItemPosition) {
                    1 -> o.put(name, true)
                    2 -> o.put(name, false)
                    else -> {} // inherit
                }
            }
            if (o.length() > 0) keyObj.put("attributes", o) else keyObj.remove("attributes")
        }
    }

    /**
     * An attribute value dropdown showing `(inherit)` first, then [options]. If [current] is set but not one
     * of the options it's appended so an imported value round-trips. Returns a reader: null when inherit.
     */
    private fun optionDropdown(labelText: String, options: List<String>, current: String): () -> String? {
        fieldsContainer.addView(sectionSub(labelText))
        val items = mutableListOf(getString(R.string.keyedit_inherit_paren))
        items += options
        if (current.isNotEmpty() && current !in options) items += current
        val spinner = Spinner(this).apply {
            adapter = simpleAdapter(items)
            setSelection(if (current.isEmpty()) 0 else items.indexOf(current).coerceAtLeast(0))
            fieldsContainer.addView(this)
        }
        return { if (spinner.selectedItemPosition == 0) null else items[spinner.selectedItemPosition] }
    }

    private fun triState(name: String, a: JSONObject): Spinner {
        fieldsContainer.addView(sectionSub(name))
        val sel = if (!a.has(name)) 0 else if (a.optBoolean(name)) 1 else 2
        return Spinner(this).apply {
            adapter = simpleAdapter(listOf(
                getString(R.string.keyedit_inherit_paren), getString(R.string.keyedit_on), getString(R.string.keyedit_off)
            ))
            setSelection(sel)
            fieldsContainer.addView(this)
        }
    }

    // ---- structural ops (top-level position) ----------------------------------------------------

    private fun rowArr(r: Int): JSONArray =
        working.getJSONObject("modes").getJSONObject(mode).getJSONArray("rows").getJSONArray(r)

    private fun rowsArr(): JSONArray =
        working.getJSONObject("modes").getJSONObject(mode).getJSONArray("rows")

    private fun moveKey(delta: Int) {
        commitFields()
        val r = path[1].toInt(); val c = path[2].toInt()
        val arr = rowArr(r)
        val target = c + delta
        if (target < 0 || target >= arr.length()) return
        val a = arr.getJSONObject(c); val b = arr.getJSONObject(target)
        arr.put(c, b); arr.put(target, a)
        saveWorking()
        relaunchSelf(listOf(mode, r.toString(), target.toString()))
    }

    private fun moveRow(delta: Int) {
        commitFields()
        val r = path[1].toInt(); val c = path[2].toInt()
        val rows = rowsArr()
        val target = r + delta
        if (target < 0 || target >= rows.length()) return
        val a = rows.getJSONArray(r); val b = rows.getJSONArray(target)
        rows.put(r, b); rows.put(target, a)
        saveWorking()
        relaunchSelf(listOf(mode, target.toString(), c.toString()))
    }

    private fun insertSibling(offset: Int) {
        commitFields()
        val r = path[1].toInt(); val c = path[2].toInt()
        val arr = rowArr(r)
        val at = c + offset
        val fresh = JSONObject().put("type", "character").put("char", "x").put("keyType", "letter")
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            if (i == at) out.put(fresh)
            out.put(arr.getJSONObject(i))
        }
        if (at >= arr.length()) out.put(fresh)
        rowsArr().put(r, out)
        saveWorking()
        relaunchSelf(listOf(mode, r.toString(), at.toString()))
    }

    private fun duplicateKey() {
        commitFields()
        val r = path[1].toInt(); val c = path[2].toInt()
        val arr = rowArr(r)
        val copy = JSONObject(keyObj.toString())
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            out.put(arr.getJSONObject(i))
            if (i == c) out.put(copy)
        }
        rowsArr().put(r, out)
        saveWorking()
        relaunchSelf(listOf(mode, r.toString(), (c + 1).toString()))
    }

    private fun deleteKey() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.keyedit_delete))
            .setMessage(getString(R.string.keyedit_delete_confirm))
            .setPositiveButton(R.string.keyedit_delete) { _, _ ->
                rowArr(path[1].toInt()).remove(path[2].toInt())
                saveWorking()
                finish()
            }
            .setNegativeButton(R.string.keyedit_cancel, null)
            .show()
    }

    // ---- commit / persistence / preview ----------------------------------------------------------

    /** Run every registered collector to fold the live UI back into [keyObj] (no disk write). */
    private fun commitFields() {
        collectors.forEach { it() }
        appearanceCollector?.invoke()
        attributesCollector?.invoke()
    }

    private fun saveWorking() {
        CustomLayoutStore.saveLayout(this, entry, working)
        keyboardRepository.invalidateLayoutCache()
    }

    private fun applyAndFinish() {
        commitFields()
        saveWorking()
        finish()
    }

    /** Re-launch this Activity for [newPath] in the same slice — used after a structural move/insert. */
    private fun relaunchSelf(newPath: List<String>) {
        startActivity(intentFor(this, entry, newPath))
        finish()
    }

    private fun launchChild(childPath: List<String>) {
        startActivity(intentFor(this, entry, childPath))
    }

    override fun onResume() {
        super.onResume()
        // A child edit (slot/case drill-in) saved into the same file; reload so our view reflects it.
        val reloaded = CustomLayoutStore.rawJson(this, entry.id) ?: return
        working = reloaded
        val resolved = resolveKey()
        if (resolved == null) { finish(); return }
        keyObj = resolved
        currentType = editorType(keyObj)
        if (::typeSpinner.isInitialized) {
            typeSpinner.setSelection(TYPES.indexOf(currentType).coerceAtLeast(0))
        }
        rebuildFields()
    }

    /** Render the edited key alone through the real renderer (1×1 layout) for a true live preview. */
    private fun refreshPreview() {
        commitFields()
        val parsed = parseSafely(keyObj) ?: KeyboardKey.Character("", KeyboardKey.KeyType.LETTER)
        lifecycleScope.launch {
            val settings = settingsRepository.settings.first()
            val density = resources.displayMetrics.density
            val posture = PostureDetector(this@KeyEditActivity, lifecycleScope).postureInfo.value
            val geometry = settingsRepository.currentGeometry.first()
                ?: GeometryBucket.fromConfiguration(resources.configuration).key
            val knobs = settingsRepository.resolveLookKnobs(entry.lang, entry.id, geometry)
            val baseDims = AdaptiveDimensions.compute(posture, settings.keySize, density)
            val dims = knobs.applyTo(baseDims, density)
            layoutManager.updateKeySize(settings.keySize)
            layoutManager.updateKeyLabelSize(settings.keyLabelSize)
            layoutManager.updateSpaceBarSize(settings.spaceBarSize)
            layoutManager.updateNumberHints(settings.showNumberHints)
            layoutManager.updateAdaptiveDimensions(dims)
            val layout = KeyboardLayout(
                mode = KeyboardMode.LETTERS,
                rows = listOf(listOf(parsed)),
                showFlickHints = parsed is KeyboardKey.FlickKey
            )
            val keyboardView = layoutManager.createKeyboardView(layout, KeyboardState())
            val bg = knobs.keyboardBgColor ?: themeManager.currentTheme.value.colors.keyboardBackground
            keyboardView.setBackgroundColor(bg)
            previewContainer.removeAllViews()
            previewContainer.setBackgroundColor(bg)
            previewContainer.addView(keyboardView)
        }
    }

    // ---- small helpers --------------------------------------------------------------------------

    private fun putOrRemove(key: String, value: String) {
        if (value.isEmpty()) keyObj.remove(key) else keyObj.put(key, value)
    }

    private fun putNum(o: JSONObject, key: String, f: EditText) {
        f.text.toString().trim().toDoubleOrNull()?.let { o.put(key, it) }
    }

    private fun numStr(o: JSONObject, key: String): String =
        if (o.has(key)) trimNum(o.optDouble(key)) else ""

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /** Parse "#RRGGBB"/"#AARRGGBB" to an ARGB int (null on blank/invalid). */
    private fun parseHex(s: String): Int? {
        val hex = s.removePrefix("#").takeIf { it.isNotBlank() } ?: return null
        val v = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> (0xFF000000L or v).toInt()
            8 -> v.toInt()
            else -> null
        }
    }

    private fun hexOf(c: Int): String = "#%08X".format(c.toLong() and 0xFFFFFFFFL)

    // Re-emit the appearance/attributes models to JSON via the codec's public round-trip (parse a stub key
    // that carries them, then read the block back). Keeps colour/number formatting identical to the codec.
    private fun emitAppearanceJson(a: KeyAppearance): JSONObject =
        KeyboardJsonCodec.emitKey(KeyboardKey.Character("", KeyboardKey.KeyType.LETTER, appearance = a))
            .optJSONObject("appearance") ?: JSONObject()

    private fun emitAttributesJson(a: KeyAttributes): JSONObject =
        KeyboardJsonCodec.emitKey(KeyboardKey.Character("", KeyboardKey.KeyType.LETTER, attributes = a))
            .optJSONObject("attributes") ?: JSONObject()

    private fun promptText(title: String, hintText: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply { hint = hintText; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(YELLOW)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun sectionSub(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(YELLOW)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(8), 0, dp(1))
    }

    private fun labelledField(labelText: String, value: String): EditText {
        fieldsContainer.addView(sectionSub(labelText))
        return EditText(this).apply {
            setText(value)
            setTextColor(YELLOW)
            setSingleLine()
            fieldsContainer.addView(this)
        }
    }

    private fun labelledMultiline(labelText: String, value: String): EditText {
        fieldsContainer.addView(sectionSub(labelText))
        return EditText(this).apply {
            setText(value)
            setTextColor(YELLOW)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setHorizontallyScrolling(false)
            maxLines = 6
            fieldsContainer.addView(this)
        }
    }

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun pill(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setTextColor(YELLOW)
        background = pillBg(filled = false)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { onClick() }
    }

    private fun pillBg(filled: Boolean, stroke: Int = YELLOW) =
        GradientDrawable().apply {
            setColor(if (filled) YELLOW else Color.BLACK)
            setStroke(dp(2), stroke)
            cornerRadius = dp(4).toFloat()
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun typeLabel(t: String): String = when (t) {
        "base" -> getString(R.string.keyedit_type_base)
        "compass" -> getString(R.string.keyedit_type_compass)
        "cluster" -> getString(R.string.keyedit_type_cluster)
        "column" -> getString(R.string.keyedit_type_column)
        "macro" -> getString(R.string.keyedit_type_macro)
        "chord" -> getString(R.string.keyedit_type_chord)
        "cycle" -> getString(R.string.keyedit_type_cycle)
        "case" -> getString(R.string.keyedit_type_case)
        "gap" -> getString(R.string.keyedit_type_gap)
        else -> t
    }

    private fun dirLabel(dir: String): String = when (dir) {
        "up" -> getString(R.string.keyedit_dir_up)
        "down" -> getString(R.string.keyedit_dir_down)
        "left" -> getString(R.string.keyedit_dir_left)
        "right" -> getString(R.string.keyedit_dir_right)
        "upLeft" -> getString(R.string.keyedit_dir_up_left)
        "upRight" -> getString(R.string.keyedit_dir_up_right)
        "downLeft" -> getString(R.string.keyedit_dir_down_left)
        "downRight" -> getString(R.string.keyedit_dir_down_right)
        else -> dir
    }

    private fun modeShort(m: String): String = when (m) {
        "letters" -> "base"
        else -> m
    }

    companion object {
        private const val YELLOW = 0xFFFFFF00.toInt()
        private const val RED = 0xFFFF5555.toInt()

        private const val EXTRA_ID = "layout_id"
        private const val EXTRA_LANG = "layout_lang"
        private const val EXTRA_NAME = "layout_name"
        private const val EXTRA_KIND = "layout_kind"
        private const val EXTRA_WIDTH = "layout_width"
        private const val EXTRA_PATH = "key_path"

        private val TYPES =
            listOf("base", "compass", "cluster", "column", "macro", "chord", "cycle", "case", "gap")

        // Compass = all 8 slides. Cluster: band ends ride left/right → only up/down + 4 diagonals editable.
        // Column: band ends ride up/down → only left/right + 4 diagonals editable.
        private val COMPASS_DIRS =
            listOf("up", "down", "left", "right", "upLeft", "upRight", "downLeft", "downRight")
        private val CLUSTER_DIRS = listOf("up", "down", "upLeft", "upRight", "downLeft", "downRight")
        private val COLUMN_DIRS = listOf("left", "right", "upLeft", "upRight", "downLeft", "downRight")

        private val CASE_STATES =
            listOf("normal", "shifted", "shiftedManually", "shiftLocked", "symbols", "symbolsShifted")
        private val CASE_BRANCHES = CASE_STATES // "normal" + the five shift states.

        private val TRISTATE_ATTRS = listOf(
            "showPopup", "longPressEnabled", "repeatableEnabled", "anchored",
            "useKeySpecShortcut", "shiftable", "fastMoreKeys"
        )

        // futokxkb named-width / style / moreKeyMode value sets surfaced as dropdown options.
        private val WIDTH_OPTIONS =
            listOf("Regular", "FunctionalKey", "Grow", "Custom1", "Custom2", "Custom3", "Custom4")
        private val STYLE_OPTIONS = listOf("Action", "Functional", "Spacebar")
        private val MOREKEY_OPTIONS = listOf("OnlyExplicit", "All")

        // Special-key picker: every ActionType paired with a friendly label. Picking sets `!action/<NAME>`.
        private val SPECIAL_KEYS: List<Pair<KeyboardKey.ActionType, String>> = listOf(
            KeyboardKey.ActionType.SHIFT to "Shift",
            KeyboardKey.ActionType.BACKSPACE to "Backspace",
            KeyboardKey.ActionType.SPACE to "Space",
            KeyboardKey.ActionType.ENTER to "Enter",
            KeyboardKey.ActionType.SEARCH to "Search",
            KeyboardKey.ActionType.SEND to "Send",
            KeyboardKey.ActionType.DONE to "Done",
            KeyboardKey.ActionType.GO to "Go",
            KeyboardKey.ActionType.NEXT to "Next",
            KeyboardKey.ActionType.PREVIOUS to "Previous",
            KeyboardKey.ActionType.MODE_SWITCH_LETTERS to "To letters",
            KeyboardKey.ActionType.MODE_SWITCH_NUMBERS to "To numbers",
            KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS to "To symbols",
            KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY to "To symbols₂",
            KeyboardKey.ActionType.CAPS_LOCK to "Caps lock",
            KeyboardKey.ActionType.LANGUAGE_SWITCH to "Language switch",
            KeyboardKey.ActionType.DAKUTEN to "Dakuten",
            KeyboardKey.ActionType.SMALL_KANA to "Small kana",
            KeyboardKey.ActionType.NEXT_CANDIDATE to "Next candidate",
            KeyboardKey.ActionType.COMMIT_CANDIDATE to "Commit candidate",
            KeyboardKey.ActionType.HANDAKUTEN to "Handakuten",
            KeyboardKey.ActionType.EMOJI to "Emoji",
            KeyboardKey.ActionType.TAB to "Tab"
        )

        // Icon picker glyphs — the converter's ICON_LABEL value set (gnu_yaml_to_json.py): renderable text
        // glyphs for space/tab/enter/settings/shift/delete + edit/undo/redo/hide/voice/arrows/cut/copy/paste.
        private val ICON_GLYPHS: List<String> = listOf(
            "␣", "⇥", "⏎", "⚙", "⇧", "⌫",
            "↶", "↷", "⌄", "🎙", "↑", "↓",
            "←", "→", "✂", "⧉", "⎘", "全"
        )

        fun intentFor(context: Context, entry: LayoutEntry, path: List<String>): Intent =
            Intent(context, KeyEditActivity::class.java).apply {
                putExtra(EXTRA_ID, entry.id)
                putExtra(EXTRA_LANG, entry.lang)
                putExtra(EXTRA_NAME, entry.name)
                putExtra(EXTRA_KIND, entry.kind)
                putExtra(EXTRA_WIDTH, entry.width)
                putExtra(EXTRA_PATH, path.toTypedArray())
            }

        /** Convenience for the grid: edit the top-level key at (mode,row,col). */
        fun intent(context: Context, entry: LayoutEntry, mode: String, row: Int, col: Int): Intent =
            intentFor(context, entry, listOf(mode, row.toString(), col.toString()))
    }
}
