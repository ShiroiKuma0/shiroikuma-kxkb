package com.urik.keyboard.settings.eximport

import com.urik.keyboard.chrome.ChromeDialogs
import com.urik.keyboard.chrome.ChromeIcons
import com.urik.keyboard.chrome.Pills
import com.urik.keyboard.chrome.ChromeColors
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.urik.keyboard.R
import com.urik.keyboard.data.database.DatabaseAvailability
import com.urik.keyboard.service.BackupManager
import com.urik.keyboard.service.BackupPart
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.KxkbToast
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Export / import window: a single house-style page that backs up and restores the keyboard. The user
 * ticks the parts ([BackupPart]) they want, sets a real backup directory once (All-Files-Access, no SAF —
 * the same model as the Library git path), then Exports (writes a timestamped .zip) or Imports (picks a .zip
 * from it). The page scans that directory for the newest backup and shows when it was made.
 *
 * Visual format = the Kōjiki export/import sheet: one bordered rounded box carries the whole page —
 * centred bold title, dim description, a bordered tappable directory box (small label over a bold value,
 * warn-red when unset), the last-backup line, a divider, Select all + the part checkboxes, a divider,
 * then the two equal-width Import | Export buttons.
 *
 * Reached from the space-slide menu (Actions → Export/import) and from a link at the end of the kxkb UI page.
 */
@AndroidEntryPoint
class ExportImportFragment : Fragment() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var backupManager: BackupManager

    private lateinit var root: LinearLayout

    private var exportDir: String? = null
    private val selectedParts: MutableSet<BackupPart> =
        BackupPart.entries.filter { it.defaultSelected }.toMutableSet()

    private val yellow: Int get() = ChromeColors.text
    private val dim: Int get() = ChromeColors.note
    private val red: Int get() = ChromeColors.delete

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val scroll = ScrollView(requireContext()).apply {
            setBackgroundColor(ChromeColors.background)
            isFillViewport = true
            clipToPadding = false
        }
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(24))
            clipToPadding = false
            clipChildren = false
        }
        scroll.addView(root)
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        // Returning from the all-files-access screen: re-render so the grant prompt clears.
        rebuild()
    }

    private fun rebuild() {
        lifecycleScope.launch {
            exportDir = settingsRepository.getExportImportPath()
            root.removeAllViews()

            // The bordered box the whole page lives in.
            val box = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(20))
                clipToPadding = false
                clipChildren = false
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ChromeColors.background)
                    setStroke(dp(2), ChromeColors.accent)
                    cornerRadius = dp(16).toFloat()
                }
            }
            root.addView(
                box,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )

            box.addView(heading(getString(R.string.export_import_title)))
            box.addView(caption(getString(R.string.export_import_intro)).apply {
                alpha = 0.85f
                setPadding(0, 0, 0, dp(10))
            })

            if (!hasAllFilesAccess()) {
                box.addView(caption(getString(R.string.export_import_need_access), color = red))
                box.addView(pillButton(getString(R.string.export_import_grant_access)) { requestAllFilesAccess() })
                box.addView(spacer(6))
            }

            box.addView(dirRow(exportDir))
            box.addView(statusLine())
            if (!DatabaseAvailability.isReal) {
                // The word database is the in-memory stand-in this process: the dictionary parts would export
                // empty and import into thin air, so the engine refuses them — say so here rather than in a
                // failure line after the fact.
                box.addView(caption(getString(R.string.export_import_db_unavailable), color = red))
            }

            box.addView(divider())
            box.addView(selectAllRow())
            BackupPart.entries.forEach { part -> box.addView(partRow(part)) }

            box.addView(divider(topGap = 8))
            box.addView(actionRow())
        }
    }

    // ---- rows ------------------------------------------------------------------------------------------

    /** The directory box: a bordered, clearly-tappable box — small label over the bold value, warn when unset. */
    private fun dirRow(path: String?): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(ChromeColors.background)
            setStroke(dp(2), ChromeColors.accent)
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { editDir(path) }
        addView(
            TextView(requireContext()).apply {
                text = getString(R.string.export_import_dir_label)
                setTextColor(yellow)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
        )
        val set = path?.takeIf { it.isNotBlank() }
        addView(
            TextView(requireContext()).apply {
                text = set ?: getString(R.string.export_import_dir_unset)
                setTextColor(if (set == null) red else dim)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    private fun statusLine(): View {
        val (text, warn) = lastBackupStatus()
        return TextView(requireContext()).apply {
            this.text = text
            setTextColor(if (warn) red else dim)
            alpha = if (warn) 1f else 0.8f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(2), 0, 0, dp(8))
        }
    }

    private fun selectAllRow(): View =
        switchRow(getString(R.string.export_import_select_all), selectedParts.size == BackupPart.entries.size, bold = true) { on ->
            if (on) selectedParts.addAll(BackupPart.entries) else selectedParts.clear()
            // Posted: rebuild() replaces the very row whose switch is still reporting.
            view?.post { rebuild() }
        }

    private fun partRow(part: BackupPart): View =
        switchRow(getString(part.labelRes), part in selectedParts) { on ->
            if (on) selectedParts.add(part) else selectedParts.remove(part)
        }

    private fun divider(topGap: Int = 0): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            .apply { topMargin = dp(topGap) }
        setBackgroundColor(ChromeColors.accent)
        alpha = 0.4f
    }

    /** The ArcaneChat button bar: Cancel alone on the left, Import + Export grouped on the right. */
    private fun actionRow(): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, dp(14), 0, 0)
        addView(pillButton(getString(R.string.export_import_cancel)) {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        })
        addView(View(requireContext()).also {
            it.layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        addView(pillButton(getString(R.string.export_import_import)) { onImport() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
        })
        addView(pillButton(getString(R.string.export_import_export), main = true) { onExport() })
    }

    // ---- export ----------------------------------------------------------------------------------------

    private fun onExport() {
        if (!ensureReady()) return
        val dir = File(exportDir!!)
        if (selectedParts.isEmpty()) {
            flash(getString(R.string.export_import_none_selected)); return
        }
        flash(getString(R.string.export_import_exporting))
        val parts = selectedParts.toSet()
        val version = appVersionName()
        // The family name convention — `shiroikuma-kxkb_<yyyy-MM-dd_HH-mm-ss>.zip`, no version, no suffix:
        // every sister app's backups share one directory, so they must sort and read uniformly.
        val name = BackupManager.exportFileName()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (!dir.exists()) dir.mkdirs()
                    // Same writer as the headless automation path: a `.part` file renamed only once the
                    // archive is complete, so a failure never leaves a short .zip in the backup directory.
                    backupManager.exportToDirectory(parts, dir, version, name)
                }
            }
            result.onSuccess {
                KxkbToast.show(requireContext(), getString(R.string.export_import_export_ok, name), Toast.LENGTH_LONG)
                rebuild()
            }.onFailure {
                KxkbToast.show(
                    requireContext(),
                    getString(R.string.export_import_export_failed, it.message ?: ""),
                    Toast.LENGTH_LONG
                )
            }
        }
    }

    // ---- import ----------------------------------------------------------------------------------------

    private fun onImport() {
        if (!ensureReady()) return
        if (selectedParts.isEmpty()) {
            flash(getString(R.string.export_import_none_selected)); return
        }
        val backups = listBackups()
        if (backups.isEmpty()) {
            flash(getString(R.string.export_import_no_backups)); return
        }
        ChromeDialogs.actions(
            requireContext(), getString(R.string.export_import_pick_backup),
            backups.map { ChromeDialogs.Row(it.name, ChromeIcons.Icon.IMPORT, ChromeIcons.Does.CONNECT, opens = true) },
            cancelText = getString(R.string.export_import_cancel)
        ) { which -> runImport(backups[which]) }
    }

    private fun runImport(file: File) {
        flash(getString(R.string.export_import_importing))
        val parts = selectedParts.toSet()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { file.inputStream().use { backupManager.import(parts, it) } }
            }
            result.onSuccess { showImportResult(it) }
                .onFailure {
                    KxkbToast.show(
                        requireContext(),
                        getString(R.string.export_import_import_failed, it.message ?: ""),
                        Toast.LENGTH_LONG
                    )
                }
        }
    }

    private fun showImportResult(result: com.urik.keyboard.service.BackupResult) {
        val body = buildString {
            result.lines.forEach { appendLine(it) }
            if (result.errors.isNotEmpty()) {
                appendLine()
                appendLine("⚠ " + result.errors.joinToString(", "))
            }
            appendLine()
            append(getString(R.string.export_import_restart_hint))
        }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.export_import_import_done_title)
            .setMessage(body.trim())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---- backup directory helpers -------------------------------------------------------------------------

    /**
     * Our backups in that directory, newest first. Filtered by [BackupManager.EXPORT_PREFIX] because 白い熊 keeps
     * every sister app's backups in one directory — an unfiltered `*.zip` scan would offer their files too.
     */
    private fun listBackups(): List<File> {
        val dir = exportDir?.let { File(it) } ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && BackupManager.isBackupFileName(f.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun lastBackupStatus(): Pair<String, Boolean> {
        if (exportDir.isNullOrBlank()) return getString(R.string.export_import_last_nodir) to true
        val newest = listBackups().firstOrNull() ?: return getString(R.string.export_import_last_none) to true
        val ts = DateFormat.getDateFormat(requireContext()).format(newest.lastModified()) + " " +
            DateFormat.getTimeFormat(requireContext()).format(newest.lastModified())
        return getString(R.string.export_import_last, ts) to false
    }

    private fun ensureReady(): Boolean {
        if (!hasAllFilesAccess()) {
            requestAllFilesAccess(); return false
        }
        if (exportDir.isNullOrBlank()) {
            editDir(null); return false
        }
        return true
    }

    private fun editDir(current: String?) {
        val input = EditText(requireContext()).apply {
            setText(current ?: "")
            hint = getString(R.string.export_import_dir_hint)
            setSingleLine()
        }
        val box = FrameLayout(requireContext()).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.export_import_dir_dialog_title)
            .setMessage(R.string.export_import_dir_dialog_message)
            .setView(box)
            .setPositiveButton(R.string.export_import_save) { _, _ -> saveDir(input.text.toString()) }
            .setNeutralButton(R.string.export_import_browse) { _, _ ->
                val start = current?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }
                    ?: Environment.getExternalStorageDirectory()
                browseForDirectory(start) { picked -> saveDir(picked.absolutePath) }
            }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    private fun saveDir(path: String) {
        lifecycleScope.launch {
            settingsRepository.setExportImportPath(path)
            rebuild()
        }
    }

    private fun browseForDirectory(dir: File, onPick: (File) -> Unit) {
        if (!hasAllFilesAccess()) {
            requestAllFilesAccess(); return
        }
        val subDirs = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val rows = mutableListOf<ChromeDialogs.Row>()
        val targets = mutableListOf<File?>()
        rows.add(
            ChromeDialogs.Row(getString(R.string.export_import_pick_here), ChromeIcons.Icon.CHECK, ChromeIcons.Does.CONNECT)
        )
        targets.add(null)
        dir.parentFile?.let {
            rows.add(ChromeDialogs.Row(".. (${it.name.ifBlank { "/" }})", ChromeIcons.Icon.UP, opens = true))
            targets.add(it)
        }
        subDirs.forEach {
            rows.add(ChromeDialogs.Row(it.name, ChromeIcons.Icon.DIRECTORY, opens = true))
            targets.add(it)
        }
        ChromeDialogs.actions(
            requireContext(), dir.absolutePath, rows,
            cancelText = getString(R.string.export_import_cancel)
        ) { which ->
            val t = targets[which]
            if (t == null) onPick(dir) else browseForDirectory(t, onPick)
        }
    }

    private fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        val pkg = "package:" + requireContext().packageName
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse(pkg)))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                flash(getString(R.string.export_import_need_access))
            }
        }
    }

    // ---- view builders ---------------------------------------------------------------------------------

    private fun appVersionName(): String = try {
        val pm = requireContext().packageManager
        pm.getPackageInfo(requireContext().packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    private fun heading(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextColor(yellow)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun caption(text: String, topGap: Int = 0, color: Int = dim) = TextView(requireContext()).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topGap) }
    }

    /** One part of the backup, on or off: its label, and the outlined switch at the right. The row toggles it. */
    private fun switchRow(labelText: String, checked: Boolean, bold: Boolean = false, onChange: (Boolean) -> Unit): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(4), dp(3))
            val sw = com.urik.keyboard.chrome.KxkbSwitch(requireContext()).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) }
            }
            addView(TextView(requireContext()).apply {
                text = labelText
                setTextColor(yellow)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) })
            setOnClickListener { sw.toggle() }
        }

    private fun spacer(height: Int) = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    }

    /** An outlined round pill; [main] is the one action the page is for (3 dp frame, bold). */
    private fun pillButton(text: String, main: Boolean = false, onClick: () -> Unit): Button =
        Pills.button(requireContext(), text, main = main, onClick = onClick).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); gravity = Gravity.CENTER }
    }

    private fun flash(msg: String) = KxkbToast.show(requireContext(), msg)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
