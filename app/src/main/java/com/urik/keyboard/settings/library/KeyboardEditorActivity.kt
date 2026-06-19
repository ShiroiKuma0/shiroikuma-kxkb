package com.urik.keyboard.settings.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.urik.keyboard.R
import com.urik.keyboard.data.CustomLayoutStore
import com.urik.keyboard.data.GitArchive
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.data.KeyboardYamlEmitter
import com.urik.keyboard.data.LayoutEntry
import com.urik.keyboard.data.LayoutRegistry
import com.urik.keyboard.data.LibraryArchive
import com.urik.keyboard.model.KeyboardKey
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
import java.io.File
import java.util.IdentityHashMap
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The main visual Keyboard Editor screen (M4 L2 step 9). Edits a custom layout's raw JSON tree directly —
 * losslessly, so fields the editor doesn't surface (script, per-key `shifted`, compass bindings) ride
 * through untouched. Top→bottom it is: mode tabs → a true live preview (each preview key is tappable →
 * opens the per-key [KeyEditActivity] for that key's (mode,row,col)) → a Rows section (reorder/dup/+key/
 * delete per row) → an Alt-pages section → Custom-key-width sliders → a Suggestion-bar candidates box → an
 * action row (Apply / Export YAML / Apply as new / Revert). Every committed change auto-saves to the
 * [CustomLayoutStore] and refreshes the preview (the same render path the Library uses).
 *
 * Preview-tap → (mode,row,col) mapping is reliable even though the renderer transposes column boards and
 * splits rows (view order ≠ model order): the loaded [com.urik.keyboard.model.KeyboardLayout] keeps the
 * authored rows/cols 1:1, and the renderer tags every key Button with `R.id.key_data` = the very
 * [KeyboardKey] instance from `layout.rows[r][c]`. So we build an identity map KeyboardKey → (row,col) from
 * the loaded layout, walk the rendered view tree, and look each Button's tagged key back up — matching by
 * object identity, never by view order.
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
    private var built = false

    private lateinit var modeTabs: LinearLayout
    private lateinit var previewContainer: LinearLayout
    private lateinit var rowsContainer: LinearLayout
    private lateinit var altPagesContainer: LinearLayout
    private lateinit var widthsContainer: LinearLayout
    private lateinit var topBarField: EditText

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
        if (intent.getBooleanExtra(EXTRA_EDIT_ACTIVE, false)) {
            // "Edit active layout": resolve (and, for a stock layout, duplicate into an editable copy) the
            // currently active layout, then run the normal editor setup on the resolved entry. Settings reads
            // suspend, so this happens in a coroutine; the UI is built inside [setupEditor].
            lifecycleScope.launch { resolveActiveEntryAndSetup(intent.getStringExtra(EXTRA_LANG)) }
            return
        }
        val resolved = LayoutEntry(
            id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return },
            lang = intent.getStringExtra(EXTRA_LANG) ?: "",
            name = intent.getStringExtra(EXTRA_NAME) ?: "",
            kind = intent.getStringExtra(EXTRA_KIND) ?: "",
            width = intent.getStringExtra(EXTRA_WIDTH) ?: ""
        )
        if (!setupEditor(resolved)) finish()
    }

    /**
     * Resolve the active layout for [requestedLang] (or the current/first-active language), duplicating a
     * stock built-in layout into an editable custom copy first (and pointing the active keyboard at the
     * copy), then run the normal editor setup. Mirrors [LibraryFragment.duplicate]'s freshId→rawJson→
     * saveLayout pattern; a layout that's already custom is edited in place.
     */
    private suspend fun resolveActiveEntryAndSetup(requestedLang: String?) {
        val registry = LayoutRegistry.load(this)
        val settings = settingsRepository.settings.first()
        val lang = requestedLang?.takeIf { it.isNotBlank() }
            ?: settingsRepository.getCurrentLayoutLanguage()
            ?: settings.activeLanguages.firstOrNull()
            ?: "en"
        val activeId = settingsRepository.getActiveLayoutForLanguage(lang)
            ?: registry.defaultFor(lang)
            ?: registry.forLanguage(lang).firstOrNull()?.id
        if (activeId == null) {
            finish()
            return
        }
        val baseEntry = registry.entries.find { it.id == activeId }
            ?: LayoutEntry(id = activeId, lang = lang, name = activeId, kind = "", width = "")

        val targetEntry = if (CustomLayoutStore.hasLayout(this, activeId)) {
            // Already an editable custom layout — edit it directly.
            baseEntry
        } else {
            // Stock/built-in (uneditable): duplicate into an editable copy that SHADOWS the stock — same name,
            // derivedFrom = the stock id, so the Library/switcher show only this copy (stock hidden) and
            // deleting it reinstates the stock. Make it the active layout so the on-screen keyboard is editable.
            val raw = CustomLayoutStore.rawJson(this, activeId)
            if (raw == null) {
                finish()
                return
            }
            val newId = CustomLayoutStore.freshId(this, activeId)
            val newEntry = baseEntry.copy(id = newId, derivedFrom = activeId)
            CustomLayoutStore.saveLayout(this, newEntry, raw)
            settingsRepository.setActiveLayoutForLanguage(lang, newId)
            newEntry
        }
        if (!setupEditor(targetEntry)) finish()
    }

    /** Load [target]'s JSON, build the editor UI, and render. Returns false if the layout can't be opened. */
    private fun setupEditor(target: LayoutEntry): Boolean {
        entry = target
        val loaded = CustomLayoutStore.rawJson(this, entry.id)
        if (loaded == null || !loaded.has("modes")) return false
        working = loaded
        mode = modesPresent().firstOrNull() ?: "letters"

        setContentView(buildRoot())
        renderAll()
        built = true
        return true
    }

    /**
     * Coming back from [KeyEditActivity] (which auto-saves into the same custom-store file), reload the
     * working JSON and re-render so edits made over there show up. Skipped on the first resume right after
     * [onCreate] (nothing changed yet, and the file is already loaded).
     */
    override fun onResume() {
        super.onResume()
        if (!built) return
        val reloaded = CustomLayoutStore.rawJson(this, entry.id) ?: return
        working = reloaded
        if (mode !in modesPresent()) mode = modesPresent().firstOrNull() ?: "letters"
        renderAll()
    }

    private fun renderAll() {
        renderModeTabs()
        renderRows()
        renderAltPages()
        renderWidths()
        renderTopBar()
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
        previewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        rowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        altPagesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        widthsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topBarField = EditText(this).apply {
            setTextColor(YELLOW)
            setHintTextColor(0x80FFFF00.toInt())
            hint = getString(R.string.editor_topbar_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setHorizontallyScrolling(false)
            maxLines = 8
        }

        val scrollInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
            addView(previewContainer)
            addView(captionView(getString(R.string.editor_tap_to_edit)))
            addView(sectionLabel(getString(R.string.editor_section_rows)))
            addView(rowsContainer)
            addView(pill(getString(R.string.editor_add_row)) { addRow() }.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
            })
            addView(sectionLabel(getString(R.string.editor_section_alt_pages)))
            addView(altPagesContainer)
            addView(sectionLabel(getString(R.string.editor_section_widths)))
            addView(widthsContainer)
            addView(sectionLabel(getString(R.string.editor_section_topbar)))
            addView(topBarField)
            addView(buildActionRow())
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

    private fun buildActionRow(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(16), 0, 0)
        }
        bar.addView(pillFilled(getString(R.string.editor_apply_active)) { applyActive() })
        bar.addView(pill(getString(R.string.editor_commit)) { promptCommit() }
            .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) })
        bar.addView(pill(getString(R.string.editor_export_yaml)) { exportYaml() }
            .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) })
        bar.addView(pill(getString(R.string.editor_apply_as_new)) { applyAsNew() }
            .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) })
        bar.addView(pill(getString(R.string.editor_revert)) { revert() }
            .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) })
        // Wrap so the action pills can flow/scroll on narrow screens.
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
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
                        renderAll()
                    }
                }
            )
        }
    }

    // ---- live preview (tap → per-key editor) ----------------------------------------------------

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

            // Build the identity map from the loaded layout (authored row/col order). The renderer tags each
            // key Button with that very KeyboardKey instance, so a tap can be resolved to (row,col).
            val coords = IdentityHashMap<KeyboardKey, Pair<Int, Int>>()
            layout.rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, key -> coords[key] = r to c }
            }

            previewContainer.removeAllViews()
            previewContainer.addView(
                TextView(this@KeyboardEditorActivity).apply {
                    text = getString(R.string.editor_live_preview)
                    setTextColor(YELLOW)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(2), dp(2), 0, dp(4))
                }
            )
            // A transparent overlay on top of the preview captures every tap and maps it — by screen position
            // — to the key Button underneath, then to its (row,col). Per-button onClick doesn't work here:
            // flick/compass keys have their own touch handling (input normally goes through the IME's touch
            // dispatcher, which the editor preview lacks), so only plain Buttons (actions) ever fired onClick.
            val frame = FrameLayout(this@KeyboardEditorActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(keyboardView)
                addView(
                    View(this@KeyboardEditorActivity).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setOnTouchListener { _, e ->
                            if (e.actionMasked == MotionEvent.ACTION_UP) {
                                val key = findButtonAt(keyboardView, e.rawX, e.rawY)
                                    ?.getTag(R.id.key_data) as? KeyboardKey
                                key?.let { coords[it] }?.let { editKey(it.first, it.second) }
                            }
                            true
                        }
                    }
                )
            }
            previewContainer.addView(frame)
        }
    }

    /** The deepest key Button whose on-screen bounds contain the (rawX,rawY) tap, searched top child first. */
    private fun findButtonAt(view: View, rawX: Float, rawY: Float): Button? {
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                findButtonAt(view.getChildAt(i), rawX, rawY)?.let { return it }
            }
        }
        if (view is Button) {
            val r = Rect()
            if (view.getGlobalVisibleRect(r) && r.contains(rawX.toInt(), rawY.toInt())) return view
        }
        return null
    }

    /**
     * Tapping a preview key opens the full-screen recursive per-key editor ([KeyEditActivity]) on that
     * (mode, row, col). It auto-saves into the same custom-store file; [onResume] reloads the working JSON
     * and re-renders when we come back.
     */
    private fun editKey(r: Int, c: Int) {
        startActivity(KeyEditActivity.intent(this, entry, mode, r, c))
    }

    // ---- Rows section ---------------------------------------------------------------------------

    private fun renderRows() {
        rowsContainer.removeAllViews()
        val rows = rowsArray()
        for (r in 0 until rows.length()) {
            rowsContainer.addView(rowCard(r, rows.getJSONArray(r)))
        }
    }

    private fun rowCard(r: Int, rowArr: JSONArray): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.editor_row_label, r + 1) + "  " + rowPreviewText(rowArr)
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(smallPill(getString(R.string.editor_row_up)) { if (r > 0) { swapRows(r, r - 1); commit() } })
        controls.addView(smallPill(getString(R.string.editor_row_down)) {
            if (r < rowsArray().length() - 1) { swapRows(r, r + 1); commit() }
        })
        controls.addView(smallPill(getString(R.string.editor_row_dup)) { dupRow(r) })
        controls.addView(smallPill(getString(R.string.editor_row_add_key)) { addKey(r) })
        controls.addView(smallPill(getString(R.string.editor_row_delete)) { deleteRow(r) }
            .apply { setTextColor(RED); background = pillBg(filled = false, stroke = RED) })
        header.addView(controls)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(4)
            setPadding(dp(2), p, dp(2), p)
            addView(header)
        }
    }

    private fun rowPreviewText(rowArr: JSONArray): String {
        val sb = StringBuilder()
        for (c in 0 until rowArr.length()) {
            if (c > 0) sb.append(' ')
            sb.append(cellLabel(rowArr.getJSONObject(c)))
        }
        return sb.toString()
    }

    private fun dupRow(r: Int) {
        val rows = rowsArray()
        val copy = JSONArray(rows.getJSONArray(r).toString())
        setRows(insertAt(rows, r + 1, copy))
        commit()
    }

    private fun deleteRow(r: Int) {
        if (rowsArray().length() <= 1) {
            flash(getString(R.string.editor_delete_last_row))
            return
        }
        rowsArray().remove(r)
        commit()
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

    /** Append a fresh key to row [r], then open the per-key editor on it. */
    private fun addKey(r: Int) {
        val arr = rowsArray().getJSONArray(r)
        arr.put(JSONObject().put("type", "character").put("char", "x").put("keyType", "letter"))
        CustomLayoutStore.saveLayout(this, entry, working)
        keyboardRepository.invalidateLayoutCache()
        editKey(r, arr.length() - 1)
    }

    // ---- Alt pages section ----------------------------------------------------------------------

    private fun renderAltPages() {
        altPagesContainer.removeAllViews()
        val present = modesPresent()
        for (m in present) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(TextView(this).apply {
                text = modeLabel(m)
                setTextColor(YELLOW)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            // "letters" is the base page and always stays; alt pages can be removed.
            if (m != "letters" && present.size > 1) {
                row.addView(smallPill(getString(R.string.editor_alt_page_remove, modeLabel(m))) { removeAltPage(m) }
                    .apply { setTextColor(RED); background = pillBg(filled = false, stroke = RED) })
            }
            altPagesContainer.addView(row)
        }
        // Add an alt page that isn't present yet.
        val addable = MODE_ORDER.filter { it != "letters" && it !in present }
        if (addable.isNotEmpty()) {
            altPagesContainer.addView(pill(getString(R.string.editor_alt_page_add)) { addAltPageMenu(addable) }
                .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) })
        }
        // Append-from-another-layout is a later step (simple add/remove is enough now).
        altPagesContainer.addView(captionView(getString(R.string.editor_alt_page_append_note)))
    }

    private fun addAltPageMenu(addable: List<String>) {
        val labels = addable.map { modeLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.editor_alt_page_add))
            .setItems(labels) { _, which -> addAltPage(addable[which]) }
            .show()
    }

    private fun addAltPage(m: String) {
        val modes = working.getJSONObject("modes")
        if (!modes.has(m)) {
            modes.put(m, JSONObject().put("rows", JSONArray().put(JSONArray())))
        }
        commit()
    }

    private fun removeAltPage(m: String) {
        working.getJSONObject("modes").remove(m)
        if (mode == m) mode = modesPresent().firstOrNull() ?: "letters"
        commit()
    }

    // ---- Custom key widths ----------------------------------------------------------------------

    private fun renderWidths() {
        widthsContainer.removeAllViews()
        val ow = working.optJSONObject("overrideWidths") ?: JSONObject().also { working.put("overrideWidths", it) }
        for (i in 1..4) {
            val key = "Custom$i"
            val frac = if (ow.has(key)) ow.optDouble(key, DEFAULT_WIDTH) else DEFAULT_WIDTH
            widthsContainer.addView(widthSlider(key, frac))
        }
    }

    /** A labelled slider for one Custom width fraction (0.02–1.0); writing commits and re-renders preview. */
    private fun widthSlider(key: String, initial: Double): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val caption = TextView(this).apply {
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        fun captionText(frac: Double) {
            caption.text = "$key  ${"%.2f".format(frac)}"
        }
        captionText(initial)
        val seek = SeekBar(this).apply {
            max = 100
            progress = (initial * 100).toInt().coerceIn(MIN_WIDTH_PCT, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    captionText(p.coerceAtLeast(MIN_WIDTH_PCT) / 100.0)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val frac = sb!!.progress.coerceAtLeast(MIN_WIDTH_PCT) / 100.0
                    val ow = working.optJSONObject("overrideWidths")
                        ?: JSONObject().also { working.put("overrideWidths", it) }
                    ow.put(key, frac)
                    commit()
                }
            })
        }
        container.addView(caption)
        container.addView(seek)
        return container
    }

    // ---- Suggestion-bar candidates --------------------------------------------------------------

    private fun renderTopBar() {
        val arr = working.optJSONArray("topBar")
        val text = if (arr == null) "" else (0 until arr.length()).joinToString("\n") { arr.optString(it) }
        topBarField.setText(text)
        // Commit on focus loss (a passive bind that doesn't fight typing).
        topBarField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitTopBar() }
    }

    private fun commitTopBar() {
        val lines = topBarField.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) working.remove("topBar") else working.put("topBar", JSONArray(lines))
        CustomLayoutStore.saveLayout(this, entry, working)
        keyboardRepository.invalidateLayoutCache()
    }

    // ---- Action row ------------------------------------------------------------------------------

    /** The editor already auto-saves; Apply just (re-)activates this layout for its language and confirms. */
    private fun applyActive() {
        commitTopBar()
        lifecycleScope.launch {
            settingsRepository.setActiveLayoutForLanguage(entry.lang, entry.id)
            flash(getString(R.string.editor_applied_toast, entry.name))
        }
    }

    /**
     * Commit straight from the editor — the same explicit library→archive snapshot as the Library tab, but
     * the message comes PRE-STAMPED with this layout's language + id (e.g. "English · en_column_5r13c: ")
     * with the cursor parked after it, so 白い熊 only appends the concrete change instead of typing the
     * identifier by hand. Requires the archive path (set in the Library) + all-files access.
     */
    private fun promptCommit() {
        commitTopBar()
        lifecycleScope.launch {
            val path = settingsRepository.getLibraryRepoPath()
            val dir = path?.let { File(it) }
            if (dir == null || !Environment.isExternalStorageManager() || !GitArchive.isRepo(dir)) {
                flash(getString(R.string.editor_commit_no_repo))
                return@launch
            }
            val cleanId = entry.derivedFrom ?: entry.id
            val rawLang = Locale(entry.lang).getDisplayLanguage(Locale.ENGLISH)
            // A real language resolves to its English name (en→English); an unknown tag like "gnu" comes
            // back as the code itself — uppercase those so the commit scope reads "GNU", not "gnu".
            val langName = if (rawLang.equals(entry.lang, ignoreCase = true)) {
                entry.lang.uppercase(Locale.ENGLISH)
            } else {
                rawLang
            }
            val prefix = "$langName · $cleanId: "
            val input = EditText(this@KeyboardEditorActivity).apply {
                setText(prefix)
                setSelection(prefix.length)
                hint = getString(R.string.library_git_commit_dialog_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
            }
            val pad = dp(20)
            val box = FrameLayout(this@KeyboardEditorActivity).apply {
                setPadding(pad, dp(8), pad, 0); addView(input)
            }
            AlertDialog.Builder(this@KeyboardEditorActivity, R.style.Theme_Urik_Dialog)
                .setTitle(R.string.library_git_commit_dialog_title)
                .setView(box)
                .setPositiveButton(R.string.library_git_commit) { _, _ ->
                    val message = input.text.toString().trim()
                        .ifBlank { getString(R.string.library_git_commit_default_msg) }
                    doCommit(dir, message)
                }
                .setNegativeButton(R.string.library_git_cancel, null)
                .show()
        }
    }

    private fun doCommit(dir: File, message: String) {
        val ctx = applicationContext
        lifecycleScope.launch {
            val hash = withContext(Dispatchers.IO) { LibraryArchive.mirrorAndCommit(ctx, dir, message) }
            flash(
                if (hash != null) getString(R.string.library_git_committed_toast, hash.take(8))
                else getString(R.string.library_git_nothing_to_commit)
            )
        }
    }

    /**
     * Emit the layout as futokxkb-style YAML ([KeyboardYamlEmitter]) and fire a share chooser on a cache
     * file via the existing FileProvider. A preview dialog shows the YAML with Share + Copy; Share writes
     * `cacheDir/export/<id>.yaml` and sends an `ACTION_SEND`/`ACTION_VIEW` chooser, Copy is the fallback.
     */
    private fun exportYaml() {
        commitTopBar()
        val yaml = try {
            KeyboardYamlEmitter.emit(working)
        } catch (e: Exception) {
            flash(getString(R.string.editor_export_failed, e.message ?: ""))
            return
        }
        val text = TextView(this).apply {
            text = yaml
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.editor_export_yaml))
            .setView(scroll)
            .setPositiveButton(getString(R.string.editor_share)) { _, _ -> shareYaml(yaml) }
            .setNeutralButton(getString(R.string.editor_copy)) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(entry.name, yaml))
                flash(getString(R.string.editor_copied_toast))
            }
            .setNegativeButton(getString(R.string.editor_cancel), null)
            .show()
    }

    /** Write [yaml] to `cacheDir/export/<id>.yaml` and fire a share chooser via the app's FileProvider. */
    private fun shareYaml(yaml: String) {
        try {
            val dir = java.io.File(cacheDir, "export").apply { mkdirs() }
            val safeName = entry.id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val file = java.io.File(dir, "$safeName.yaml")
            file.writeText(yaml)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/yaml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, "$safeName.yaml")
                putExtra(Intent.EXTRA_TEXT, yaml)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.editor_export_yaml)))
        } catch (e: Exception) {
            // Sharing failed (no file access / no chooser target) → fall back to the clipboard.
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(entry.name, yaml))
            flash(getString(R.string.editor_copied_toast))
        }
    }

    /** Save the current working JSON as a brand-new custom layout (mirrors LibraryFragment.duplicate). */
    private fun applyAsNew() {
        commitTopBar()
        val newId = CustomLayoutStore.freshId(this, entry.id)
        // A stand-alone copy (shown separately, not shadowing any stock).
        val newEntry = entry.copy(id = newId, name = "${entry.name} copy", derivedFrom = null)
        val copy = JSONObject(working.toString())
        CustomLayoutStore.saveLayout(this, newEntry, copy)
        flash(getString(R.string.editor_applied_new_toast, newEntry.name))
    }

    /** Discard working edits: reload from the store/asset of THIS id and re-render. */
    private fun revert() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.editor_revert))
            .setMessage(getString(R.string.editor_revert_confirm))
            .setPositiveButton(getString(R.string.editor_revert)) { _, _ ->
                val reloaded = CustomLayoutStore.rawJson(this, entry.id) ?: return@setPositiveButton
                working = reloaded
                if (mode !in modesPresent()) mode = modesPresent().firstOrNull() ?: "letters"
                renderAll()
                flash(getString(R.string.editor_reverted_toast))
            }
            .setNegativeButton(getString(R.string.editor_cancel), null)
            .show()
    }

    // ---- persistence ----------------------------------------------------------------------------

    /** Auto-save the working JSON to the custom store, drop stale caches, and re-render everything. */
    private fun commit() {
        CustomLayoutStore.saveLayout(this, entry, working)
        keyboardRepository.invalidateLayoutCache()
        renderAll()
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
        "flick", "compass", "cluster", "column" -> {
            val c = key.optString("cluster").ifEmpty { key.optString("main") }
            (if (c.isNotEmpty()) c else key.optString("char").ifEmpty { "·" }) +
                if (key.has("flick")) " ✦" else ""
        }
        "action" -> actionGlyph(key.optString("action"))
        "spacer", "gap" -> "▢"
        "macro" -> key.optString("label").ifEmpty { key.optString("text").ifEmpty { "≣" } }
        "case" -> key.optJSONObject("normal")?.optString("char")?.ifEmpty { "⇧" } ?: "⇧"
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

    private fun modeLabel(m: String): String = when (m) {
        "letters" -> getString(R.string.editor_mode_letters)
        "numbers" -> getString(R.string.editor_mode_numbers)
        "symbols" -> getString(R.string.editor_mode_symbols)
        "symbols_secondary" -> getString(R.string.editor_mode_symbols2)
        else -> m
    }

    /** A short confirmation toast (custom-view toasts are deprecated/ignored on modern Android). */
    private fun flash(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun captionView(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xC0FFFF00.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(2), dp(4), 0, dp(4))
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(YELLOW)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun pill(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setTextColor(YELLOW)
        background = pillBg(filled = false)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { onClick() }
    }

    private fun pillFilled(text: String, onClick: () -> Unit): Button = pill(text, onClick).apply {
        setTextColor(Color.BLACK)
        background = pillBg(filled = true)
    }

    /** A compact pill for the dense per-row / per-page control clusters. */
    private fun smallPill(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setTextColor(YELLOW)
        background = pillBg(filled = false)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        minWidth = dp(36)
        minimumWidth = dp(36)
        setPadding(dp(6), dp(2), dp(6), dp(2))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(3) }
        setOnClickListener { onClick() }
    }

    private fun pillBg(filled: Boolean, stroke: Int = YELLOW) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(if (filled) YELLOW else Color.BLACK)
            setStroke(dp(2), stroke)
            cornerRadius = dp(4).toFloat()
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val YELLOW = 0xFFFFFF00.toInt()
        private const val RED = 0xFFFF5555.toInt()
        private const val DEFAULT_WIDTH = 0.1
        private const val MIN_WIDTH_PCT = 2

        private const val EXTRA_ID = "layout_id"
        private const val EXTRA_LANG = "layout_lang"
        private const val EXTRA_NAME = "layout_name"
        private const val EXTRA_KIND = "layout_kind"
        private const val EXTRA_WIDTH = "layout_width"
        private const val EXTRA_EDIT_ACTIVE = "edit_active"

        private val MODE_ORDER = listOf("letters", "numbers", "symbols", "symbols_secondary")

        fun intent(context: Context, entry: LayoutEntry): Intent =
            Intent(context, KeyboardEditorActivity::class.java).apply {
                putExtra(EXTRA_ID, entry.id)
                putExtra(EXTRA_LANG, entry.lang)
                putExtra(EXTRA_NAME, entry.name)
                putExtra(EXTRA_KIND, entry.kind)
                putExtra(EXTRA_WIDTH, entry.width)
            }

        /**
         * Open the editor on the *currently active* layout (for [lang], else the live/first-active language),
         * duplicating a stock layout into an editable custom copy first. Used by the space-slide menu and the
         * Settings "Keyboard editor" item. Resolution happens in [resolveActiveEntryAndSetup] on open.
         */
        fun intentForActiveLayout(context: Context, lang: String?): Intent =
            Intent(context, KeyboardEditorActivity::class.java).apply {
                putExtra(EXTRA_EDIT_ACTIVE, true)
                if (!lang.isNullOrBlank()) putExtra(EXTRA_LANG, lang)
            }
    }
}
