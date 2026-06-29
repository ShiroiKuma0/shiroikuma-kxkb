package com.urik.keyboard.settings.eximport

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
import android.widget.CheckBox
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
 * ticks the parts ([BackupPart]) they want, sets a real backup folder once (All-Files-Access, no SAF — the
 * same model as the Library git path), then Exports (writes a timestamped .zip) or Imports (picks a .zip from
 * the folder). The page scans the folder for the newest backup and shows when it was made.
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

    private val yellow = 0xFFFFFF00.toInt()
    private val dim = 0xFFCCCC66.toInt()
    private val red = 0xFFFF6666.toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val scroll = ScrollView(requireContext()).apply {
            setBackgroundColor(0xFF000000.toInt())
            isFillViewport = true
            clipToPadding = false
        }
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(36))
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

            root.addView(heading(getString(R.string.export_import_title)))
            root.addView(caption(getString(R.string.export_import_intro), topGap = 6))

            if (!hasAllFilesAccess()) {
                root.addView(caption(getString(R.string.export_import_need_access), topGap = 16, color = red))
                root.addView(pillButton(getString(R.string.export_import_grant_access)) { requestAllFilesAccess() })
            }

            root.addView(spacer(16))
            root.addView(dirRow(exportDir))
            root.addView(statusLine())

            root.addView(spacer(14))
            root.addView(selectAllRow())
            BackupPart.entries.forEach { part -> root.addView(partRow(part)) }

            root.addView(spacer(20))
            root.addView(actionRow())
        }
    }

    // ---- rows ------------------------------------------------------------------------------------------

    private fun dirRow(path: String?): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        isClickable = true
        isFocusable = true
        setOnClickListener { editDir(path) }
        addView(label(getString(R.string.export_import_dir_label)))
        addView(
            TextView(requireContext()).apply {
                text = path?.takeIf { it.isNotBlank() } ?: getString(R.string.export_import_dir_unset)
                setTextColor(dim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(2), 0, 0)
            }
        )
    }

    private fun statusLine(): View {
        val (text, warn) = lastBackupStatus()
        return TextView(requireContext()).apply {
            this.text = text
            setTextColor(if (warn) red else dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, 0)
        }
    }

    private fun selectAllRow(): View = CheckBox(requireContext()).apply {
        text = getString(R.string.export_import_select_all)
        setTextColor(yellow)
        textSize = 16f
        isChecked = selectedParts.size == BackupPart.entries.size
        setOnClickListener {
            if (isChecked) selectedParts.addAll(BackupPart.entries) else selectedParts.clear()
            rebuild()
        }
    }

    private fun partRow(part: BackupPart): View = CheckBox(requireContext()).apply {
        text = getString(part.labelRes)
        setTextColor(yellow)
        textSize = 15f
        setPadding(dp(40), 0, 0, 0)
        isChecked = part in selectedParts
        setOnCheckedChangeListener { _, checked ->
            if (checked) selectedParts.add(part) else selectedParts.remove(part)
        }
    }

    private fun actionRow(): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, dp(4), 0, dp(8))
        addView(pillButton(getString(R.string.export_import_import)) { onImport() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; marginEnd = dp(8) }
        })
        addView(pillButton(getString(R.string.export_import_export)) { onExport() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        })
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
        val name = "${BackupManager.EXPORT_PREFIX}${version}_${timestamp()}${BackupManager.EXPORT_SUFFIX}"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, name)
                    file.outputStream().use { backupManager.export(parts, it, version) }
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
        val names = backups.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.export_import_pick_backup)
            .setItems(names) { _, which -> runImport(backups[which]) }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
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

    // ---- backup folder helpers -------------------------------------------------------------------------

    private fun listBackups(): List<File> {
        val dir = exportDir?.let { File(it) } ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
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
                browseForFolder(start) { picked -> saveDir(picked.absolutePath) }
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

    private fun browseForFolder(dir: File, onPick: (File) -> Unit) {
        if (!hasAllFilesAccess()) {
            requestAllFilesAccess(); return
        }
        val subDirs = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val labels = mutableListOf<String>()
        val targets = mutableListOf<File?>()
        labels.add("✓ ${getString(R.string.export_import_pick_here)}"); targets.add(null)
        dir.parentFile?.let { labels.add(".. (${it.name.ifBlank { "/" }})"); targets.add(it) }
        subDirs.forEach { labels.add("📁 ${it.name}"); targets.add(it) }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(dir.absolutePath)
            .setItems(labels.toTypedArray()) { _, which ->
                val t = targets[which]
                if (t == null) onPick(dir) else browseForFolder(t, onPick)
            }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
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

    private fun timestamp(): String =
        DateFormat.format("yyyy-MM-dd_HH-mm-ss", System.currentTimeMillis()).toString()

    private fun appVersionName(): String = try {
        val pm = requireContext().packageManager
        pm.getPackageInfo(requireContext().packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    private fun heading(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextColor(yellow)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
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

    private fun label(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextColor(yellow)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun spacer(height: Int) = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    }

    private fun pillButton(text: String, onClick: () -> Unit): Button = Button(requireContext()).apply {
        this.text = text
        setTextColor(yellow)
        background = makePillBg()
        // Explicit padding + zeroed minimums so the rounded stroke is never clipped at the view edge.
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(20), dp(14), dp(20), dp(14))
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); gravity = Gravity.CENTER }
    }

    private fun makePillBg() = android.graphics.drawable.GradientDrawable().apply {
        setColor(0xFF000000.toInt())
        setStroke(dp(2), yellow)
        cornerRadius = dp(8).toFloat()
    }

    private fun flash(msg: String) = KxkbToast.show(requireContext(), msg)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
