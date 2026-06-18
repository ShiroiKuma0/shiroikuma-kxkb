package com.urik.keyboard.settings.library

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.urik.keyboard.R
import com.urik.keyboard.data.CustomLayoutStore
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.data.LayoutEntry
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.AdaptiveDimensions
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.GeometryBucket
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.PostureDetector
import com.urik.keyboard.settings.SettingsRepository
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
 * The visual Keyboard Editor (M4 L2). Edits a custom layout's raw JSON tree directly — losslessly, so
 * fields the editor doesn't surface (script, per-key `shifted`, compass bindings) ride through untouched.
 * The grid maps each cell to `modes.<mode>.rows[r][c]`; tapping it edits that key; structural buttons
 * add/remove keys and rows. Every committed change auto-saves to the [CustomLayoutStore] and refreshes a
 * true live preview rendered with the real keyboard renderer (the same path the Library uses).
 */
@AndroidEntryPoint
class KeyboardEditorActivity : AppCompatActivity() {
    @Inject lateinit var keyboardRepository: KeyboardRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var themeManager: ThemeManager
    @Inject lateinit var characterVariationService: CharacterVariationService
    @Inject lateinit var languageManager: LanguageManager
    @Inject lateinit var cacheMemoryManager: CacheMemoryManager

    private lateinit var entry: LayoutEntry
    private lateinit var working: JSONObject
    private var mode = "letters"

    private lateinit var modeTabs: LinearLayout
    private lateinit var gridContainer: LinearLayout
    private lateinit var previewContainer: LinearLayout

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
        entry = LayoutEntry(
            id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return },
            lang = intent.getStringExtra(EXTRA_LANG) ?: "",
            name = intent.getStringExtra(EXTRA_NAME) ?: "",
            kind = intent.getStringExtra(EXTRA_KIND) ?: "",
            width = intent.getStringExtra(EXTRA_WIDTH) ?: ""
        )
        val loaded = CustomLayoutStore.rawJson(this, entry.id)
        if (loaded == null || !loaded.has("modes")) {
            finish()
            return
        }
        working = loaded
        mode = modesPresent().firstOrNull() ?: "letters"

        setContentView(buildRoot())
        renderModeTabs()
        renderGrid()
        refreshPreview()
    }

    // ---- UI scaffold ----------------------------------------------------------------------------

    private fun buildRoot(): View {
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.editor_title, entry.name)
            setNavigationOnClickListener { finish() }
        }
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        modeTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        gridContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val addRowBtn = pill(getString(R.string.editor_add_row)) { addRow() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        }

        val scrollInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
            addView(gridContainer)
            addView(addRowBtn)
            addView(previewContainer)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(scrollInner)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(toolbar)
            addView(modeTabs)
            addView(scroll)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        return root
    }

    private fun renderModeTabs() {
        modeTabs.removeAllViews()
        for (m in modesPresent()) {
            val selected = m == mode
            modeTabs.addView(
                Button(this).apply {
                    text = modeLabel(m)
                    setTextColor(if (selected) Color.BLACK else YELLOW)
                    background = pillBg(filled = selected)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6) }
                    setOnClickListener {
                        mode = m
                        renderModeTabs()
                        renderGrid()
                        refreshPreview()
                    }
                }
            )
        }
    }

    private fun renderGrid() {
        gridContainer.removeAllViews()
        val rows = rowsArray()
        for (r in 0 until rows.length()) {
            val rowArr = rows.getJSONArray(r)
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = dp(3)
                setPadding(0, p, 0, p)
            }
            rowView.addView(
                Button(this).apply {
                    text = "≡"
                    setTextColor(YELLOW)
                    background = pillBg(filled = false)
                    minWidth = dp(40)
                    setOnClickListener { rowMenu(r) }
                }
            )
            val keysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until rowArr.length()) {
                keysRow.addView(cell(rowArr.getJSONObject(c), r, c))
            }
            keysRow.addView(
                Button(this).apply {
                    text = "+"
                    setTextColor(YELLOW)
                    background = pillBg(filled = false)
                    setOnClickListener { addKey(r) }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(4) }
                }
            )
            rowView.addView(
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(keysRow)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = dp(6) }
                }
            )
            gridContainer.addView(rowView)
        }
    }

    private fun cell(key: JSONObject, r: Int, c: Int): View = TextView(this).apply {
        text = cellLabel(key)
        setTextColor(YELLOW)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        minWidth = dp(44)
        background = pillBg(filled = false)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(4) }
        isClickable = true
        setOnClickListener { editKey(r, c) }
    }

    // ---- key editing ----------------------------------------------------------------------------

    private fun editKey(r: Int, c: Int) {
        val existing = rowsArray().getJSONArray(r).getJSONObject(c)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val typeSpinner = Spinner(this).apply {
            adapter = simpleAdapter(TYPES)
            setSelection(TYPES.indexOf(existing.optString("type", "character")).coerceAtLeast(0))
        }
        container.addView(label(getString(R.string.editor_key_type)))
        container.addView(typeSpinner)

        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(fields)

        var collect: () -> JSONObject = { existing }
        fun rebuildFields() {
            fields.removeAllViews()
            collect = buildFields(fields, TYPES[typeSpinner.selectedItemPosition], existing)
        }
        rebuildFields()
        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = rebuildFields()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.editor_edit_key_title, r + 1, c + 1))
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton(R.string.editor_apply) { _, _ ->
                rowsArray().getJSONArray(r).put(c, collect())
                commit()
            }
            .setNeutralButton(R.string.editor_delete_key) { _, _ ->
                rowsArray().getJSONArray(r).remove(c)
                commit()
            }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    /** Populate [fields] for the chosen key [type]; return a collector that reads them into a JSON key. */
    private fun buildFields(fields: LinearLayout, type: String, existing: JSONObject): () -> JSONObject {
        when (type) {
            "character" -> {
                val charField = field(fields, getString(R.string.editor_char), existing.optString("char"))
                val keyTypeSpinner = spinnerField(fields, getString(R.string.editor_key_subtype), KEY_TYPES,
                    existing.optString("keyType", "letter"))
                val widthField = field(fields, getString(R.string.editor_width), widthText(existing))
                return {
                    JSONObject()
                        .put("type", "character")
                        .put("char", charField.text.toString())
                        .put("keyType", KEY_TYPES[keyTypeSpinner.selectedItemPosition])
                        .also { putWidth(it, widthField) }
                }
            }
            "action" -> {
                val cur = existing.optString("action", "dynamic_action")
                val actionSpinner = spinnerField(fields, getString(R.string.editor_action),
                    ACTIONS.map { it.first }, cur, ACTIONS.map { it.second })
                val widthField = field(fields, getString(R.string.editor_width), widthText(existing))
                return {
                    JSONObject()
                        .put("type", "action")
                        .put("action", ACTIONS[actionSpinner.selectedItemPosition].first)
                        .also { putWidth(it, widthField) }
                }
            }
            "spacer" -> {
                fields.addView(label(getString(R.string.editor_spacer_note)))
                return { JSONObject().put("type", "spacer") }
            }
            else -> { // flick
                val charField = field(fields, getString(R.string.editor_char), existing.optString("char"))
                val clusterField = field(fields, getString(R.string.editor_cluster), existing.optString("cluster"))
                val keyTypeSpinner = spinnerField(fields, getString(R.string.editor_key_subtype),
                    listOf("") + KEY_TYPES, existing.optString("keyType", ""))
                val widthField = field(fields, getString(R.string.editor_width), widthText(existing))
                val flick = existing.optJSONObject("flick") ?: JSONObject()
                val dirFields = DIRECTIONS.associateWith { dir ->
                    field(fields, dir, flickDisplay(flick.opt(dir)))
                }
                return {
                    val o = JSONObject()
                        .put("type", "flick")
                        .put("char", charField.text.toString())
                    if (clusterField.text.isNotBlank()) o.put("cluster", clusterField.text.toString())
                    val kt = (listOf("") + KEY_TYPES)[keyTypeSpinner.selectedItemPosition]
                    if (kt.isNotBlank()) o.put("keyType", kt)
                    // Preserve `shifted` and any other untouched fields verbatim.
                    existing.optJSONObject("shifted")?.let { o.put("shifted", it) }
                    val newFlick = JSONObject()
                    for (dir in DIRECTIONS) {
                        val typed = dirFields.getValue(dir).text.toString()
                        val original = flick.opt(dir)
                        when {
                            typed.isBlank() -> {}
                            // Untouched object binding (action/chord/layer) → keep it verbatim.
                            original is JSONObject && typed == flickDisplay(original) -> newFlick.put(dir, original)
                            else -> newFlick.put(dir, typed)
                        }
                    }
                    if (newFlick.length() > 0) o.put("flick", newFlick)
                    putWidth(o, widthField)
                    o
                }
            }
        }
    }

    // ---- structural ops -------------------------------------------------------------------------

    private fun addKey(r: Int) {
        rowsArray().getJSONArray(r).put(
            JSONObject().put("type", "character").put("char", "x").put("keyType", "letter")
        )
        commit()
    }

    private fun rowMenu(r: Int) {
        val options = arrayOf(
            getString(R.string.editor_add_row_below),
            getString(R.string.editor_move_row_up),
            getString(R.string.editor_move_row_down),
            getString(R.string.editor_delete_row)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.editor_row_n, r + 1))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { setRows(insertAt(rowsArray(), r + 1, JSONArray())); commit() }
                    1 -> if (r > 0) { swapRows(r, r - 1); commit() }
                    2 -> if (r < rowsArray().length() - 1) { swapRows(r, r + 1); commit() }
                    3 -> { rowsArray().remove(r); commit() }
                }
            }
            .show()
    }

    private fun addRow() {
        setRows(insertAt(rowsArray(), rowsArray().length(), JSONArray()))
        commit()
    }

    private fun swapRows(a: Int, b: Int) {
        val rows = rowsArray()
        val tmp = rows.getJSONArray(a)
        rows.put(a, rows.getJSONArray(b))
        rows.put(b, tmp)
    }

    // ---- persistence + preview ------------------------------------------------------------------

    /** Auto-save the working JSON to the custom store, drop stale caches, and re-render grid + preview. */
    private fun commit() {
        CustomLayoutStore.saveLayout(this, entry, working)
        keyboardRepository.invalidateLayoutCache()
        renderModeTabs()
        renderGrid()
        refreshPreview()
    }

    private fun refreshPreview() {
        lifecycleScope.launch {
            val kbMode = when (mode) {
                "numbers" -> KeyboardMode.NUMBERS
                "symbols" -> KeyboardMode.SYMBOLS
                "symbols_secondary" -> KeyboardMode.SYMBOLS_SECONDARY
                else -> KeyboardMode.LETTERS
            }
            val layout = keyboardRepository.loadLayoutById(entry.id, kbMode) ?: return@launch
            val settings = settingsRepository.settings.first()
            val density = resources.displayMetrics.density
            val posture = PostureDetector(this@KeyboardEditorActivity, lifecycleScope).postureInfo.value
            val geometry = settingsRepository.currentGeometry.first()
                ?: GeometryBucket.fromConfiguration(resources.configuration).key
            val knobs = settingsRepository.resolveLookKnobs(entry.lang, entry.id, geometry)
            val baseDims = AdaptiveDimensions.compute(posture, settings.keySize, density)
            val liveHeightScale = settingsRepository.getCurrentKeyHeightScale()
            val dims = knobs.applyTo(baseDims, density).let { d ->
                if (liveHeightScale != null) {
                    d.copy(keyHeightPx = (baseDims.keyHeightPx * liveHeightScale).toInt().coerceAtLeast(1))
                } else {
                    d
                }
            }
            layoutManager.updateKeySize(settings.keySize)
            layoutManager.updateKeyLabelSize(settings.keyLabelSize)
            layoutManager.updateSpaceBarSize(settings.spaceBarSize)
            layoutManager.updateNumberHints(settings.showNumberHints)
            layoutManager.updateAdaptiveDimensions(dims)
            val keyboardView = layoutManager.createKeyboardView(layout, KeyboardState())
            val bg = knobs.keyboardBgColor ?: themeManager.currentTheme.value.colors.keyboardBackground
            keyboardView.setBackgroundColor(bg)
            previewContainer.removeAllViews()
            previewContainer.addView(
                TextView(this@KeyboardEditorActivity).apply {
                    text = getString(R.string.editor_live_preview)
                    setTextColor(YELLOW)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(2), dp(6), 0, dp(4))
                }
            )
            previewContainer.addView(keyboardView)
        }
    }

    // ---- small helpers --------------------------------------------------------------------------

    private fun modesPresent(): List<String> {
        val modes = working.optJSONObject("modes") ?: return listOf("letters")
        return MODE_ORDER.filter { modes.has(it) }.ifEmpty { modes.keys().asSequence().toList() }
    }

    private fun rowsArray(): JSONArray =
        working.getJSONObject("modes").getJSONObject(mode).getJSONArray("rows")

    private fun setRows(rows: JSONArray) {
        working.getJSONObject("modes").getJSONObject(mode).put("rows", rows)
    }

    private fun insertAt(arr: JSONArray, index: Int, value: Any): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            if (i == index) out.put(value)
            out.put(arr.get(i))
        }
        if (index >= arr.length()) out.put(value)
        return out
    }

    private fun cellLabel(key: JSONObject): String = when (key.optString("type")) {
        "character" -> key.optString("char").ifEmpty { "·" }
        "flick" -> {
            val c = key.optString("cluster")
            (if (c.isNotEmpty()) c else key.optString("char").ifEmpty { "·" }) +
                if (key.has("flick")) " ✦" else ""
        }
        "action" -> actionGlyph(key.optString("action"))
        "spacer" -> "▢"
        else -> "?"
    }

    private fun actionGlyph(a: String): String = when (a) {
        "shift" -> "⇧"; "backspace" -> "⌫"; "space" -> "␣"; "caps_lock" -> "⇪"
        "mode_switch_letters" -> "ABC"; "mode_switch_numbers" -> "123"
        "mode_switch_symbols" -> "#+="; "mode_switch_symbols_secondary" -> "{ }"
        "language_switch" -> "🌐"; "emoji" -> "☺"; "tab" -> "⇥"
        "dakuten" -> "゛"; "handakuten" -> "゜"; "small_kana" -> "小"
        "next_candidate" -> "▶"; "commit_candidate" -> "確"
        "dynamic_action", "enter" -> "↵"
        else -> a.ifEmpty { "↵" }
    }

    private fun flickDisplay(raw: Any?): String = when (raw) {
        null, JSONObject.NULL -> ""
        is String -> raw
        is JSONObject -> when {
            raw.has("text") -> raw.optString("text")
            raw.has("label") -> raw.optString("label")
            raw.has("action") -> raw.optString("action")
            raw.has("chord") -> raw.optString("chord")
            raw.has("layer") -> raw.optString("layer")
            else -> ""
        }
        else -> raw.toString()
    }

    private fun widthText(o: JSONObject): String =
        o.optDouble("width", 0.0).let { if (it > 0.0) trimNum(it) else "" }

    private fun putWidth(o: JSONObject, f: EditText) {
        val w = f.text.toString().trim().toDoubleOrNull()
        if (w != null && w > 0.0) o.put("width", w)
    }

    private fun trimNum(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun modeLabel(m: String): String = when (m) {
        "letters" -> getString(R.string.editor_mode_letters)
        "numbers" -> getString(R.string.editor_mode_numbers)
        "symbols" -> getString(R.string.editor_mode_symbols)
        "symbols_secondary" -> getString(R.string.editor_mode_symbols2)
        else -> m
    }

    // Dialog labels/fields use the AlertDialog surface's default colours (legible on light or dark),
    // unlike the black-backed grid/toolbar/preview which are forced black/yellow.
    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun field(parent: LinearLayout, labelText: String, value: String): EditText {
        parent.addView(label(labelText))
        return EditText(this).apply {
            setText(value)
            setSingleLine()
            parent.addView(this)
        }
    }

    private fun spinnerField(
        parent: LinearLayout,
        labelText: String,
        values: List<String>,
        selected: String,
        display: List<String> = values
    ): Spinner {
        parent.addView(label(labelText))
        return Spinner(this).apply {
            adapter = simpleAdapter(display)
            setSelection(values.indexOf(selected).coerceAtLeast(0))
            parent.addView(this)
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

    private fun pillBg(filled: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        setColor(if (filled) YELLOW else Color.BLACK)
        setStroke(dp(2), YELLOW)
        cornerRadius = dp(4).toFloat()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val YELLOW = 0xFFFFFF00.toInt()
        private const val EXTRA_ID = "layout_id"
        private const val EXTRA_LANG = "layout_lang"
        private const val EXTRA_NAME = "layout_name"
        private const val EXTRA_KIND = "layout_kind"
        private const val EXTRA_WIDTH = "layout_width"

        private val MODE_ORDER = listOf("letters", "numbers", "symbols", "symbols_secondary")
        private val TYPES = listOf("character", "action", "flick", "spacer")
        private val KEY_TYPES = listOf("letter", "number", "symbol", "punctuation")
        private val DIRECTIONS =
            listOf("up", "down", "left", "right", "upLeft", "upRight", "downLeft", "downRight")

        /** (json action, display label) in editor order. */
        private val ACTIONS = listOf(
            "dynamic_action" to "Enter (dynamic)",
            "enter" to "Enter (fixed)",
            "shift" to "Shift",
            "caps_lock" to "Caps lock",
            "backspace" to "Backspace",
            "space" to "Space",
            "mode_switch_letters" to "→ Letters",
            "mode_switch_numbers" to "→ Numbers",
            "mode_switch_symbols" to "→ Symbols",
            "mode_switch_symbols_secondary" to "→ Symbols₂",
            "language_switch" to "Language switch",
            "emoji" to "Emoji",
            "tab" to "Tab",
            "dakuten" to "Dakuten",
            "handakuten" to "Handakuten",
            "small_kana" to "Small kana",
            "next_candidate" to "Next candidate",
            "commit_candidate" to "Commit candidate"
        )

        fun intent(context: Context, entry: LayoutEntry): Intent =
            Intent(context, KeyboardEditorActivity::class.java).apply {
                putExtra(EXTRA_ID, entry.id)
                putExtra(EXTRA_LANG, entry.lang)
                putExtra(EXTRA_NAME, entry.name)
                putExtra(EXTRA_KIND, entry.kind)
                putExtra(EXTRA_WIDTH, entry.width)
            }
    }
}
