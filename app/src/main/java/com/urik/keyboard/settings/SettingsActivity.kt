package com.urik.keyboard.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.urik.keyboard.R
import com.urik.keyboard.automation.GengoshimaHandoff
import com.urik.keyboard.settings.appearance.AppearanceFragment
import com.urik.keyboard.settings.autocorrection.AutoCorrectionFragment
import com.urik.keyboard.settings.eximport.ExportImportFragment
import com.urik.keyboard.settings.languages.LanguagesFragment
import com.urik.keyboard.settings.keyboardui.KeyboardUiFragment
import com.urik.keyboard.settings.layoutinput.LayoutInputFragment
import com.urik.keyboard.settings.library.KeyboardEditorActivity
import com.urik.keyboard.settings.library.LibraryFragment
import com.urik.keyboard.settings.learnedwords.LearnedWordsFragment
import com.urik.keyboard.settings.privacydata.PrivacyDataFragment
import com.urik.keyboard.settings.typingbehavior.TypingBehaviorFragment
import com.urik.keyboard.settings.userdictionary.UserDictionaryFragment
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    /**
     * The 自由作業盤 task to run on the way out instead of merely finishing — set only when that app's
     * board put 白い熊 here ([WalkCaptureEntryActivity]). This activity is not exported, so nothing but
     * our own trampoline can write it.
     */
    private var returnTask: String? = null

    /**
     * Whether 自由作業盤's door opened this at all. On its own that is already a way back — the 言語島
     * board's own door (contract §8a) — so the review returns there when no task was named.
     */
    private var throughTheDoor = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        returnTask = intent.getStringExtra(EXTRA_RETURN_TASK)
        throughTheDoor = intent.getBooleanExtra(EXTRA_THROUGH_THE_DOOR, false)
        if (returnTask != null || throughTheDoor) installReturnOnBack()

        val toolbar = findViewById<MaterialToolbar>(R.id.settings_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = resources.getString(R.string.settings_title)
        }

        applyWindowInsets()
        setupFragmentTitleUpdates()

        if (savedInstanceState == null) {
            val page: androidx.fragment.app.Fragment? =
                when (intent.getStringExtra(EXTRA_OPEN_PAGE)) {
                    PAGE_KEYBOARD_UI -> KeyboardUiFragment()
                    PAGE_LANGUAGES -> LanguagesFragment()
                    PAGE_LIBRARY -> LibraryFragment()
                    PAGE_EXPORT_IMPORT -> ExportImportFragment()
                    PAGE_USER_DICTIONARY -> UserDictionaryFragment()
                    PAGE_VOICE -> com.urik.keyboard.settings.voice.VoiceSettingsFragment()
                    PAGE_LEARNED_WORDS -> LearnedWordsFragment()
                    PAGE_WALK_CAPTURE -> com.urik.keyboard.settings.voice.VoiceCaptureFragment()
                    else -> null
                }
            if (page != null) {
                // Deep-linked from the space-slide menu: show only the page (no settings list beneath it),
                // so Back / Up returns straight to the app + keyboard rather than into the settings tree.
                supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings_container, page)
                    .commit()
                supportFragmentManager.executePendingTransactions()
                updateToolbarTitle()
            } else {
                supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings_container, MainSettingsFragment())
                    .commit()
            }
        }
    }

    private fun setupFragmentTitleUpdates() {
        supportFragmentManager.addOnBackStackChangedListener {
            updateToolbarTitle()
        }
    }

    private fun updateToolbarTitle() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.settings_container)
        val title =
            when (currentFragment) {
                is AutoCorrectionFragment -> getString(R.string.autocorrect_settings_title)
                is UserDictionaryFragment -> getString(R.string.user_dictionary_title)
                is LanguagesFragment -> getString(R.string.language_settings_title)
                is TypingBehaviorFragment -> getString(R.string.typing_settings_title)
                is LayoutInputFragment -> getString(R.string.layout_settings_title)
                is AppearanceFragment -> getString(R.string.appearance_settings_title)
                is KeyboardUiFragment -> getString(R.string.keyboard_ui_settings_title)
                is LibraryFragment -> getString(R.string.library_settings_title)
                is PrivacyDataFragment -> getString(R.string.privacy_settings_title)
                is LearnedWordsFragment -> getString(R.string.learned_words_title)
                is ExportImportFragment -> getString(R.string.export_import_title)
                is com.urik.keyboard.settings.voice.VoiceSettingsFragment -> getString(R.string.voice_settings_title)
                is com.urik.keyboard.settings.voice.VoiceCorpusFragment -> getString(R.string.voice_corpus_title)
                is com.urik.keyboard.settings.voice.VoiceCaptureFragment -> getString(R.string.voice_capture_title)
                else -> getString(R.string.settings_title)
            }
        supportActionBar?.title = title
    }

    private fun applyWindowInsets() {
        val rootView = findViewById<View>(R.id.settings_root)
        val container = findViewById<View>(R.id.settings_container)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            container.setPadding(0, 0, 0, maxOf(systemBars.bottom, ime.bottom))
            windowInsets
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            } else if (!returnToCaller()) {
                finish()
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * The system Back, answered the way the toolbar arrow is. Registered ONLY when there is somewhere to
     * return to, so the ordinary settings keep the framework's own handling untouched; and because this
     * callback is added after the FragmentManager's, it is asked first and must pop the page stack itself.
     */
    private fun installReturnOnBack() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (supportFragmentManager.backStackEntryCount > 0) {
                        supportFragmentManager.popBackStack()
                    } else if (!returnToCaller()) {
                        finish()
                    }
                }
            }
        )
    }

    /**
     * Put 白い熊 back on 自由作業盤's 言語島 board (contract §8 / §8a).
     *
     * The board cannot simply be revealed again: it finishes itself the moment one of its tiles hands its
     * task over, so by the time this review is on screen the board is gone. It has to be opened, and
     * [GengoshimaHandoff.returnIntent] picks the way — the task the caller named, run through 自由作業盤's
     * own run-a-task trampoline, or that app's door to the board.
     *
     * Resolve-checked, and pinned to 自由作業盤 either way: an older 自由作業盤 without the door answers
     * nothing, and then this says false and the review closes exactly as it does for the space menu.
     */
    fun returnToCaller(): Boolean {
        val back = GengoshimaHandoff.returnIntent(returnTask, throughTheDoor) ?: return false
        if (!resolves(back)) return false
        return runCatching {
            startActivity(back)
            finish()
            true
        }.getOrDefault(false)
    }

    /** Whether anything on this phone answers [intent] — asked before it is fired, never after. */
    private fun resolves(intent: Intent): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)
        }
    }.getOrNull() != null

    companion object {
        const val EXTRA_OPEN_PAGE = "open_page"

        /**
         * The 自由作業盤 task Back, the toolbar arrow and Close run on the way out, and whether that
         * app's door opened this page at all. Written by [WalkCaptureEntryActivity] alone — this
         * activity is not exported.
         */
        const val EXTRA_RETURN_TASK = "return_task"
        const val EXTRA_THROUGH_THE_DOOR = "through_the_door"
        const val PAGE_KEYBOARD_UI = "keyboard_ui"
        const val PAGE_LANGUAGES = "languages"
        const val PAGE_LIBRARY = "library"
        const val PAGE_EXPORT_IMPORT = "export_import"
        const val PAGE_USER_DICTIONARY = "user_dictionary"
        const val PAGE_VOICE = "voice"
        const val PAGE_LEARNED_WORDS = "learned_words"

        /** The walk-capture review — also what the exported [WalkCaptureEntryActivity] opens. */
        const val PAGE_WALK_CAPTURE = "walk_capture"

        fun createIntent(context: Context): Intent = Intent(context, SettingsActivity::class.java)

        /** Open straight to a sub-page (used by the space-slide menu's actions column). */
        fun createIntent(context: Context, page: String): Intent =
            createIntent(context).putExtra(EXTRA_OPEN_PAGE, page)
    }
}

@AndroidEntryPoint
class MainSettingsFragment : PreferenceFragmentCompat() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        screen.addPreference(
            Preference(context).apply {
                key = "auto_correction_category"
                title = resources.getString(R.string.autocorrect_settings_title)
                summary = resources.getString(R.string.autocorrect_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(AutoCorrectionFragment())
                    true
                }
            }
        )

        screen.addPreference(
            Preference(context).apply {
                key = "user_dictionary_category"
                title = resources.getString(R.string.user_dictionary_title)
                summary = resources.getString(R.string.user_dictionary_summary)
                setOnPreferenceClickListener {
                    navigateToFragment(UserDictionaryFragment())
                    true
                }
            }
        )

        screen.addPreference(
            Preference(context).apply {
                key = "languages_category"
                title = resources.getString(R.string.language_settings_title)
                summary = resources.getString(R.string.language_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(LanguagesFragment())
                    true
                }
            }
        )

        screen.addPreference(
            Preference(context).apply {
                key = "voice_input_category"
                title = resources.getString(R.string.voice_settings_title)
                summary = resources.getString(R.string.voice_settings_summary)
                setOnPreferenceClickListener {
                    navigateToFragment(com.urik.keyboard.settings.voice.VoiceSettingsFragment())
                    true
                }
            }
        )

        screen.addPreference(
            Preference(context).apply {
                key = "typing_behavior_category"
                title = resources.getString(R.string.typing_settings_title)
                summary = resources.getString(R.string.typing_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(TypingBehaviorFragment())
                    true
                }
            }
        )

        screen.addPreference(
            Preference(context).apply {
                key = "layout_input_category"
                title = resources.getString(R.string.layout_settings_title)
                summary = resources.getString(R.string.layout_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(LayoutInputFragment())
                    true
                }
            }
        )

        screen.addPreference(
            Preference(context).apply {
                key = "appearance_category"
                title = resources.getString(R.string.appearance_settings_title)
                summary = resources.getString(R.string.appearance_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(AppearanceFragment())
                    true
                }
            }
        )

        screen.addPreference(
            Preference(context).apply {
                key = "keyboard_ui_category"
                title = resources.getString(R.string.keyboard_ui_settings_title)
                summary = resources.getString(R.string.keyboard_ui_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(KeyboardUiFragment())
                    true
                }
            }
        )

        screen.addPreference(
            Preference(context).apply {
                key = "editor_category"
                title = resources.getString(R.string.editor_settings_title)
                summary = resources.getString(R.string.editor_settings_description)
                setOnPreferenceClickListener {
                    openEditorOnActiveLayout()
                    true
                }
            }
        )

        screen.addPreference(
            Preference(context).apply {
                key = "library_category"
                title = resources.getString(R.string.library_settings_title)
                summary = resources.getString(R.string.library_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(LibraryFragment())
                    true
                }
            }
        )

        screen.addPreference(
            Preference(context).apply {
                key = "privacy_data_category"
                title = resources.getString(R.string.privacy_settings_title)
                summary = resources.getString(R.string.privacy_settings_description)
                setOnPreferenceClickListener {
                    navigateToFragment(PrivacyDataFragment())
                    true
                }
            }
        )

        screen.addPreference(
            Preference(context).apply {
                key = "open_source_licenses"
                title = resources.getString(R.string.licenses_title)
                summary = resources.getString(R.string.licenses_description)
                setOnPreferenceClickListener {
                    startActivity(Intent(context, OssLicensesActivity::class.java))
                    true
                }
            }
        )

        screen.addPreference(
            Preference(context).apply {
                key = "dictionary_attribution"
                title = resources.getString(R.string.dictionary_attribution_title)
                summary = resources.getString(R.string.dictionary_attribution_summary)
                setOnPreferenceClickListener {
                    startActivity(Intent(context, DictionaryAttributionActivity::class.java))
                    true
                }
            }
        )

        preferenceScreen = screen
    }

    /**
     * Launch the visual editor on the currently active layout. The target language is the one the live
     * keyboard last published (falling back to the first active language); the editor itself duplicates a
     * stock layout into an editable copy on open. The settings read suspends, so it runs in a coroutine.
     */
    private fun openEditorOnActiveLayout() {
        lifecycleScope.launch {
            val lang = settingsRepository.getCurrentLayoutLanguage()
                ?: settingsRepository.settings.first().activeLanguages.firstOrNull()
            startActivity(KeyboardEditorActivity.intentForActiveLayout(requireContext(), lang))
        }
    }

    private fun navigateToFragment(fragment: androidx.fragment.app.Fragment) {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}
