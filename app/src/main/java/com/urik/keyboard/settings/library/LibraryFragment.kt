package com.urik.keyboard.settings.library

import com.urik.keyboard.chrome.ChromeDialogs
import com.urik.keyboard.chrome.ChromeIcons
import com.urik.keyboard.chrome.Pills
import com.urik.keyboard.chrome.ChromeColors
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
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
import com.urik.keyboard.data.CustomLayoutStore
import com.urik.keyboard.data.GitArchive
import com.urik.keyboard.data.KeyboardRepository
import com.urik.keyboard.data.LayoutEntry
import com.urik.keyboard.data.LayoutRegistry
import com.urik.keyboard.data.LayoutResync
import com.urik.keyboard.data.LibraryArchive
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.AdaptiveDimensions
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.GeometryBucket
import com.urik.keyboard.service.KeyboardFonts
import com.urik.keyboard.service.KeyboardLookKnobs
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.LibraryLook
import com.urik.keyboard.service.PostureDetector
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.KxkbToast
import com.urik.keyboard.ui.keyboard.components.KeyboardLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Library — browse every layout grouped under a per-language heading. Tapping a layout renders it as a
 * true live keyboard (the real renderer + the resolved look knobs) in a panel at the bottom, without
 * switching to it; an "Activate" button there makes it that language's active layout while staying here.
 * The list look (separators, spacing, indent, per-category fonts) is settable on the kxkb UI page.
 */
@AndroidEntryPoint
class LibraryFragment : Fragment() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var keyboardRepository: KeyboardRepository
    @Inject lateinit var themeManager: ThemeManager
    @Inject lateinit var characterVariationService: CharacterVariationService
    @Inject lateinit var languageManager: LanguageManager
    @Inject lateinit var cacheMemoryManager: CacheMemoryManager

    private lateinit var registry: LayoutRegistry
    private lateinit var listContainer: LinearLayout
    private var listScroll: ScrollView? = null
    private lateinit var previewContainer: LinearLayout
    private var rootFrame: android.widget.FrameLayout? = null
    private var look = LibraryLook()

    // The real keyboard renderer, with no-op callbacks (the preview is non-interactive).
    private val layoutManager by lazy {
        KeyboardLayoutManager(
            context = requireContext(),
            onKeyClick = {},
            onAcceleratedDeletionChanged = {},
            onSymbolsLongPress = {},
            characterVariationService = characterVariationService,
            languageManager = languageManager,
            themeManager = themeManager,
            cacheMemoryManager = cacheMemoryManager
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        registry = LayoutRegistry.load(requireContext())
        listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        val scroll = ScrollView(requireContext()).apply {
            listScroll = this
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(
                listContainer,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        previewContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ChromeColors.background)
            addView(scroll)
            addView(previewContainer)
        }
        return FrameLayout(requireContext()).apply {
            addView(
                content,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            rootFrame = this
        }
    }

    /** A toast-style "flash" in 白い熊's look (black box, yellow text, yellow border) — replaces system toasts. */
    private fun flash(message: String) {
        val root = rootFrame ?: return
        val tv = TextView(requireContext()).apply {
            text = message
            setTextColor(ChromeColors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ChromeColors.background)
                setStroke(dp(2), ChromeColors.accent)
                cornerRadius = dp(8).toFloat()
            }
            alpha = 0f
        }
        root.addView(
            tv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(24) }
        )
        tv.animate().alpha(1f).setDuration(150).withEndAction {
            tv.postDelayed({
                tv.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(tv) }
            }, 1400)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        lifecycleScope.launch {
            settingsRepository.ensureLayoutDefaultsMigration()
            registry = LayoutRegistry.load(requireContext())
            look = settingsRepository.getLibraryLook()
            val langs = registry.entries.map { it.lang }.distinct()
            val active = langs.associateWith { lang ->
                settingsRepository.getActiveLayoutForLanguage(lang) ?: registry.defaultFor(lang)
            }
            val customIds = CustomLayoutStore.customEntries(requireContext()).map { it.id }.toSet()
            val visibleByLang = settingsRepository.getVisibleLayoutsByLanguage()
            // The list is rebuilt from scratch, so the page would jump back to the top: a layout pass
            // over a momentarily shorter list clamps the scroll offset. Everything suspending is read
            // BEFORE the rows are torn down, the list is held at its present height until the (async)
            // git section has been built too, and the offset is put back — a toggle leaves the page
            // exactly where it was.
            val keepScrollY = listScroll?.scrollY ?: 0
            listContainer.minimumHeight = listContainer.height
            listContainer.removeAllViews()
            for (lang in langs) {
                listContainer.addView(heading(langDisplay(lang)))
                val entries = registry.forLanguage(lang)
                entries.forEachIndexed { i, entry ->
                    // Absent set = all layouts visible in the switcher (the out-of-the-box default).
                    val inSwitcher = visibleByLang[lang]?.contains(entry.id) ?: true
                    listContainer.addView(
                        layoutRow(entry, entry.id == active[lang], entry.id in customIds, inSwitcher)
                    )
                    if (i < entries.lastIndex) listContainer.addView(separator())
                }
            }
            // A new language with its standard keyboard (built as a custom layout, fully editable).
            if (addableLanguages().isNotEmpty()) {
                listContainer.addView(pillRow(pillButton(getString(R.string.library_add_language), main = true, icon = ChromeIcons.Icon.ADD, does = ChromeIcons.Does.CONNECT) { showAddLanguageDialog() }))
            }
            // The git archive section sits at the END of the page (folded by default) — all its
            // (blocking) git work runs lazily off the main thread, only when this section builds,
            // never on the keyboard hot path or boot.
            val gitSection = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            listContainer.addView(gitSection)
            try {
                buildGitSection(gitSection).join()
            } finally {
                listContainer.minimumHeight = 0
            }
            listScroll?.let { sv -> if (sv.scrollY != keepScrollY) sv.post { sv.scrollTo(0, keepScrollY) } }
        }
    }

    /**
     * Build the Library's "Git archive" section into [container]. Path unset → only the heading + a path
     * row (so the Library degrades to internal-store-only, exactly as before). All git/filesystem calls go
     * through [Dispatchers.IO]; failures (missing dir, not-a-repo, no permission) degrade gracefully and
     * never crash.
     */
    private fun buildGitSection(container: LinearLayout): kotlinx.coroutines.Job {
        return lifecycleScope.launch {
            container.removeAllViews()
            // Foldable + persistent: tapping the heading collapses/expands the section, and the state survives
            // exit/re-open (stored in settings). Collapsed → only the heading shows.
            val folded = settingsRepository.getLibraryGitFolded()
            val arrow = if (folded) "▸ " else "▾ "
            container.addView(
                heading(arrow + getString(R.string.library_git_heading)).apply {
                    isClickable = true
                    setOnClickListener {
                        lifecycleScope.launch {
                            settingsRepository.setLibraryGitFolded(!folded)
                            buildGitSection(container)
                        }
                    }
                }
            )
            if (folded) return@launch

            val path = settingsRepository.getLibraryRepoPath()
            container.addView(gitPathRow(path))

            // The HTTPS remote row is always shown (independent of the path), so the user can set it up first.
            val remoteUrl = settingsRepository.getLibraryRepoRemote()
            container.addView(gitRemoteRow(remoteUrl))

            if (path.isNullOrBlank()) return@launch

            // All-files access is required to touch a real external directory.
            if (!hasAllFilesAccess()) {
                container.addView(gitCaption(getString(R.string.library_git_need_access)))
                container.addView(pillRow(pillButton(getString(R.string.library_git_grant_access), main = true, icon = ChromeIcons.Icon.PRIVACY, does = ChromeIcons.Does.VIEW) {
                    requestAllFilesAccess()
                }))
                return@launch
            }

            val dir = File(path)
            // Probe the repo state off the main thread.
            val state = withContext(Dispatchers.IO) {
                when {
                    !dir.exists() -> GitState.Missing
                    !GitArchive.isRepo(dir) -> GitState.NotRepo
                    else -> GitState.Repo(
                        GitArchive.listLayoutFiles(dir).map { it to it.relativeTo(dir).path },
                        GitArchive.pendingChanges(dir).size
                    )
                }
            }

            when (state) {
                GitState.Missing, GitState.NotRepo -> {
                    val caption = if (state is GitState.Missing) R.string.library_git_dir_missing
                        else R.string.library_git_not_a_repo
                    container.addView(gitCaption(getString(caption)))
                    val pills = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
                    pills.setPadding(0, dp(3), 0, dp(3))
                    pills.addView(pillButton(getString(R.string.library_git_init), main = true, icon = ChromeIcons.Icon.ADD, does = ChromeIcons.Does.CONNECT) { initRepo(dir) })
                    // With a remote set, offer Clone into this (empty/non-repo) directory.
                    if (!remoteUrl.isNullOrBlank()) {
                        pills.addView(pillButton(getString(R.string.library_git_clone), icon = ChromeIcons.Icon.IMPORT, does = ChromeIcons.Does.CONNECT) {
                            cloneRepo(remoteUrl, dir)
                        })
                    }
                    container.addView(pills)
                }
                is GitState.Repo -> {
                    if (state.pending > 0) {
                        container.addView(gitCaption(getString(R.string.library_git_pending, state.pending)))
                    }
                    val pills = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
                    pills.setPadding(0, dp(3), 0, dp(3))
                    pills.addView(pillButton(getString(R.string.library_git_commit), main = true, icon = ChromeIcons.Icon.BRANCH, does = ChromeIcons.Does.SHARE) { promptCommitMessage(dir) })
                    // Browse the archive's git log; view/restore a layout as it was at any commit.
                    pills.addView(pillButton(getString(R.string.library_git_history), icon = ChromeIcons.Icon.HISTORY, does = ChromeIcons.Does.VIEW) { showHistory(dir) })
                    // With a remote set, offer Pull / Push for the existing repo.
                    if (!remoteUrl.isNullOrBlank()) {
                        pills.addView(pillButton(getString(R.string.library_git_pull), icon = ChromeIcons.Icon.IMPORT, does = ChromeIcons.Does.CONNECT) { pullRepo(dir) })
                        pills.addView(pillButton(getString(R.string.library_git_push), icon = ChromeIcons.Icon.EXPORT, does = ChromeIcons.Does.SHARE) { pushRepo(dir) })
                    }
                    container.addView(pills)
                    if (state.files.isEmpty()) {
                        container.addView(gitCaption(getString(R.string.library_git_no_files)))
                    } else {
                        state.files.forEach { (file, rel) ->
                            container.addView(gitFileRow(file, rel, dir))
                        }
                    }
                }
            }
        }
    }

    private sealed interface GitState {
        data object Missing : GitState
        data object NotRepo : GitState
        data class Repo(val files: List<Pair<File, String>>, val pending: Int) : GitState
    }

    /** The "Repository path  <value>" row — tap to edit the path in an AlertDialog. */
    private fun gitPathRow(path: String?): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(8), dp(2))
        isClickable = true
        isFocusable = true
        setOnClickListener { editRepoPath(path) }
        addView(rowIcon(ChromeIcons.Icon.DIRECTORY, ChromeIcons.Does.VIEW))
        addView(
            TextView(requireContext()).apply {
                text = getString(R.string.library_git_path_label)
                setTextColor(ChromeColors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
        )
        addView(
            TextView(requireContext()).apply {
                text = path?.takeIf { it.isNotBlank() } ?: getString(R.string.library_git_path_unset)
                setTextColor(ChromeColors.note)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
    }

    /** The "Remote  <url>" row — tap to edit the HTTPS URL + username + token in an AlertDialog. */
    private fun gitRemoteRow(url: String?): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(8), dp(2))
        isClickable = true
        isFocusable = true
        setOnClickListener { editRemote() }
        addView(rowIcon(ChromeIcons.Icon.GLOBE, ChromeIcons.Does.SHARE))
        addView(
            TextView(requireContext()).apply {
                text = getString(R.string.library_git_remote_label)
                setTextColor(ChromeColors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
        )
        addView(
            TextView(requireContext()).apply {
                text = url?.takeIf { it.isNotBlank() } ?: getString(R.string.library_git_remote_unset)
                setTextColor(ChromeColors.note)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
    }

    private fun gitCaption(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextColor(ChromeColors.note)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(4), dp(2), dp(8), dp(6))
    }

    /** The leading icon of a value row — one icon column, coloured by what the row does. */
    private fun rowIcon(icon: ChromeIcons.Icon, does: ChromeIcons.Does): View = View(requireContext()).apply {
        background = ChromeIcons.drawable(requireContext(), icon, does)
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) }
    }

    /** A left-aligned single-pill row, so a lone pill doesn't stretch full width. */
    private fun pillRow(pill: View): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(3), 0, dp(3))
        addView(pill)
    }

    /** "<relative path>   [Import]" — Import copies the archived JSON into the editable custom store. */
    private fun gitFileRow(file: File, rel: String, dir: File): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(3), dp(8), dp(3))
        addView(
            TextView(requireContext()).apply {
                text = rel
                setTextColor(ChromeColors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        addView(pillButton(getString(R.string.library_git_import), icon = ChromeIcons.Icon.IMPORT, does = ChromeIcons.Does.CONNECT) { importFile(file, dir) })
    }

    private fun editRepoPath(current: String?) {
        val input = EditText(requireContext()).apply {
            setText(current ?: "")
            hint = getString(R.string.library_git_path_dialog_hint)
            setSingleLine()
        }
        val pad = dp(20)
        val box = FrameLayout(requireContext()).apply { setPadding(pad, dp(8), pad, 0); addView(input) }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.library_git_path_dialog_title)
            .setMessage(R.string.library_git_path_dialog_message)
            .setView(box)
            .setPositiveButton(R.string.library_git_save) { _, _ ->
                lifecycleScope.launch {
                    settingsRepository.setLibraryRepoPath(input.text.toString())
                    rebuild()
                }
            }
            .setNeutralButton(R.string.library_git_browse) { _, _ ->
                val start = current?.takeIf { it.isNotBlank() }?.let { File(it) }
                    ?.takeIf { it.isDirectory }
                    ?: Environment.getExternalStorageDirectory()
                browseForDirectory(start) { picked ->
                    lifecycleScope.launch {
                        settingsRepository.setLibraryRepoPath(picked.absolutePath)
                        rebuild()
                    }
                }
            }
            .setNegativeButton(R.string.library_git_cancel, null)
            .show()
    }

    /** Edit the HTTPS remote — URL, username, token (masked) — in one dialog; saves all three at once. */
    private fun editRemote() {
        lifecycleScope.launch {
            val currentUrl = settingsRepository.getLibraryRepoRemote() ?: ""
            val currentUser = settingsRepository.getLibraryRepoUser()
            val currentToken = settingsRepository.getLibraryRepoToken()

            val urlField = EditText(requireContext()).apply {
                setText(currentUrl)
                hint = getString(R.string.library_git_remote_url_hint)
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            val userField = EditText(requireContext()).apply {
                setText(currentUser)
                hint = getString(R.string.library_git_remote_user_hint)
                setSingleLine()
            }
            val tokenField = EditText(requireContext()).apply {
                setText(currentToken)
                hint = getString(R.string.library_git_remote_token_hint)
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val pad = dp(20)
            val box = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, dp(8), pad, 0)
                addView(urlField)
                addView(userField)
                addView(tokenField)
            }
            AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
                .setTitle(R.string.library_git_remote_dialog_title)
                .setMessage(R.string.library_git_remote_dialog_message)
                .setView(box)
                .setPositiveButton(R.string.library_git_save) { _, _ ->
                    lifecycleScope.launch {
                        settingsRepository.setLibraryRepoRemote(
                            urlField.text.toString(),
                            userField.text.toString(),
                            tokenField.text.toString()
                        )
                        flash(getString(R.string.library_git_remote_saved))
                        rebuild()
                    }
                }
                .setNegativeButton(R.string.library_git_cancel, null)
                .show()
        }
    }

    /** Clone the saved remote into [dir] (must be empty/non-repo), then commit-config the origin URL. */
    private fun cloneRepo(url: String, dir: File) {
        lifecycleScope.launch {
            val user = settingsRepository.getLibraryRepoUser()
            val token = settingsRepository.getLibraryRepoToken()
            if (token.isBlank()) {
                flash(getString(R.string.library_git_remote_need_fields)); return@launch
            }
            val result = withContext(Dispatchers.IO) { GitArchive.clone(url, dir, user, token) }
            flash(result.message)
            rebuild()
        }
    }

    /** Ensure `origin` points at the saved remote, then pull. */
    private fun pullRepo(dir: File) {
        lifecycleScope.launch {
            val url = settingsRepository.getLibraryRepoRemote()
            val user = settingsRepository.getLibraryRepoUser()
            val token = settingsRepository.getLibraryRepoToken()
            if (url.isNullOrBlank() || token.isBlank()) {
                flash(getString(R.string.library_git_remote_need_fields)); return@launch
            }
            val result = withContext(Dispatchers.IO) {
                GitArchive.setRemote(dir, url)
                GitArchive.pull(dir, user, token)
            }
            flash(result.message)
            rebuild()
        }
    }

    /** Ensure `origin` points at the saved remote, then push. */
    private fun pushRepo(dir: File) {
        lifecycleScope.launch {
            val url = settingsRepository.getLibraryRepoRemote()
            val user = settingsRepository.getLibraryRepoUser()
            val token = settingsRepository.getLibraryRepoToken()
            if (url.isNullOrBlank() || token.isBlank()) {
                flash(getString(R.string.library_git_remote_need_fields)); return@launch
            }
            val result = withContext(Dispatchers.IO) {
                GitArchive.setRemote(dir, url)
                GitArchive.push(dir, user, token)
            }
            flash(result.message)
            rebuild()
        }
    }

    /**
     * A no-SAF, in-app directory browser (we hold All-Files-Access): list the subdirectories of [dir], let
     * the user walk up/into them, create a new one, or pick [dir] itself. Re-shows itself per navigation.
     */
    private fun browseForDirectory(dir: File, onPick: (File) -> Unit) {
        if (!hasAllFilesAccess()) {
            flash(getString(R.string.library_git_need_access)); requestAllFilesAccess(); return
        }
        val subdirs = (dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray())
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        val labels = mutableListOf<String>()
        val targets = mutableListOf<File>()
        val hasUp = dir.parentFile != null
        dir.parentFile?.let { labels.add(getString(R.string.library_git_up)); targets.add(it) }
        subdirs.forEach { labels.add(it.name); targets.add(it) }
        // The places to go, as full-width pills; the dialog's own buttons stay the decisions.
        var dialog: AlertDialog? = null
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(2))
        }
        labels.forEachIndexed { i, label ->
            val up = hasUp && i == 0
            column.addView(
                Pills.iconPill(
                    requireContext(), label,
                    if (up) ChromeIcons.Icon.UP else ChromeIcons.Icon.DIRECTORY,
                    ChromeIcons.color(ChromeIcons.Does.VIEW), opens = true, fullWidth = true
                ) {
                    dialog?.dismiss()
                    browseForDirectory(targets[i], onPick)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3); bottomMargin = dp(3) }
            )
        }
        dialog = AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(dir.absolutePath)
            .setView(ScrollView(requireContext()).apply { addView(column) })
            .setPositiveButton(R.string.library_git_select_directory) { _, _ -> onPick(dir) }
            .setNeutralButton(R.string.library_git_new_directory) { _, _ ->
                val input = EditText(requireContext()).apply { setSingleLine() }
                val pad = dp(20)
                AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
                    .setTitle(R.string.library_git_new_directory)
                    .setView(FrameLayout(requireContext()).apply { setPadding(pad, dp(8), pad, 0); addView(input) })
                    .setPositiveButton(R.string.library_git_save) { _, _ ->
                        val name = input.text.toString().trim().takeIf { it.isNotEmpty() }
                        val created = name?.let { File(dir, it).apply { mkdirs() } }
                        browseForDirectory(created?.takeIf { it.isDirectory } ?: dir, onPick)
                    }
                    .setNegativeButton(R.string.library_git_cancel) { _, _ -> browseForDirectory(dir, onPick) }
                    .show()
            }
            .setNegativeButton(R.string.library_git_cancel, null)
            .show()
    }

    private fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        val pkg = "package:" + requireContext().packageName
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse(pkg))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                flash(getString(R.string.library_git_need_access))
            }
        }
    }

    private fun initRepo(dir: File) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    GitArchive.initIfNeeded(dir)
                    GitArchive.isRepo(dir)
                } catch (_: Exception) {
                    false
                }
            }
            flash(getString(if (ok) R.string.library_git_initialised_toast else R.string.library_git_init_failed))
            rebuild()
        }
    }

    /** Read the archived file's JSON, derive a [LayoutEntry], and save it into the editable custom store. */
    private fun importFile(file: File, dir: File) {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                try {
                    JSONObject(file.readText())
                } catch (_: Exception) {
                    null
                }
            }
            if (json == null) {
                flash(getString(R.string.library_git_import_failed))
                return@launch
            }
            val baseId = file.relativeTo(dir).path
                .removeSuffix(".json")
                .substringAfterLast('/')
                .ifBlank { "imported" }
            val newId = CustomLayoutStore.freshId(requireContext(), baseId)
            // The shipped layout JSON carries `locale` (the registry's `lang` key), `script`, and `name`;
            // `kind`/`width` live only in the registry, so default them empty.
            val lang = json.optString("locale", "")
            val name = json.optString("name", "").ifBlank { baseId }
            val entry = LayoutEntry(
                id = newId,
                lang = lang,
                name = name,
                kind = json.optString("kind", ""),
                width = json.optString("width", ""),
                derivedFrom = null
            )
            CustomLayoutStore.saveLayout(requireContext(), entry, json)
            flash(getString(R.string.library_git_imported_toast, name))
            rebuild()
        }
    }

    /**
     * Mirror the FULL effective layout collection (bundled + the user's edits) into `<dir>/layouts/` under
     * CLEAN names + the registry, then commit. A shadow copy (an edited stock) is written under the STOCK id
     * it replaces — so the archive holds `gnu_5r13c.json` with the edited content, never `gnu_5r13c_copy.json`.
     * The layouts dir is rebuilt each commit so it's a faithful mirror; stray `_copy` files an earlier version
     * wrote to the repo root are cleaned up.
     */
    /**
     * Commits are explicit (never automatic on edit) — edits already auto-save to the runtime store; this
     * snapshots the whole library into the archive on demand. Prompt for a free-text message first so the
     * commit (and the History list) records WHAT changed; a blank message falls back to a default.
     */
    private fun promptCommitMessage(dir: File) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.library_git_commit_dialog_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }
        val pad = dp(20)
        val box = FrameLayout(requireContext()).apply { setPadding(pad, dp(8), pad, 0); addView(input) }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.library_git_commit_dialog_title)
            .setView(box)
            .setPositiveButton(R.string.library_git_commit) { _, _ ->
                val message = input.text.toString().trim()
                    .ifBlank { getString(R.string.library_git_commit_default_msg) }
                commitLibrary(dir, message)
            }
            .setNegativeButton(R.string.library_git_cancel, null)
            .show()
    }

    private fun commitLibrary(dir: File, message: String) {
        lifecycleScope.launch {
            val ctx = requireContext()
            val hash = withContext(Dispatchers.IO) { LibraryArchive.mirrorAndCommit(ctx, dir, message) }
            flash(
                if (hash != null) getString(R.string.library_git_committed_toast, hash.take(8))
                else getString(R.string.library_git_nothing_to_commit)
            )
            rebuild()
        }
    }

    // --- History browser (M4 L3): browse the archive's git log; preview/restore a layout at any commit. ---
    // All git/file reads run off the main thread; the dialogs degrade gracefully when the repo can't be read.

    /** Off-thread read the archive's commit log, then show it as a black/yellow list dialog. */
    private fun showHistory(dir: File) {
        lifecycleScope.launch {
            val commits = withContext(Dispatchers.IO) { GitArchive.log(dir) }
            if (commits.isEmpty()) {
                flash(getString(R.string.library_git_history_empty)); return@launch
            }
            val labels = commits.map { c ->
                "${c.shortHash}  ·  ${relativeTime(c.timeMs)}\n${c.message}"
            }.toTypedArray()
            ChromeDialogs.actions(
                requireContext(), getString(R.string.library_git_history_title),
                labels.map { ChromeDialogs.Row(it, ChromeIcons.Icon.HISTORY, ChromeIcons.Does.VIEW, opens = true) },
                cancelText = getString(R.string.library_git_close)
            ) { which -> showCommitLayouts(dir, commits[which]) }
        }
    }

    /** For one commit, list the layouts it held, each with Preview + Restore. */
    private fun showCommitLayouts(dir: File, commit: GitArchive.CommitInfo) {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { GitArchive.layoutFilesAtCommit(dir, commit.hash) }
            if (files.isEmpty()) {
                flash(getString(R.string.library_git_commit_no_layouts)); return@launch
            }
            // A vertical list: per layout a row with its path + Preview + Restore (the standard pills).
            val list = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val p = dp(12)
                setPadding(p, dp(4), p, dp(4))
            }
            val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
                .setTitle(getString(R.string.library_git_commit_title, commit.shortHash, relativeTime(commit.timeMs)))
                .setView(ScrollView(requireContext()).apply { addView(list) })
                .setNegativeButton(R.string.library_git_close, null)
                .create()
            files.forEach { path ->
                list.addView(commitLayoutRow(dir, commit, path) { dialog.dismiss() })
            }
            dialog.show()
        }
    }

    /** "<path>   [Preview] [Restore]" for one layout inside a commit. */
    private fun commitLayoutRow(
        dir: File,
        commit: GitArchive.CommitInfo,
        path: String,
        onRestored: () -> Unit
    ): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(3), 0, dp(3))
        addView(
            TextView(requireContext()).apply {
                text = path.removePrefix("layouts/")
                setTextColor(ChromeColors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        addView(pillButton(getString(R.string.library_git_history_preview), icon = ChromeIcons.Icon.EYE, does = ChromeIcons.Does.VIEW) { previewAtCommit(dir, commit, path) })
        addView(pillButton(getString(R.string.library_git_history_restore), main = true, icon = ChromeIcons.Icon.RESET, does = ChromeIcons.Does.CONNECT) {
            restoreAtCommit(dir, commit, path); onRestored()
        })
    }

    /**
     * Read the layout JSON as it was at [commit]'s [path], parse it, and live-render that exact version in
     * the Library preview panel — the same render path as [preview], but from in-memory git-blob JSON rather
     * than a stored layout id. Look knobs resolve off the JSON's own `locale` + base id.
     */
    private fun previewAtCommit(dir: File, commit: GitArchive.CommitInfo, path: String) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { GitArchive.fileAtCommit(dir, commit.hash, path) }
            val json = text?.let { try { JSONObject(it) } catch (_: Exception) { null } }
            if (json == null) {
                flash(getString(R.string.library_git_history_preview_failed)); return@launch
            }
            val baseId = path.removePrefix("layouts/").removeSuffix(".json").substringAfterLast('/')
                .ifBlank { "version" }
            val lang = json.optString("locale", "")
            val name = json.optString("name", "").ifBlank { baseId }
            val layout = keyboardRepository.layoutFromJson(json)
            if (layout == null) {
                flash(getString(R.string.library_git_history_preview_failed)); return@launch
            }
            renderPreview(layout, lang, baseId, historyPreviewHeader("$name · ${commit.shortHash}"))
        }
    }

    /**
     * Bring the layout as it was at [commit]'s [path] back into the editable custom store as a NEW layout
     * (fresh id, derived name/lang from the JSON) — mirrors [importFile]. We never `git checkout` the whole
     * repo; just this one version becomes a fresh editable Library entry.
     */
    private fun restoreAtCommit(dir: File, commit: GitArchive.CommitInfo, path: String) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { GitArchive.fileAtCommit(dir, commit.hash, path) }
            val json = text?.let { try { JSONObject(it) } catch (_: Exception) { null } }
            if (json == null) {
                flash(getString(R.string.library_git_history_restore_failed)); return@launch
            }
            val baseId = path.removePrefix("layouts/").removeSuffix(".json").substringAfterLast('/')
                .ifBlank { "restored" }
            val newId = CustomLayoutStore.freshId(requireContext(), baseId)
            val lang = json.optString("locale", "")
            val name = json.optString("name", "").ifBlank { baseId }
            val entry = LayoutEntry(
                id = newId,
                lang = lang,
                name = name,
                kind = json.optString("kind", ""),
                width = json.optString("width", ""),
                derivedFrom = null
            )
            CustomLayoutStore.saveLayout(requireContext(), entry, json)
            flash(getString(R.string.library_git_history_restored_toast, name))
            rebuild()
        }
    }

    /** A coarse "Nm/h/d ago" label for a commit time (no extra deps; good enough for a log row). */
    private fun relativeTime(timeMs: Long): String {
        val delta = System.currentTimeMillis() - timeMs
        if (delta < 0) return java.text.DateFormat.getDateInstance().format(java.util.Date(timeMs))
        val minutes = delta / 60_000L
        val hours = delta / 3_600_000L
        val days = delta / 86_400_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 30 -> "${days}d ago"
            else -> java.text.DateFormat.getDateInstance().format(java.util.Date(timeMs))
        }
    }

    private fun heading(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextColor(look.headingColor ?: LibraryLook.DEF_HEADING_COLOR)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, (look.headingSizeSp ?: LibraryLook.DEF_HEADING_SIZE).toFloat())
        typeface = KeyboardFonts.weightedTypeface(
            requireContext(), look.headingFont ?: "", look.headingWeight ?: LibraryLook.DEF_HEADING_WEIGHT
        )
        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        setPadding(0, dp(16), 0, dp(6))
    }

    // ---- Add language ----------------------------------------------------------------------------------

    /** Languages that ship a dictionary + a standard-layout spec but have no layout yet. */
    private fun addableLanguages(): List<String> {
        val withLayouts = registry.entries.map { it.lang }.toSet()
        return com.urik.keyboard.data.StandardLayouts.SPECS.keys.filter { it !in withLayouts }
    }

    private fun showAddLanguageDialog() {
        val candidates = addableLanguages()
        val labels = candidates.map { lang ->
            val spec = com.urik.keyboard.data.StandardLayouts.SPECS[lang]!!
            "${spec.nativeName} — ${spec.layoutName}"
        }.toTypedArray()
        ChromeDialogs.actions(
            requireContext(), getString(R.string.library_add_language_title),
            labels.map { ChromeDialogs.Row(it, ChromeIcons.Icon.GLOBE, ChromeIcons.Does.CONTACT) },
            cancelText = getString(R.string.export_import_cancel)
        ) { which -> addLanguage(candidates[which]) }
    }

    /**
     * Create the language's standard keyboard as a CUSTOM layout, make it the language's default
     * and its only switcher entry, and activate the language — ready to type immediately.
     */
    private fun addLanguage(lang: String) {
        val spec = com.urik.keyboard.data.StandardLayouts.SPECS[lang] ?: return
        lifecycleScope.launch {
            val id = "${lang}_std_5r10c"
            val json = com.urik.keyboard.data.StandardLayouts.buildLayoutJson(requireContext(), lang, spec)
            CustomLayoutStore.saveLayout(
                requireContext(),
                LayoutEntry(id, lang, spec.layoutName, "compass", "10c"),
                json
            )
            settingsRepository.setVisibleLayoutsForLanguage(lang, setOf(id))
            settingsRepository.setActiveLayoutForLanguage(lang, id)
            val s = settingsRepository.settings.first()
            if (lang !in s.activeLanguages) {
                settingsRepository.updateActiveLanguages(s.activeLanguages + lang, s.primaryLayoutLanguage)
            }
            KxkbToast.show(requireContext(), getString(R.string.library_language_added, spec.nativeName))
            rebuild()
        }
    }

    /**
     * Toggle whether [entry] appears in the space-slide switcher. The stored set materialises from
     * "all visible" on the first toggle; the active layout can be hidden too (it stays active, the
     * switcher just stops listing it).
     */
    private fun toggleSwitcherVisibility(entry: LayoutEntry, currentlyVisible: Boolean) {
        lifecycleScope.launch {
            val all = registry.forLanguage(entry.lang).map { it.id }
            val stored = settingsRepository.getVisibleLayoutsByLanguage()[entry.lang]
            val visibleNow = stored ?: all.toSet()
            val next = if (currentlyVisible) visibleNow - entry.id else visibleNow + entry.id
            settingsRepository.setVisibleLayoutsForLanguage(entry.lang, next)
            KxkbToast.show(
                requireContext(),
                getString(
                    if (currentlyVisible) R.string.library_switcher_hidden else R.string.library_switcher_shown,
                    entry.name
                )
            )
            rebuild()
        }
    }

    /** The switcher-visibility chip: ⇄ marked (tinted, bold) = listed in the space-slide switcher, dim = hidden. */
    private fun switcherPill(entry: LayoutEntry, inSwitcher: Boolean): View = TextView(requireContext()).apply {
        text = getString(R.string.library_pill_switcher)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        val px = dp(7)
        setPadding(px, dp(1), px, dp(1))
        background = Pills.background(
            requireContext(), if (inSwitcher) ChromeColors.accent else ChromeColors.switchOff,
            main = inSwitcher, selected = inSwitcher
        )
        setTextColor(if (inSwitcher) ChromeColors.text else ChromeColors.note)
        setTypeface(null, if (inSwitcher) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(10) }
        isClickable = true
        isFocusable = true
        setOnClickListener { toggleSwitcherVisibility(entry, inSwitcher) }
    }

    private fun layoutRow(entry: LayoutEntry, isActive: Boolean, isCustom: Boolean, inSwitcher: Boolean): View {
        val spacing = look.rowSpacingDp ?: LibraryLook.DEF_ROW_SPACING
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(look.indentDp ?: LibraryLook.DEF_INDENT), dp(spacing), dp(8), dp(spacing))
            isClickable = true
            isFocusable = true
            setOnClickListener { preview(entry) }
        }
        // The row's icon sits in the indent, so the names keep their edge: a green tick on the layout that is
        // active for its language, a keyboard on every other.
        val indentPx = dp(look.indentDp ?: LibraryLook.DEF_INDENT)
        val iconPx = dp(18)
        if (indentPx >= iconPx + dp(8)) {
            row.setPadding(indentPx - iconPx - dp(8), row.paddingTop, row.paddingRight, row.paddingBottom)
            row.addView(
                View(requireContext()).apply {
                    background = if (isActive) {
                        ChromeIcons.drawable(requireContext(), ChromeIcons.Icon.CHECK, ChromeIcons.Does.CONNECT, 18f)
                    } else {
                        ChromeIcons.drawable(requireContext(), ChromeIcons.Icon.KEYBOARD, ChromeIcons.Does.CONTACT, 18f)
                    }
                },
                LinearLayout.LayoutParams(iconPx, iconPx).apply { marginEnd = dp(8) }
            )
        }
        val nameWeight = look.nameWeight ?: LibraryLook.DEF_NAME_WEIGHT
        val name = TextView(requireContext()).apply {
            text = entry.name
            setTextColor(look.nameColor ?: LibraryLook.DEF_NAME_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (look.nameSizeSp ?: LibraryLook.DEF_NAME_SIZE).toFloat())
            typeface = KeyboardFonts.weightedTypeface(
                requireContext(), look.nameFont ?: "", if (isActive) maxOf(nameWeight, 700) else nameWeight
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val badge = TextView(requireContext()).apply {
            text = badgeText(entry, isActive)
            setTextColor(look.badgeColor ?: LibraryLook.DEF_BADGE_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (look.badgeSizeSp ?: LibraryLook.DEF_BADGE_SIZE).toFloat())
            typeface = KeyboardFonts.weightedTypeface(
                requireContext(), look.badgeFont ?: "", look.badgeWeight ?: LibraryLook.DEF_BADGE_WEIGHT
            )
        }
        row.addView(name)
        row.addView(switcherPill(entry, inSwitcher))
        row.addView(statusPill(isCustom))
        row.addView(badge)
        return row
    }

    /**
     * A small "stock"/"custom" tag so the two kinds of layout are unmistakable at a glance (custom = bright
     * and bold). Square-cornered on purpose: it is a label, and only something pressable is a pill.
     */
    private fun statusPill(isCustom: Boolean): View = TextView(requireContext()).apply {
        text = getString(if (isCustom) R.string.library_pill_custom else R.string.library_pill_stock)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        val px = dp(7)
        setPadding(px, dp(1), px, dp(1))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), if (isCustom) ChromeColors.accent else ChromeColors.switchOff)
        }
        setTextColor(if (isCustom) ChromeColors.text else ChromeColors.note)
        setTypeface(null, if (isCustom) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(10) }
    }

    private fun separator(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(look.separatorThicknessDp ?: LibraryLook.DEF_SEPARATOR_THICKNESS)
        )
        setBackgroundColor(look.separatorColor ?: LibraryLook.DEF_SEPARATOR_COLOR)
    }

    /** Render the tapped layout as a true keyboard in the bottom panel, without activating it. */
    private fun preview(entry: LayoutEntry) {
        lifecycleScope.launch {
            val layout = keyboardRepository.loadLayoutById(entry.id) ?: return@launch
            renderPreview(layout, entry.lang, entry.id, header = previewHeader(entry))
        }
    }

    /**
     * The shared live-render path: resolve the look knobs for [lang]/[id] at the keyboard's geometry, build
     * the real keyboard view from [layout], and drop it (under [header]) into the bottom preview panel. Used
     * for both a stored Library layout ([preview]) and an at-commit version from git ([previewAtCommit]).
     */
    private fun renderPreview(
        layout: com.urik.keyboard.model.KeyboardLayout,
        lang: String,
        id: String,
        header: View
    ) {
        lifecycleScope.launch {
            val settings = settingsRepository.settings.first()
            val density = resources.displayMetrics.density
            val posture = PostureDetector(requireContext(), lifecycleScope).postureInfo.value
            // Resolve the look for the geometry the live keyboard is actually using (the IME publishes it).
            // The fold-aware IME bucket can differ from the settings Activity's Configuration bucket, and
            // the whole per-geometry look (spacing, square keys, colours, borders) hangs off it.
            val geometry = settingsRepository.currentGeometry.first()
                ?: GeometryBucket.fromConfiguration(resources.configuration).key
            val knobs = settingsRepository.resolveLookKnobs(null, id, geometry)
            // Render at the live keyboard's actual height when the IME has published one (its on-keyboard
            // resize lives in a per-combo fork that a different previewed layout wouldn't otherwise pick up).
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
            previewContainer.setBackgroundColor(bg)
            previewContainer.addView(header)
            previewContainer.addView(keyboardView)
            previewContainer.visibility = View.VISIBLE
        }
    }

    /** A plain title bar (no Activate/Edit) above an at-commit history preview. */
    private fun historyPreviewHeader(title: String): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = dp(10)
        setPadding(p, p, p, dp(4))
        addView(
            TextView(requireContext()).apply {
                text = title
                setTextColor(ChromeColors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
    }

    /** The "<name>   [Activate]" bar above the live preview. */
    private fun previewHeader(entry: LayoutEntry): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = dp(10)
        setPadding(p, p, p, dp(4))
        addView(
            TextView(requireContext()).apply {
                text = "${entry.name} · ${langDisplay(entry.lang)}"
                setTextColor(ChromeColors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        addView(pillButton(getString(R.string.library_duplicate), icon = ChromeIcons.Icon.COPY, does = ChromeIcons.Does.SHARE) { duplicate(entry) })
        // Rename works on stock too (via a name override) — stock asset stays read-only.
        addView(pillButton(getString(R.string.library_rename), icon = ChromeIcons.Icon.EDIT, does = ChromeIcons.Does.MESSAGE) { renameDialog(entry) })
        if (CustomLayoutStore.hasLayout(requireContext(), entry.id)) {
            addView(pillButton(getString(R.string.library_edit), icon = ChromeIcons.Icon.KEYBOARD, does = ChromeIcons.Does.CONTACT) {
                startActivity(KeyboardEditorActivity.intent(requireContext(), entry))
            })
            // An edited stock (shadow) can pull in later bundled-asset changes without losing the user's edits.
            if (entry.derivedFrom != null) {
                addView(pillButton(getString(R.string.library_resync), icon = ChromeIcons.Icon.RESET, does = ChromeIcons.Does.CONNECT) { resync(entry) })
            }
            addView(pillButton(getString(R.string.library_delete), color = ChromeColors.delete, icon = ChromeIcons.Icon.DELETE, does = ChromeIcons.Does.DELETE) { delete(entry) })
        }
        addView(pillButton(getString(R.string.library_activate), main = true, icon = ChromeIcons.Icon.CHECK, does = ChromeIcons.Does.CONNECT) { activate(entry) })
    }

    /** An outlined pill: [main] is the one action its block is for, a deleting one passes the delete red. */
    private fun pillButton(
        label: String,
        main: Boolean = false,
        color: Int = ChromeColors.accent,
        icon: ChromeIcons.Icon? = null,
        does: ChromeIcons.Does = ChromeIcons.Does.VIEW,
        onClick: () -> Unit
    ): Button = Pills.button(
        requireContext(), label, main = main, color = color, icon = icon, does = does, onClick = onClick
    ).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) }
    }

    /** Copy a layout into the editable custom store (it then appears in the Library and is activatable). */
    private fun duplicate(entry: LayoutEntry) {
        lifecycleScope.launch {
            val raw = CustomLayoutStore.rawJson(requireContext(), entry.id)
            if (raw == null) {
                flash(getString(R.string.library_duplicate_failed))
                return@launch
            }
            val newId = CustomLayoutStore.freshId(requireContext(), entry.id)
            // A stand-alone copy (shown separately, not shadowing any stock).
            val newEntry = entry.copy(id = newId, name = "${entry.name} copy", derivedFrom = null)
            CustomLayoutStore.saveLayout(requireContext(), newEntry, raw)
            rebuild()
            flash(getString(R.string.library_duplicated_toast, newEntry.name))
        }
    }

    /**
     * Remove a custom layout. If a language had it active, reinstate the stock it shadowed ([derivedFrom]) —
     * or fall back to the registry default — so deleting an edited copy brings the original keyboard back.
     */
    private fun delete(entry: LayoutEntry) {
        lifecycleScope.launch {
            CustomLayoutStore.deleteLayout(requireContext(), entry.id)
            if (settingsRepository.getActiveLayoutForLanguage(entry.lang) == entry.id) {
                registry = LayoutRegistry.load(requireContext())
                val reinstate = entry.derivedFrom ?: registry.defaultFor(entry.lang)
                reinstate?.let { settingsRepository.setActiveLayoutForLanguage(entry.lang, it) }
            }
            previewContainer.visibility = View.GONE
            rebuild()
            flash(getString(R.string.library_deleted_toast, entry.name))
        }
    }

    /** Rename a custom layout from the list (a themed text dialog → persist the new display name). */
    private fun renameDialog(entry: LayoutEntry) {
        val input = EditText(requireContext()).apply {
            setText(entry.name)
            setSingleLine()
            setSelection(text.length)
        }
        val pad = dp(20)
        val box = FrameLayout(requireContext()).apply { setPadding(pad, dp(8), pad, 0); addView(input) }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.library_rename_title)
            .setView(box)
            .setPositiveButton(R.string.library_git_save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != entry.name) rename(entry, newName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rename(entry: LayoutEntry, newName: String) {
        lifecycleScope.launch {
            if (CustomLayoutStore.hasLayout(requireContext(), entry.id)) {
                val json = CustomLayoutStore.rawJson(requireContext(), entry.id)
                if (json == null) {
                    flash(getString(R.string.library_rename_failed))
                    return@launch
                }
                json.put("name", newName) // keep the layout JSON's own name field in step with the entry
                CustomLayoutStore.saveLayout(requireContext(), entry.copy(name = newName), json)
            } else {
                // A bundled stock layout: persist a display-name override; the asset itself stays untouched.
                CustomLayoutStore.setStockName(requireContext(), entry.id, newName)
                LayoutRegistry.invalidate()
            }
            previewContainer.visibility = View.GONE
            rebuild()
            flash(getString(R.string.library_renamed_toast, newName))
        }
    }

    /**
     * Re-sync an edited shadow ([LayoutEntry.derivedFrom] = its stock) with the current bundled stock —
     * additively pulls in new stock structure (e.g. a newly added Number-pad flick / alt page) while keeping
     * the user's edits ([LayoutResync]). Non-destructive, so no confirmation prompt.
     */
    private fun resync(entry: LayoutEntry) {
        val stockId = entry.derivedFrom ?: return
        lifecycleScope.launch {
            val shadow = CustomLayoutStore.rawJson(requireContext(), entry.id)
            val stock = try {
                requireContext().assets.open("layouts/$stockId.json").bufferedReader().use {
                    JSONObject(it.readText())
                }
            } catch (_: Exception) {
                null
            }
            if (shadow == null || stock == null) {
                flash(getString(R.string.library_resync_failed))
                return@launch
            }
            CustomLayoutStore.saveLayout(requireContext(), entry, LayoutResync.mergeFromStock(shadow, stock))
            keyboardRepository.invalidateLayoutCache()
            previewContainer.visibility = View.GONE
            rebuild()
            flash(getString(R.string.library_resynced_toast, entry.name))
        }
    }

    private fun activate(entry: LayoutEntry) {
        lifecycleScope.launch {
            val result = settingsRepository.setActiveLayoutForLanguage(entry.lang, entry.id)
            if (result.isSuccess) {
                // Every app that remembered this board's original, or another copy of it, now opens this one;
                // and the keyboard rebuilds.
                val family = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CustomLayoutStore.supersededBy(requireContext(), entry)
                }
                settingsRepository.rebindPerAppLayouts(entry.lang, family, entry.id)
                // Activating here is a pick for the fold state the keyboard was last shown in — the others
                // keep their own board.
                settingsRepository.currentSizeTarget.first()
                    ?.substringAfterLast('|', "")
                    ?.takeIf { com.urik.keyboard.service.GeometryBucket.fromKey(it) != null }
                    ?.let { settingsRepository.setGeometryLayout(it, entry.lang, entry.id) }
                keyboardRepository.invalidateLayoutCache()
                rebuild()
                flash(getString(R.string.library_applied_toast, entry.name, langDisplay(entry.lang)))
            } else {
                flash(getString(R.string.library_apply_failed))
            }
        }
    }

    private fun badgeText(entry: LayoutEntry, isActive: Boolean): String {
        val parts = listOf(entry.kind, entry.width)
            .filter { it.isNotBlank() }
            .map { it.replaceFirstChar { c -> c.uppercase() } }
            .toMutableList()
        // Copies of one board share its name; the tail of the id ("copy2") is what tells them apart.
        entry.id.substringAfterLast('_').takeIf { it.startsWith("copy") }?.let { parts.add(it) }
        if (isActive) parts.add(getString(R.string.library_active_word))
        return parts.joinToString(" · ")
    }

    private fun langDisplay(lang: String): String = when (lang) {
        "gnu" -> "GNU"
        "ja" -> "日本語"
        "cs" -> "Čeština"
        "ru" -> "Русский"
        "en" -> "English"
        else -> Locale.forLanguageTag(lang)
            .let { it.getDisplayLanguage(it) }
            .ifBlank { lang }
            .replaceFirstChar { it.uppercase() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
