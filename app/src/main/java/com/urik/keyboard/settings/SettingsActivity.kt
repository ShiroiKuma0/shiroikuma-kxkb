package com.urik.keyboard.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.urik.keyboard.R
import com.urik.keyboard.settings.appearance.AppearanceFragment
import com.urik.keyboard.settings.autocorrection.AutoCorrectionFragment
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
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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
            } else {
                finish()
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_OPEN_PAGE = "open_page"
        const val PAGE_KEYBOARD_UI = "keyboard_ui"
        const val PAGE_LANGUAGES = "languages"
        const val PAGE_LIBRARY = "library"

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
