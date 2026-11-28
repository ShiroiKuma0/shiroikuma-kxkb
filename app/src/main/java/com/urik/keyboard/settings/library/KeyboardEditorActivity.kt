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
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.urik.keyboard.R
import com.urik.keyboard.chrome.ChromeColors
import com.urik.keyboard.chrome.ChromeDialogs
import com.urik.keyboard.chrome.ChromeIcons
import com.urik.keyboard.chrome.KxkbSwitch
import com.urik.keyboard.chrome.Pills
import com.urik.keyboard.chrome.SectionIndex
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
 * delete per row) → an Alt-pages section → the layout-wide options → a Suggestion-bar candidates box; the
 * pending-change bar and the layout's actions (Activate / Commit / Export YAML / Apply as new) stay pinned on top. Every committed change auto-saves to the
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
    private lateinit var layoutContainer: LinearLayout
    private lateinit var topBarField: EditText
    /** Cancel and Activate as one unit, so a line break never parts them. */
    private lateinit var pendingPair: View

    /**
     * The layout as it stood when the editor was opened or Apply was last pressed. Every edit here is
     * auto-saved (the keyboard shows it at once and a killed process loses nothing), so THIS is what Cancel
     * writes back. Kept in a cache file as well: folding the phone recreates the activity, and that must not
     * move the point Cancel returns to.
     */
    private var baseline: String = ""
    private var dirty = false
    private var restoredInstance = false

    /** The stock layout this session copied on opening (null when an existing copy is being edited). */
    private var createdFrom: String? = null

    /** Activate was pressed: the copy stays whatever the comparison with the baseline says. */
    private var activated = false
    private var exiting = false

    /** True while [renderTopBar] fills the candidates box, so its watcher does not read that as typing. */
    private var bindingTopBar = false
    private var topBarPending = false

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
        window.statusBarColor = ChromeColors.background
        window.navigationBarColor = ChromeColors.background
        restoredInstance = savedInstanceState != null
        createdFrom = savedInstanceState?.getString(STATE_CREATED_FROM)
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
            createdFrom = activeId
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
        val kept = if (restoredInstance) runCatching { baselineFile().readText() }.getOrNull() else null
        if (kept != null && runCatching { JSONObject(kept).has("modes") }.getOrDefault(false)) {
            baseline = kept
        } else {
            setBaseline(working)
        }

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        createdFrom?.let { outState.putString(STATE_CREATED_FROM, it) }
    }

    /**
     * Leaving the editor settles which layout the keyboard uses (白い熊, 2026-11-20):
     *  - a stock layout was opened and CHANGED → its copy replaces the stock everywhere: it is the active
     *    layout of its language, and every app that remembered the stock (or another copy of it) now opens it;
     *  - a stock layout was opened and NOT changed → the copy made on opening is removed again and the stock
     *    stays, so merely looking never leaves a copy behind;
     *  - an existing copy that is the active layout was edited → the apps follow it the same way.
     */
    override fun finish() {
        if (exiting || !built) {
            super.finish()
            return
        }
        exiting = true
        lifecycleScope.launch {
            try {
                settleOnExit()
            } catch (_: Exception) {
            }
            super.finish()
        }
    }

    private suspend fun settleOnExit() {
        if (topBarPending) commitTopBar()
        val origin = createdFrom
        val changed = runCatching {
            EditorChrome.comparable(working) != EditorChrome.comparable(JSONObject(baseline))
        }.getOrDefault(true)
        if (origin != null && !changed && !activated) {
            CustomLayoutStore.deleteLayout(this, entry.id)
            settingsRepository.setActiveLayoutForLanguage(entry.lang, origin)
            settingsRepository.rebindPerAppLayouts(entry.lang, setOf(entry.id), origin)
        } else if (settingsRepository.getActiveLayoutForLanguage(entry.lang) == entry.id) {
            val family = withContext(Dispatchers.IO) { CustomLayoutStore.supersededBy(this@KeyboardEditorActivity, entry) }
            settingsRepository.rebindPerAppLayouts(entry.lang, family, entry.id)
            // …and the switcher offers the copy where it offered the stock. A copy made just now always takes
            // the slot; one that existed already only when it is not offered itself (a copy left hidden behind
            // its stock by an older build).
            val stock = origin ?: entry.derivedFrom
            if (stock != null) {
                val all = withContext(Dispatchers.IO) {
                    LayoutRegistry.load(this@KeyboardEditorActivity).forLanguage(entry.lang).map { it.id }.toSet()
                }
                settingsRepository.replaceInSwitcher(entry.lang, stock, entry.id, all, onlyIfHidden = origin == null)
            }
        }
        keyboardRepository.invalidateLayoutCache()
    }

    /** The candidates box saves on focus loss; leaving the screen must not depend on that arriving. */
    override fun onPause() {
        if (built && topBarPending) commitTopBar()
        super.onPause()
    }

    private fun renderAll() {
        renderModeTabs()
        renderRows()
        renderAltPages()
        renderLayoutOptions()
        renderTopBar()
        refreshPreview()
        updateDirty()
    }

    // ---- pending changes (Apply / Cancel) ---------------------------------------------------------

    private fun baselineFile(): File = File(cacheDir, "editor_baseline_${entry.id}.json")

    private fun setBaseline(json: JSONObject) {
        baseline = json.toString()
        runCatching { baselineFile().writeText(baseline) }
    }

    /** Show the Apply/Cancel bar exactly while the layout differs from [baseline]. */
    private fun updateDirty() {
        dirty = runCatching {
            // What a key page applied counts as a change here too; merely having OPENED a key (which stores
            // it in the editor's long form) does not — hence the comparison by meaning, not by spelling.
            EditorChrome.comparable(working) != EditorChrome.comparable(JSONObject(baseline))
        }.getOrDefault(false)
        if (::pendingPair.isInitialized) pendingPair.visibility = if (dirty) View.VISIBLE else View.GONE
    }

    /** Write the layout back as it stood at [baseline] — every change made on this screen since. */
    private fun cancelChanges() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.editor_cancel))
            .setMessage(getString(R.string.editor_cancel_confirm))
            .setPositiveButton(getString(R.string.editor_cancel_discard)) { _, _ ->
                val back = runCatching { JSONObject(baseline) }.getOrNull() ?: return@setPositiveButton
                working = back
                topBarPending = false
                if (mode !in modesPresent()) mode = modesPresent().firstOrNull() ?: "letters"
                commit()
            }
            .setNegativeButton(getString(R.string.editor_cancel_keep), null)
            .show()
            .also { Pills.destructive(it) }
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
        layoutContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topBarField = EditText(this).apply {
            setTextColor(YELLOW)
            setHintTextColor(ChromeColors.note)
            hint = getString(R.string.editor_topbar_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setHorizontallyScrolling(false)
            maxLines = 8
        }

        topBarField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (bindingTopBar) return
                topBarPending = true
                readTopBarIntoWorking()
                updateDirty()
            }
        })
        val scrollInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
            addView(previewContainer)
            addView(captionView(getString(R.string.editor_tap_to_edit)))
            addView(sectionLabel(getString(R.string.editor_section_rows)))
            addView(rowsContainer)
            addView(LinearLayout(this@KeyboardEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
                addView(pill(getString(R.string.editor_add_row)) { showAddDialog(isColumn = false) })
                addView(pill(getString(R.string.editor_add_column)) { showAddDialog(isColumn = true) }
                    .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
            })
            addView(sectionLabel(getString(R.string.editor_section_alt_pages)))
            addView(altPagesContainer)
            addView(sectionLabel(getString(R.string.editor_section_layout)))
            addView(layoutContainer)
            addView(sectionLabel(getString(R.string.editor_section_topbar)))
            addView(topBarField)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(scrollInner)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ChromeColors.background)
            addView(toolbar)
            // Pinned above the scroll, in ONE row: every button together on the left — Cancel first while a
            // change waits, then the layout's actions — and Apply alone at the right end, always the rightmost
            // (白い熊, 2026-11-20). Cancel and Apply are there only while a change is waiting.
            addView(buildActionRow())
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
        // Every button of the page next to the other, from the left, wrapping onto a second line on a narrow
        // screen so none is ever out of sight. Cancel and Activate come last and only while a change waits;
        // Activate is the rightmost of them all (白い熊, 2026-11-20).
        val bar = SectionIndex.Flow(this, dp(6)).apply { setPadding(dp(8), dp(3), dp(8), dp(3)) }
        fun add(textRes: Int, icon: ChromeIcons.Icon, does: ChromeIcons.Does, main: Boolean, opens: Boolean, onClick: () -> Unit) {
            bar.addView(
                Pills.iconPill(this, getString(textRes), icon, ChromeIcons.color(does), main = main, opens = opens, onClick = onClick)
            )
        }
        add(R.string.editor_commit, ChromeIcons.Icon.BRANCH, ChromeIcons.Does.SHARE, main = false, opens = true) { promptCommit() }
        add(R.string.editor_export_yaml, ChromeIcons.Icon.CODE, ChromeIcons.Does.SHARE, main = false, opens = true) { exportYaml() }
        add(R.string.editor_apply_as_new, ChromeIcons.Icon.COPY, ChromeIcons.Does.SHARE, main = false, opens = false) { applyAsNew() }
        // On this page the preferred button is Activate: it accepts the changes, makes the layout the active
        // one for its language and leaves the editor. The two wrap to the next line TOGETHER, Cancel always
        // at Activate's left.
        pendingPair = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(EditorChrome.cancelPill(this@KeyboardEditorActivity, getString(R.string.editor_cancel)) { cancelChanges() })
            addView(
                EditorChrome.applyPill(this@KeyboardEditorActivity, getString(R.string.editor_apply_active)) { applyActive() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(6) }
            )
            visibility = View.GONE
        }
        bar.addView(pendingPair)
        return bar
    }

    private fun renderModeTabs() {
        modeTabs.removeAllViews()
        for (m in modesPresent()) {
            val selected = m == mode
            modeTabs.addView(
                Button(this).apply {
                    text = modeLabel(m)
                    Pills.style(this, main = selected, small = true)
                    background = Pills.background(this@KeyboardEditorActivity, ChromeColors.accent, main = selected, selected = selected)
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
                "numpad" -> KeyboardMode.NUMPAD
                else -> KeyboardMode.LETTERS
            }
            val layout = keyboardRepository.loadLayoutById(entry.id, kbMode) ?: return@launch
            val settings = settingsRepository.settings.first()
            val density = resources.displayMetrics.density
            val posture = PostureDetector(this@KeyboardEditorActivity, lifecycleScope).postureInfo.value
            val geometry = settingsRepository.currentGeometry.first()
                ?: GeometryBucket.fromConfiguration(resources.configuration).key
            val knobs = settingsRepository.resolveLookKnobs(null, entry.id, geometry)
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
        if (topBarPending) commitTopBar()
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
        controls.addView(smallPill(getString(R.string.editor_row_delete), RED) { deleteRow(r) })
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

    // ---- Add / clone row & column ---------------------------------------------------------------

    private fun charKey(ch: String, keyType: String): JSONObject =
        JSONObject().put("type", "character").put("char", ch).put("keyType", keyType)

    private fun actionKey(action: String): JSONObject =
        JSONObject().put("type", "action").put("action", action)

    private fun spacerKey(): JSONObject = JSONObject().put("type", "spacer")

    private fun deepCopyKey(o: JSONObject): JSONObject = JSONObject(o.toString())

    /** Row presets: a label (with a one-line explanation baked in) → the example keys it prefills. */
    private fun rowPresets(): List<Pair<String, () -> List<JSONObject>>> = listOf(
        getString(R.string.editor_preset_letters) to { "qwertyuiop".map { charKey(it.toString(), "letter") } },
        getString(R.string.editor_preset_numbers) to { "1234567890".map { charKey(it.toString(), "number") } },
        getString(R.string.editor_preset_symbols) to
            { listOf(".", ",", "?", "!", "'", ";", ":").map { charKey(it, "symbol") } },
        getString(R.string.editor_preset_function) to {
            listOf(
                actionKey("shift"), actionKey("mode_switch_symbols"), actionKey("space"),
                actionKey("backspace"), actionKey("enter")
            )
        },
        getString(R.string.editor_preset_empty) to { emptyList() }
    )

    /** Column presets: a label → the single key stamped into every row. */
    private fun colPresets(): List<Pair<String, () -> JSONObject>> = listOf(
        getString(R.string.editor_colpreset_char) to { charKey("x", "letter") },
        getString(R.string.editor_colpreset_spacer) to { spacerKey() },
        getString(R.string.editor_colpreset_backspace) to { actionKey("backspace") },
        getString(R.string.editor_colpreset_shift) to { actionKey("shift") }
    )

    /** The widest row's key count — the number of column slots. */
    private fun columnCount(): Int {
        val rows = rowsArray()
        var max = 0
        for (r in 0 until rows.length()) max = maxOf(max, rows.getJSONArray(r).length())
        return max
    }

    /**
     * The unified "Add row / Add column" flow: pick WHAT (a type preset that prefills example keys, or a clone
     * of an existing row·column), then WHERE (start / after any existing row·column / end — so both add and
     * clone get before/after placement). [isColumn] switches the whole dialog between the row and column model.
     */
    private fun showAddDialog(isColumn: Boolean) {
        val count = if (isColumn) columnCount() else rowsArray().length()
        val presetCount = if (isColumn) colPresets().size else rowPresets().size
        val presetLabels = if (isColumn) colPresets().map { it.first } else rowPresets().map { it.first }
        val cloneLabels = (0 until count).map {
            getString(if (isColumn) R.string.editor_clone_col else R.string.editor_clone_row, it + 1)
        }
        val whatItems = (presetLabels + cloneLabels).toTypedArray()

        val rows = whatItems.mapIndexed { i, label ->
            // A preset adds something new (green); a clone copies what is there (orange).
            if (i < presetCount) {
                ChromeDialogs.Row(label, ChromeIcons.Icon.ADD, ChromeIcons.Does.CONNECT, opens = true)
            } else {
                ChromeDialogs.Row(label, ChromeIcons.Icon.COPY, ChromeIcons.Does.SHARE, opens = true)
            }
        }
        ChromeDialogs.actions(
            this, getString(if (isColumn) R.string.editor_add_column else R.string.editor_add_row), rows
        ) { which ->
            showPositionDialog(isColumn, count) { pos ->
                if (isColumn) insertColumn(pos, which) else insertRow(pos, which)
            }
        }
    }

    private fun showPositionDialog(isColumn: Boolean, count: Int, onPick: (Int) -> Unit) {
        val labels = (0..count).map { i ->
            when {
                i == 0 -> getString(R.string.editor_pos_start)
                i >= count -> getString(R.string.editor_pos_end)
                else -> getString(if (isColumn) R.string.editor_pos_after_col else R.string.editor_pos_after_row, i)
            }
        }.toTypedArray()
        ChromeDialogs.choices(
            this, getString(R.string.editor_insert_where), labels.toList(),
            if (isColumn) ChromeIcons.Icon.RIGHT else ChromeIcons.Icon.DOWN
        ) { pos -> onPick(pos) }
    }

    /** [which] < preset count → that preset; otherwise clone the (which − presetCount)-th existing row. */
    private fun insertRow(pos: Int, which: Int) {
        val presets = rowPresets()
        val keys: List<JSONObject> = if (which < presets.size) {
            presets[which].second()
        } else {
            val src = rowsArray().getJSONArray(which - presets.size)
            (0 until src.length()).map { deepCopyKey(src.getJSONObject(it)) }
        }
        val newRow = JSONArray().apply { keys.forEach { put(it) } }
        setRows(insertAt(rowsArray(), pos.coerceIn(0, rowsArray().length()), newRow))
        commit()
        flash(getString(R.string.editor_row_added))
    }

    /** [which] < preset count → stamp that preset key into every row; otherwise clone the chosen column. */
    private fun insertColumn(pos: Int, which: Int) {
        val presets = colPresets()
        val cloneCol = if (which >= presets.size) which - presets.size else -1
        val rows = rowsArray()
        for (r in 0 until rows.length()) {
            val row = rows.getJSONArray(r)
            val key: JSONObject = if (cloneCol >= 0) {
                if (cloneCol < row.length()) deepCopyKey(row.getJSONObject(cloneCol)) else spacerKey()
            } else {
                presets[which].second()
            }
            rows.put(r, insertAt(row, pos.coerceIn(0, row.length()), key))
        }
        commit()
        flash(getString(R.string.editor_col_added))
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
                row.addView(smallPill(getString(R.string.editor_alt_page_remove, modeLabel(m)), RED) { confirmRemoveAltPage(m) })
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
        ChromeDialogs.choices(
            this, getString(R.string.editor_alt_page_add), labels.toList(), ChromeIcons.Icon.LAYERS,
            ChromeIcons.Does.CONNECT
        ) { which -> addAltPage(addable[which]) }
    }

    private fun addAltPage(m: String) {
        val modes = working.getJSONObject("modes")
        if (!modes.has(m)) {
            modes.put(m, JSONObject().put("rows", JSONArray().put(JSONArray())))
        }
        commit()
    }

    /** A whole page of keys goes with one tap, so it asks first. */
    private fun confirmRemoveAltPage(m: String) {
        AlertDialog.Builder(this, R.style.Theme_Urik_Dialog)
            .setTitle(getString(R.string.editor_alt_page_remove, modeLabel(m)))
            .setMessage(getString(R.string.editor_remove_page_confirm, modeLabel(m)))
            .setPositiveButton(getString(R.string.editor_alt_page_remove, modeLabel(m))) { _, _ -> removeAltPage(m) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { Pills.destructive(it) }
    }

    private fun removeAltPage(m: String) {
        working.getJSONObject("modes").remove(m)
        if (mode == m) mode = modesPresent().firstOrNull() ?: "letters"
        commit()
    }

    // ---- Layout-wide options -------------------------------------------------------------------

    /**
     * What belongs to the layout as a whole. Today that is one switch: whether compass and cluster keys show
     * their slide characters at rest (`showFlickHints`, which the keyboard reads when it loads the layout).
     */
    private fun renderLayoutOptions() {
        layoutContainer.removeAllViews()
        val sw = KxkbSwitch(this).apply {
            isChecked = working.optBoolean("showFlickHints", false)
            setOnCheckedChangeListener { _, on ->
                working.put("showFlickHints", on)
                // Posted: commit() rebuilds this very row.
                layoutContainer.post { commit() }
            }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@KeyboardEditorActivity).apply {
                text = getString(R.string.editor_flick_hints)
                setTextColor(YELLOW)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@KeyboardEditorActivity).apply {
                text = getString(R.string.editor_flick_hints_summary)
                setTextColor(ChromeColors.note)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        }
        layoutContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            // The thin dim-gold rule of a row that carries an explanation.
            addView(
                View(this@KeyboardEditorActivity).apply { setBackgroundColor(ChromeColors.note) },
                LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(10) }
            )
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { sw.toggle() }
        })
    }

    // ---- Suggestion-bar candidates --------------------------------------------------------------

    private fun renderTopBar() {
        val arr = working.optJSONArray("topBar")
        val text = if (arr == null) "" else (0 until arr.length()).joinToString("\n") { arr.optString(it) }
        bindingTopBar = true
        topBarField.setText(text)
        bindingTopBar = false
        // Commit on focus loss (a passive bind that doesn't fight typing).
        topBarField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitTopBar() }
    }

    private fun readTopBarIntoWorking() {
        val lines = topBarField.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) working.remove("topBar") else working.put("topBar", JSONArray(lines))
    }

    private fun commitTopBar() {
        readTopBarIntoWorking()
        topBarPending = false
        CustomLayoutStore.saveLayout(this, entry, working)
        keyboardRepository.invalidateLayoutCache()
        updateDirty()
    }

    // ---- Action row ------------------------------------------------------------------------------

    /**
     * Accept everything changed here (it is saved already), make this layout the active one for its language,
     * and leave the editor.
     */
    private fun applyActive() {
        commitTopBar()
        activated = true
        setBaseline(working)
        updateDirty()
        lifecycleScope.launch {
            settingsRepository.setActiveLayoutForLanguage(entry.lang, entry.id)
            // Every app that remembered this board's original, or another copy of it, now opens this one.
            val family = withContext(Dispatchers.IO) { CustomLayoutStore.supersededBy(this@KeyboardEditorActivity, entry) }
            settingsRepository.rebindPerAppLayouts(entry.lang, family, entry.id)
            keyboardRepository.invalidateLayoutCache()
            flash(getString(R.string.editor_applied_toast, entry.name))
            finish()
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
        "mode_switch_numpad" -> "Num"
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
        "numpad" -> getString(R.string.editor_mode_numpad)
        else -> m
    }

    /** A short house-style confirmation flash (black bg, yellow text/frame). */
    private fun flash(message: String) {
        com.urik.keyboard.utils.KxkbToast.show(this, message)
    }

    private fun captionView(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ChromeColors.note)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(2), dp(4), 0, dp(4))
    }

    private fun sectionLabel(text: String): View = EditorChrome.heading(this, text)

    private fun pill(text: String, onClick: () -> Unit): Button =
        Pills.button(this, text, main = false, onClick = onClick)

    /** A compact pill for the dense per-row / per-page control clusters. */
    private fun smallPill(text: String, color: Int = YELLOW, onClick: () -> Unit): Button =
        Pills.button(this, text, main = false, color = color, small = true, onClick = onClick).apply {
            minWidth = dp(34)
            minimumWidth = dp(34)
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(4)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val YELLOW: Int get() = ChromeColors.text
        private val RED: Int get() = ChromeColors.delete

        private const val EXTRA_ID = "layout_id"
        private const val EXTRA_LANG = "layout_lang"
        private const val EXTRA_NAME = "layout_name"
        private const val EXTRA_KIND = "layout_kind"
        private const val EXTRA_WIDTH = "layout_width"
        private const val EXTRA_EDIT_ACTIVE = "edit_active"
        private const val STATE_CREATED_FROM = "created_from"

        private val MODE_ORDER = listOf("letters", "numbers", "symbols", "symbols_secondary", "numpad")

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
