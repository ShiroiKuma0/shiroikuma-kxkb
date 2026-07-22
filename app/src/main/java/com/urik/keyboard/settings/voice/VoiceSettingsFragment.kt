package com.urik.keyboard.settings.voice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.urik.keyboard.R
import com.urik.keyboard.service.voice.VoiceModelStore
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.KxkbToast
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Voice input window: guided offline-Whisper setup + options, in the house style.
 *
 * The keyboard deliberately has NO internet permission — so the speech model cannot be fetched by
 * the app itself. The page walks the three steps instead: (1) the browser downloads the model zip,
 * (2) the page imports it from disk (All-Files-Access real path, no SAF) into app-private storage
 * with a progress dialog, (3) the mic permission is granted. Below that, the dictation options.
 *
 * Reached from the top of the kxkb UI page, the space-slide Actions column, and the main settings
 * list; the mic key also opens it when the model or permission is missing.
 */
@AndroidEntryPoint
class VoiceSettingsFragment : Fragment() {
    @Inject lateinit var settingsRepository: SettingsRepository

    private lateinit var root: LinearLayout

    private val yellow = 0xFFFFFF00.toInt()
    private val dim = 0xFFCCCC66.toInt()
    private val red = 0xFFFF6666.toInt()
    private val green = 0xFF99DD66.toInt()

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted &&
                !shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)
            ) {
                // Permanently denied: the system dialog will no longer appear — App info is the
                // only place left to grant it.
                openAppInfo()
            }
            rebuild()
        }

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
        // Returning from the browser / All-Files-Access / App-info screens: re-render the statuses.
        rebuild()
    }

    private fun rebuild() {
        lifecycleScope.launch {
            val settings = settingsRepository.settings.first()
            root.removeAllViews()

            root.addView(heading(getString(R.string.voice_settings_title)))
            root.addView(caption(getString(R.string.voice_settings_intro), topGap = 6))

            root.addView(spacer(12))
            root.addView(statusLine())

            // -- Step 1: the browser downloads the model (the keyboard has no network access). --
            root.addView(spacer(16))
            root.addView(label(getString(R.string.voice_step1_title)))
            root.addView(caption(getString(R.string.voice_step1_text), topGap = 4))
            root.addView(pillButton(getString(R.string.voice_step1_button)) { openModelDownload() })

            // -- Step 2: import the downloaded zip from disk. --
            root.addView(spacer(16))
            root.addView(label(getString(R.string.voice_step2_title)))
            root.addView(caption(getString(R.string.voice_step2_text), topGap = 4))
            if (!hasAllFilesAccess()) {
                root.addView(caption(getString(R.string.voice_need_all_files), topGap = 8, color = red))
                root.addView(pillButton(getString(R.string.voice_grant_all_files)) { requestAllFilesAccess() })
            } else {
                root.addView(pillButton(getString(R.string.voice_step2_button)) { pickModelZip() })
                if (modelInstalled()) {
                    root.addView(pillButton(getString(R.string.voice_remove_model)) { confirmRemoveModel() })
                }
            }

            // -- Step 3: the mic permission. --
            root.addView(spacer(16))
            root.addView(label(getString(R.string.voice_step3_title)))
            root.addView(caption(getString(R.string.voice_step3_text), topGap = 4))
            if (micGranted()) {
                root.addView(caption(getString(R.string.voice_mic_granted), topGap = 8, color = green))
            } else {
                root.addView(pillButton(getString(R.string.voice_step3_button)) {
                    micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                })
            }

            // -- Options. --
            root.addView(spacer(20))
            root.addView(label(getString(R.string.voice_options_title)))

            root.addView(optionCheckbox(
                getString(R.string.voice_opt_autostop),
                getString(R.string.voice_opt_autostop_desc),
                settings.voiceAutoStop
            ) { checked -> lifecycleScope.launch { settingsRepository.updateVoiceAutoStop(checked); rebuild() } })

            if (settings.voiceAutoStop) {
                root.addView(sliderRow(
                    getString(R.string.voice_opt_silence),
                    min = 200,
                    max = 2000,
                    step = 100,
                    current = settings.voiceSilenceMs,
                    format = { getString(R.string.voice_opt_silence_value, it) }
                ) { ms -> lifecycleScope.launch { settingsRepository.updateVoiceSilenceMs(ms) } })

                root.addView(optionCheckbox(
                    getString(R.string.voice_opt_continuous),
                    getString(R.string.voice_opt_continuous_desc),
                    settings.voiceContinuous
                ) { checked -> lifecycleScope.launch { settingsRepository.updateVoiceContinuous(checked); rebuild() } })

                if (settings.voiceContinuous) {
                    root.addView(sliderRow(
                        getString(R.string.voice_opt_session_end),
                        min = 1000,
                        max = 30000,
                        step = 500,
                        current = settings.voiceSessionEndMs,
                        format = {
                            String.format(java.util.Locale.getDefault(), "%.1f s", it / 1000f)
                        }
                    ) { ms -> lifecycleScope.launch { settingsRepository.updateVoiceSessionEndMs(ms) } })

                    root.addView(optionCheckbox(
                        getString(R.string.voice_opt_beeps),
                        getString(R.string.voice_opt_beeps_desc),
                        settings.voiceBeeps
                    ) { checked -> lifecycleScope.launch { settingsRepository.updateVoiceBeeps(checked); rebuild() } })
                }
            }

            root.addView(optionCheckbox(
                getString(R.string.voice_opt_autodetect),
                getString(R.string.voice_opt_autodetect_desc),
                settings.voiceAutoDetect
            ) { checked -> lifecycleScope.launch { settingsRepository.updateVoiceAutoDetect(checked); rebuild() } })

            root.addView(optionCheckbox(
                getString(R.string.voice_opt_translate),
                getString(R.string.voice_opt_translate_desc),
                settings.voiceTranslate
            ) { checked -> lifecycleScope.launch { settingsRepository.updateVoiceTranslate(checked); rebuild() } })

            root.addView(optionCheckbox(
                getString(R.string.voice_opt_alternate),
                getString(R.string.voice_opt_alternate_desc),
                settings.voiceUseAlternate
            ) { checked -> lifecycleScope.launch { settingsRepository.updateVoiceUseAlternate(checked); rebuild() } })
        }
    }

    // ---- status ----------------------------------------------------------------------------------------

    private fun modelInstalled(): Boolean = VoiceModelStore.isInstalled(requireContext())

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun statusLine(): View {
        val model = if (modelInstalled()) {
            val mb = VoiceModelStore.REQUIRED_FILES.sumOf {
                File(VoiceModelStore.modelDir(requireContext()), it).length()
            } / (1024 * 1024)
            getString(R.string.voice_status_model_ok, mb)
        } else {
            getString(R.string.voice_status_model_missing)
        }
        val mic = if (micGranted()) {
            getString(R.string.voice_status_mic_ok)
        } else {
            getString(R.string.voice_status_mic_missing)
        }
        val ok = modelInstalled() && micGranted()
        return caption("$model\n$mic", color = if (ok) green else red)
    }

    // ---- step 1: browser download ----------------------------------------------------------------------

    private fun openModelDownload() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(MODEL_DOWNLOAD_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            flash(getString(R.string.voice_no_browser))
        }
    }

    // ---- step 2: pick + extract ------------------------------------------------------------------------

    private fun pickModelZip() {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        browseForZip(if (downloads.isDirectory) downloads else Environment.getExternalStorageDirectory())
    }

    /** House file browser (All-Files-Access real paths, no SAF): folders to descend, .zip files to pick. */
    private fun browseForZip(dir: File) {
        val subDirs = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val zips = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        dir.parentFile?.let { parent ->
            labels.add(".. (${parent.name.ifBlank { "/" }})")
            actions.add { browseForZip(parent) }
        }
        zips.forEach { zip ->
            labels.add("🗜 ${zip.name} (${zip.length() / (1024 * 1024)} MB)")
            actions.add { confirmImport(zip) }
        }
        subDirs.forEach { sub ->
            labels.add("📁 ${sub.name}")
            actions.add { browseForZip(sub) }
        }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(dir.absolutePath)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    private fun confirmImport(zip: File) {
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_import_confirm_title)
            .setMessage(getString(R.string.voice_import_confirm_text, zip.name))
            .setPositiveButton(R.string.voice_import_go) { _, _ -> runImport(zip) }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    private fun runImport(zip: File) {
        val targetDir = VoiceModelStore.modelDir(requireContext())
        if (targetDir == null) {
            flash(getString(R.string.voice_import_failed, "no storage"))
            return
        }
        val cancelled = AtomicBoolean(false)

        val statusText = TextView(requireContext()).apply {
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = getString(R.string.voice_import_starting)
        }
        val bar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = false
        }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(statusText)
            addView(bar)
        }
        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_import_progress_title)
            .setView(box)
            .setNegativeButton(R.string.export_import_cancel) { _, _ -> cancelled.set(true) }
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    extractModel(zip, targetDir, cancelled) { fileName, done, total ->
                        bar.post {
                            statusText.text = getString(
                                R.string.voice_import_progress,
                                fileName,
                                done / (1024 * 1024),
                                total / (1024 * 1024)
                            )
                            if (total > 0) bar.progress = ((done * 1000) / total).toInt()
                        }
                    }
                }
            }
            dialog.dismiss()
            result.onSuccess { completed ->
                if (completed) {
                    KxkbToast.show(requireContext(), getString(R.string.voice_import_ok), Toast.LENGTH_LONG)
                } else {
                    flash(getString(R.string.voice_import_cancelled))
                }
                rebuild()
            }.onFailure { e ->
                KxkbToast.show(
                    requireContext(),
                    getString(R.string.voice_import_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                )
                rebuild()
            }
        }
    }

    /**
     * Unpack the six model files from the zip into [targetDir]. Entries are matched by base name so a
     * zip with a folder inside works too. Throws when the zip is not the expected model set; returns
     * false when cancelled (partial files are removed either way).
     */
    private fun extractModel(
        zip: File,
        targetDir: File,
        cancelled: AtomicBoolean,
        onProgress: (String, Long, Long) -> Unit
    ): Boolean {
        ZipFile(zip).use { zf ->
            val wanted = VoiceModelStore.REQUIRED_FILES.associateWith { name ->
                zf.entries().asSequence().firstOrNull {
                    !it.isDirectory && it.name.substringAfterLast('/') == name
                } ?: throw IllegalArgumentException(getString(R.string.voice_import_wrong_zip, name))
            }
            val totalBytes = wanted.values.sumOf { it.size.coerceAtLeast(0) }
            var doneBytes = 0L
            if (!targetDir.exists()) targetDir.mkdirs()
            try {
                for ((name, entry) in wanted) {
                    if (cancelled.get()) throw InterruptedException()
                    val target = File(targetDir, name)
                    zf.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var sinceUpdate = 0L
                            while (true) {
                                if (cancelled.get()) throw InterruptedException()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                doneBytes += read
                                sinceUpdate += read
                                if (sinceUpdate >= PROGRESS_STEP_BYTES) {
                                    sinceUpdate = 0
                                    onProgress(name, doneBytes, totalBytes)
                                }
                            }
                        }
                    }
                    onProgress(name, doneBytes, totalBytes)
                }
            } catch (e: InterruptedException) {
                // Cancelled mid-way: never leave a half model behind.
                VoiceModelStore.REQUIRED_FILES.forEach { File(targetDir, it).delete() }
                return false
            } catch (e: Exception) {
                VoiceModelStore.REQUIRED_FILES.forEach { File(targetDir, it).delete() }
                throw e
            }
        }
        return true
    }

    private fun confirmRemoveModel() {
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_remove_model)
            .setMessage(R.string.voice_remove_model_confirm)
            .setPositiveButton(R.string.voice_remove_model_go) { _, _ ->
                VoiceModelStore.REQUIRED_FILES.forEach {
                    File(VoiceModelStore.modelDir(requireContext()), it).delete()
                }
                flash(getString(R.string.voice_remove_model_done))
                rebuild()
            }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    // ---- step 3 / permissions --------------------------------------------------------------------------

    private fun openAppInfo() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null)
                )
            )
        } catch (_: Exception) {
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
                flash(getString(R.string.voice_need_all_files))
            }
        }
    }

    // ---- options ---------------------------------------------------------------------------------------



    // ---- view builders ---------------------------------------------------------------------------------

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
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }

    private fun spacer(height: Int) = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    }

    private fun optionCheckbox(
        title: String,
        description: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, 0)
        addView(CheckBox(requireContext()).apply {
            text = title
            setTextColor(yellow)
            textSize = 15f
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
        addView(caption(description).apply { setPadding(dp(40), 0, 0, 0) })
    }

    /** Title + live value + a yellow slider; persists on finger release, label updates while dragging. */
    private fun sliderRow(
        title: String,
        min: Int,
        max: Int,
        step: Int,
        current: Int,
        format: (Int) -> String,
        onCommit: (Int) -> Unit
    ): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(2))
        val valueView = TextView(requireContext()).apply {
            text = format(current.coerceIn(min, max))
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(2), 0, 0)
        }
        addView(TextView(requireContext()).apply {
            text = title
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        addView(valueView)
        addView(android.widget.SeekBar(requireContext()).apply {
            this.max = (max - min) / step
            progress = (current.coerceIn(min, max) - min) / step
            progressTintList = android.content.res.ColorStateList.valueOf(yellow)
            thumbTintList = android.content.res.ColorStateList.valueOf(yellow)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    valueView.text = format(min + p * step)
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}

                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                    onCommit(min + sb.progress * step)
                }
            })
        })
    }

    private fun valueRow(title: String, value: String, onClick: () -> Unit): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(requireContext()).apply {
                text = title
                setTextColor(yellow)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            addView(TextView(requireContext()).apply {
                text = value
                setTextColor(dim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(2), 0, 0)
            })
        }

    private fun pillButton(text: String, onClick: () -> Unit): Button = Button(requireContext()).apply {
        this.text = text
        setTextColor(yellow)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF000000.toInt())
            setStroke(dp(2), yellow)
            cornerRadius = dp(8).toFloat()
        }
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

    private fun flash(msg: String) = KxkbToast.show(requireContext(), msg)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** The direct-download link: `resolve` (not `blob`) makes the browser save the file straight away. */
        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/DocWolle/whisperOnnx/resolve/main/whisper_small_int8.zip?download=true"

        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024L
    }
}
