package com.urik.keyboard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.service.KeyboardLookKnobs
import com.urik.keyboard.settings.SettingsRepository.Companion.EXPORT_SET_DELIMITER
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.isUserUnlocked
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "keyboard_settings"
)

/** All settings changes are validated before persistence. */
@Singleton
class SettingsRepository
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: KeyboardDatabase,
    private val cacheMemoryManager: CacheMemoryManager,
    private val wordFrequencyRepository: com.urik.keyboard.data.WordFrequencyRepository
) {
    private val realDataStore = context.settingsDataStore

    /**
     * BFU-safe DataStore wrapper — ALL settings access goes through it. While the device is locked (Direct
     * Boot), reads emit EMPTY preferences (so the `?: default` mapping below yields all-defaults) and writes
     * are dropped: the credential-protected DataStore must never be touched on the lock screen, where a throw
     * could brick PIN entry. Each access re-checks `isUserUnlocked`, so it transitions to the real store the
     * instant the user unlocks — no restart needed. Reads are also `.catch`-guarded; writes are try/caught.
     */
    private val dataStore = object {
        val data: Flow<Preferences>
            get() = if (context.isUserUnlocked) {
                realDataStore.data.catch { emit(emptyPreferences()) }
            } else {
                flowOf(emptyPreferences())
            }

        suspend fun edit(transform: suspend (MutablePreferences) -> Unit) {
            if (!context.isUserUnlocked) return
            try {
                realDataStore.edit(transform)
            } catch (_: Throwable) {
                // A settings write must never crash the keyboard.
            }
        }
    }

    private object PreferenceKeys {
        val SHOW_SUGGESTIONS = booleanPreferencesKey("show_suggestions")
        val SPELL_CHECK_ENABLED = booleanPreferencesKey("spell_check_enabled")
        val SUGGESTION_COUNT = intPreferencesKey("suggestion_count")
        val LEARN_NEW_WORDS = booleanPreferencesKey("learn_new_words")
        val CLIPBOARD_ENABLED = booleanPreferencesKey("clipboard_enabled")
        val CLIPBOARD_CONSENT_SHOWN = booleanPreferencesKey("clipboard_consent_shown")
        val ACTIVE_LANGUAGES_LIST = stringPreferencesKey("active_languages_list")
        val PRIMARY_LANGUAGE = stringPreferencesKey("primary_language")
        val PRIMARY_LAYOUT_LANGUAGE = stringPreferencesKey("primary_layout_language")
        val PER_APP_LAYOUT_LANGUAGES = stringPreferencesKey("per_app_layout_languages")
        val ACTIVE_LAYOUT_BY_LANGUAGE = stringPreferencesKey("active_layout_by_language")
        val PER_GEOMETRY_LOOK = stringPreferencesKey("per_geometry_look")
        val LIBRARY_LOOK = stringPreferencesKey("library_look")
        val CURRENT_GEOMETRY = stringPreferencesKey("current_geometry")
        val CURRENT_LAYOUT_LANGUAGE = stringPreferencesKey("current_layout_language")
        val LIBRARY_REPO_PATH = stringPreferencesKey("library_repo_path")
        val EXPORT_IMPORT_PATH = stringPreferencesKey("export_import_path")
        val LIBRARY_GIT_FOLDED = booleanPreferencesKey("library_git_folded")
        // The Library git archive's HTTPS remote. Stored in app-private DataStore (this device already has
        // All-Files-Access); the token is a personal-access token used only for clone/pull/push, never on
        // the keyboard hot path or at boot.
        val LIBRARY_REPO_REMOTE = stringPreferencesKey("library_repo_remote")
        val LIBRARY_REPO_USER = stringPreferencesKey("library_repo_user")
        val LIBRARY_REPO_TOKEN = stringPreferencesKey("library_repo_token")
        val CURRENT_KEY_HEIGHT_SCALE = stringPreferencesKey("current_key_height_scale")
        // The last REAL (non-self) app·layout·geometry the keyboard was shown in, so the Keyboard UI "Reset
        // layout to default" button can target the app the user was actually typing in (not the settings app).
        val CURRENT_SIZE_TARGET = stringPreferencesKey("current_size_target")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val VIBRATION_STRENGTH = intPreferencesKey("vibration_strength")
        val DOUBLE_SPACE_PERIOD = booleanPreferencesKey("double_space_period")
        val FORCE_NO_PREDICT = booleanPreferencesKey("force_no_predict")
        val AUTO_CAPITALIZATION_ENABLED = booleanPreferencesKey("auto_capitalization_enabled")
        val SWIPE_ENABLED = booleanPreferencesKey("swipe_enabled")
        val SPACEBAR_CURSOR_CONTROL = booleanPreferencesKey("spacebar_cursor_control")
        val BACKSPACE_SWIPE_DELETE = booleanPreferencesKey("backspace_swipe_delete")
        val LONG_PRESS_PUNCTUATION_MODE = stringPreferencesKey("long_press_punctuation_mode")
        val LONG_PRESS_DURATION = stringPreferencesKey("long_press_duration")
        val SHOW_NUMBER_ROW = booleanPreferencesKey("show_number_row")
        val SPACE_BAR_SIZE = stringPreferencesKey("space_bar_size")
        val KEYBOARD_THEME = stringPreferencesKey("keyboard_theme")
        val KEY_SIZE = stringPreferencesKey("key_size")
        val KEY_LABEL_SIZE = stringPreferencesKey("key_label_size")
        val CURSOR_SPEED = stringPreferencesKey("cursor_speed")
        val FAVORITE_THEMES = stringSetPreferencesKey("favorite_themes")
        val ALTERNATIVE_KEYBOARD_LAYOUT = stringPreferencesKey("alternative_keyboard_layout")
        val ADAPTIVE_KEYBOARD_MODES_ENABLED = booleanPreferencesKey("adaptive_keyboard_modes_enabled")
        val KEYBOARD_DISPLAY_MODE = stringPreferencesKey("keyboard_display_mode")
        val ONE_HANDED_MODE_ENABLED = booleanPreferencesKey("one_handed_mode_enabled")
        val SHOW_LANGUAGE_SWITCH_KEY = booleanPreferencesKey("show_language_switch_key")
        val MERGED_DICTIONARIES = booleanPreferencesKey("merged_dictionaries")
        val PAUSE_ON_MISSPELLED_WORD = booleanPreferencesKey("pause_on_misspelled_word")
        val AUTOCORRECTION_ENABLED = booleanPreferencesKey("autocorrection_enabled")
        val SHOW_NUMBER_HINTS = booleanPreferencesKey("show_number_hints")
        val RESET_TO_LETTERS_ON_DISMISS = booleanPreferencesKey("reset_to_letters_on_dismiss")
        val PRESS_HIGHLIGHT_ENABLED = booleanPreferencesKey("press_highlight_enabled")
        val KEY_PREVIEW_ENABLED = booleanPreferencesKey("key_preview_enabled")
        val CUSTOM_SUGGESTIONS = stringPreferencesKey("custom_suggestions")
        val CUSTOM_SUGGESTIONS_BY_LANG = stringPreferencesKey("custom_suggestions_by_lang")
        val VOICE_USE_ALTERNATE = booleanPreferencesKey("voice_use_alternate")
        val VOICE_AUTO_STOP = booleanPreferencesKey("voice_auto_stop")
        val VOICE_SILENCE_MS = intPreferencesKey("voice_silence_ms")
        val VOICE_TRANSLATE = booleanPreferencesKey("voice_translate")
        val VOICE_AUTO_DETECT = booleanPreferencesKey("voice_auto_detect")
        val VOICE_CONTINUOUS = booleanPreferencesKey("voice_continuous")
        val VOICE_SESSION_END_SEC = intPreferencesKey("voice_session_end_sec")
        val VOICE_BEEPS = booleanPreferencesKey("voice_beeps")
    }

    /** Falls back to system locale defaults on deserialization errors. */
    val settings: Flow<KeyboardSettings> =
        dataStore.data
            .map { preferences ->
                val activeLanguages =
                    preferences[PreferenceKeys.ACTIVE_LANGUAGES_LIST]?.let {
                        if (it.isNotEmpty()) it.split(",").map { lang -> lang.trim() } else null
                    } ?: listOf(preferences[PreferenceKeys.PRIMARY_LANGUAGE] ?: KeyboardSettings.DEFAULT_LANGUAGE)

                val primaryLanguage =
                    preferences[PreferenceKeys.PRIMARY_LANGUAGE] ?: KeyboardSettings.DEFAULT_LANGUAGE

                val primaryLayoutLanguage =
                    preferences[PreferenceKeys.PRIMARY_LAYOUT_LANGUAGE]
                        ?: primaryLanguage

                KeyboardSettings(
                    spellCheckEnabled = preferences[PreferenceKeys.SPELL_CHECK_ENABLED] ?: true,
                    showSuggestions = preferences[PreferenceKeys.SHOW_SUGGESTIONS] ?: true,
                    suggestionCount = preferences[PreferenceKeys.SUGGESTION_COUNT] ?: 3,
                    learnNewWords = preferences[PreferenceKeys.LEARN_NEW_WORDS] ?: true,
                    clipboardEnabled = preferences[PreferenceKeys.CLIPBOARD_ENABLED] ?: true,
                    clipboardConsentShown = preferences[PreferenceKeys.CLIPBOARD_CONSENT_SHOWN] ?: false,
                    activeLanguages = activeLanguages,
                    primaryLanguage = primaryLanguage,
                    primaryLayoutLanguage = primaryLayoutLanguage,
                    hapticFeedback = preferences[PreferenceKeys.HAPTIC_FEEDBACK] ?: true,
                    vibrationStrength = preferences[PreferenceKeys.VIBRATION_STRENGTH] ?: 128,
                    doubleSpacePeriod = preferences[PreferenceKeys.DOUBLE_SPACE_PERIOD] ?: true,
                    forceNoPredict = preferences[PreferenceKeys.FORCE_NO_PREDICT] ?: false,
                    autoCapitalizationEnabled = preferences[PreferenceKeys.AUTO_CAPITALIZATION_ENABLED] ?: true,
                    swipeEnabled = preferences[PreferenceKeys.SWIPE_ENABLED] ?: true,
                    spacebarCursorControl = preferences[PreferenceKeys.SPACEBAR_CURSOR_CONTROL] ?: true,
                    backspaceSwipeDelete = preferences[PreferenceKeys.BACKSPACE_SWIPE_DELETE] ?: true,
                    longPressPunctuationMode =
                    preferences[PreferenceKeys.LONG_PRESS_PUNCTUATION_MODE]?.let {
                        try {
                            LongPressPunctuationMode.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "LONG_PRESS_PUNCTUATION_MODE", "value" to it)
                            )
                            LongPressPunctuationMode.PERIOD
                        }
                    } ?: LongPressPunctuationMode.PERIOD,
                    longPressDuration =
                    preferences[PreferenceKeys.LONG_PRESS_DURATION]?.let {
                        try {
                            LongPressDuration.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "LONG_PRESS_DURATION", "value" to it)
                            )
                            LongPressDuration.MEDIUM
                        }
                    } ?: LongPressDuration.MEDIUM,
                    showNumberRow = preferences[PreferenceKeys.SHOW_NUMBER_ROW] ?: true,
                    spaceBarSize =
                    preferences[PreferenceKeys.SPACE_BAR_SIZE]?.let {
                        try {
                            SpaceBarSize.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "SPACE_BAR_SIZE", "value" to it)
                            )
                            SpaceBarSize.STANDARD
                        }
                    } ?: SpaceBarSize.STANDARD,
                    keySize =
                    preferences[PreferenceKeys.KEY_SIZE]?.let {
                        try {
                            KeySize.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "KEY_SIZE", "value" to it)
                            )
                            KeySize.MEDIUM
                        }
                    } ?: KeySize.MEDIUM,
                    keyLabelSize =
                    preferences[PreferenceKeys.KEY_LABEL_SIZE]?.let {
                        try {
                            KeyLabelSize.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "KEY_LABEL_SIZE", "value" to it)
                            )
                            KeyLabelSize.MEDIUM
                        }
                    } ?: KeyLabelSize.MEDIUM,
                    cursorSpeed =
                    preferences[PreferenceKeys.CURSOR_SPEED]?.let {
                        try {
                            CursorSpeed.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "CURSOR_SPEED", "value" to it)
                            )
                            CursorSpeed.MEDIUM
                        }
                    } ?: CursorSpeed.MEDIUM,
                    keyboardTheme = preferences[PreferenceKeys.KEYBOARD_THEME] ?: "high_contrast_yellow",
                    favoriteThemes = preferences[PreferenceKeys.FAVORITE_THEMES] ?: emptySet(),
                    alternativeKeyboardLayout =
                    preferences[PreferenceKeys.ALTERNATIVE_KEYBOARD_LAYOUT]?.let {
                        try {
                            AlternativeKeyboardLayout.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            ErrorLogger.logException(
                                component = "SettingsRepository",
                                severity = ErrorLogger.Severity.HIGH,
                                exception = e,
                                context = mapOf("key" to "ALTERNATIVE_KEYBOARD_LAYOUT", "value" to it)
                            )
                            AlternativeKeyboardLayout.DEFAULT
                        }
                    } ?: AlternativeKeyboardLayout.DEFAULT,
                    adaptiveKeyboardModesEnabled =
                    preferences[PreferenceKeys.ADAPTIVE_KEYBOARD_MODES_ENABLED] ?: false,
                    keyboardDisplayMode =
                    preferences[PreferenceKeys.KEYBOARD_DISPLAY_MODE]?.let {
                        try {
                            KeyboardDisplayMode.valueOf(it)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    },
                    oneHandedModeEnabled = preferences[PreferenceKeys.ONE_HANDED_MODE_ENABLED] ?: false,
                    showLanguageSwitchKey = preferences[PreferenceKeys.SHOW_LANGUAGE_SWITCH_KEY] ?: false,
                    mergedDictionaries = preferences[PreferenceKeys.MERGED_DICTIONARIES] ?: true,
                    pauseOnMisspelledWord = preferences[PreferenceKeys.PAUSE_ON_MISSPELLED_WORD] ?: true,
                    autocorrectionEnabled = preferences[PreferenceKeys.AUTOCORRECTION_ENABLED] ?: false,
                    showNumberHints = preferences[PreferenceKeys.SHOW_NUMBER_HINTS] ?: false,
                    resetToLettersOnDismiss = preferences[PreferenceKeys.RESET_TO_LETTERS_ON_DISMISS] ?: true,
                    keyPressHighlightEnabled = preferences[PreferenceKeys.PRESS_HIGHLIGHT_ENABLED] ?: true,
                    keyPreviewEnabled = preferences[PreferenceKeys.KEY_PREVIEW_ENABLED] ?: true,
                    customSuggestions =
                        preferences[PreferenceKeys.CUSTOM_SUGGESTIONS]?.takeIf { it.isNotBlank() }
                            ?: KeyboardSettings.DEFAULT_CUSTOM_SUGGESTIONS,
                    customSuggestionsByLang =
                        decodeCustomSuggestionsByLang(preferences[PreferenceKeys.CUSTOM_SUGGESTIONS_BY_LANG]),
                    voiceUseAlternate = preferences[PreferenceKeys.VOICE_USE_ALTERNATE] ?: false,
                    voiceAutoStop = preferences[PreferenceKeys.VOICE_AUTO_STOP] ?: true,
                    voiceSilenceMs = preferences[PreferenceKeys.VOICE_SILENCE_MS] ?: 800,
                    voiceTranslate = preferences[PreferenceKeys.VOICE_TRANSLATE] ?: false,
                    voiceAutoDetect = preferences[PreferenceKeys.VOICE_AUTO_DETECT] ?: false,
                    voiceContinuous = preferences[PreferenceKeys.VOICE_CONTINUOUS] ?: true,
                    voiceSessionEndSec = preferences[PreferenceKeys.VOICE_SESSION_END_SEC] ?: 10,
                    voiceBeeps = preferences[PreferenceKeys.VOICE_BEEPS] ?: true
                ).validated()
            }.catch { e ->
                ErrorLogger.logException(
                    component = "SettingsRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "loadSettings")
                )
                emit(getDefaultSettings())
            }

    suspend fun updateSpellCheckEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SPELL_CHECK_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateShowSuggestions(show: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SHOW_SUGGESTIONS] = show }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateSuggestionCount(count: Int): Result<Unit> = try {
        dataStore.edit {
            it[PreferenceKeys.SUGGESTION_COUNT] =
                count.coerceIn(
                    KeyboardSettings.MIN_SUGGESTION_COUNT,
                    KeyboardSettings.MAX_SUGGESTION_COUNT
                )
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateLearnNewWords(learn: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.LEARN_NEW_WORDS] = learn }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateClipboardEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.CLIPBOARD_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateClipboardConsentShown(shown: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.CLIPBOARD_CONSENT_SHOWN] = shown }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Persists the raw newline-separated custom-suggestion list verbatim (parsing happens at display time). */
    suspend fun updateCustomSuggestions(raw: String): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.CUSTOM_SUGGESTIONS] = raw }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Set one language's custom-suggestion row (stored in the per-language JSON map, keyed by base language). */
    suspend fun updateCustomSuggestionsForLanguage(lang: String, raw: String): Result<Unit> = try {
        dataStore.edit { prefs ->
            val obj = runCatching {
                org.json.JSONObject(prefs[PreferenceKeys.CUSTOM_SUGGESTIONS_BY_LANG] ?: "{}")
            }.getOrDefault(org.json.JSONObject())
            obj.put(lang.substringBefore('-'), raw)
            prefs[PreferenceKeys.CUSTOM_SUGGESTIONS_BY_LANG] = obj.toString()
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun decodeCustomSuggestionsByLang(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            buildMap { obj.keys().forEach { k -> put(k, obj.optString(k)) } }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Filters to supported languages only, de-duplicates, maintains order.
     * Primary layout language must be in active set. Falls back to default if validation fails.
     */
    suspend fun updateActiveLanguages(activeLanguages: List<String>, primaryLayoutLanguage: String): Result<Unit> =
        try {
            val validatedActiveLanguages =
                activeLanguages
                    .filter { it in KeyboardSettings.SUPPORTED_LANGUAGES }
                    .distinct()
                    .take(KeyboardSettings.MAX_ACTIVE_LANGUAGES)
                    .ifEmpty { listOf(KeyboardSettings.DEFAULT_LANGUAGE) }

            val validatedPrimaryLayoutLanguage =
                if (validatedActiveLanguages.contains(primaryLayoutLanguage)) {
                    primaryLayoutLanguage
                } else {
                    validatedActiveLanguages.first()
                }

            dataStore.edit { preferences ->
                preferences[PreferenceKeys.ACTIVE_LANGUAGES_LIST] = validatedActiveLanguages.joinToString(",")
                preferences[PreferenceKeys.PRIMARY_LANGUAGE] = validatedPrimaryLayoutLanguage
                preferences[PreferenceKeys.PRIMARY_LAYOUT_LANGUAGE] = validatedPrimaryLayoutLanguage
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Switches primary layout language without changing active languages set.
     * Layout language must be in active languages. Falls back to first active if invalid.
     */
    suspend fun updatePrimaryLayoutLanguage(primaryLayoutLanguage: String): Result<Unit> = try {
        dataStore.edit { preferences ->
            val activeLanguagesStr = preferences[PreferenceKeys.ACTIVE_LANGUAGES_LIST]
            val activeLanguages =
                activeLanguagesStr?.split(",")?.map { it.trim() }
                    ?: listOf(KeyboardSettings.DEFAULT_LANGUAGE)

            val validatedPrimaryLayoutLanguage =
                if (activeLanguages.contains(primaryLayoutLanguage)) {
                    primaryLayoutLanguage
                } else {
                    activeLanguages.first()
                }

            preferences[PreferenceKeys.PRIMARY_LAYOUT_LANGUAGE] = validatedPrimaryLayoutLanguage
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Per-app layout memory: the layout language last used in each app's package, so it can be
     * restored when the user returns to that app. Stored outside [KeyboardSettings] so writes here
     * don't perturb the settings flow.
     */
    suspend fun getPerAppLayoutLanguage(packageName: String): String? = try {
        if (packageName.isBlank()) {
            null
        } else {
            dataStore.data
                .first()[PreferenceKeys.PER_APP_LAYOUT_LANGUAGES]
                ?.let { decodePerAppLayouts(it)[packageName] }
        }
    } catch (e: Exception) {
        null
    }

    suspend fun setPerAppLayoutLanguage(packageName: String, language: String): Result<Unit> = try {
        if (packageName.isNotBlank() && language.isNotBlank()) {
            dataStore.edit { preferences ->
                val current =
                    preferences[PreferenceKeys.PER_APP_LAYOUT_LANGUAGES]
                        ?.let { decodePerAppLayouts(it) }
                        ?: emptyMap()
                if (current[packageName] != language) {
                    val updated = current.toMutableMap().apply { this[packageName] = language }
                    preferences[PreferenceKeys.PER_APP_LAYOUT_LANGUAGES] = encodePerAppLayouts(updated)
                }
            }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** The active layout id chosen for [language] (1D switcher), or null = use the registry default. */
    suspend fun getActiveLayoutForLanguage(language: String): String? = try {
        if (language.isBlank()) null
        else dataStore.data.first()[PreferenceKeys.ACTIVE_LAYOUT_BY_LANGUAGE]
            ?.let { decodePerAppLayouts(it)[language] }
    } catch (e: Exception) {
        null
    }

    suspend fun setActiveLayoutForLanguage(language: String, layoutId: String): Result<Unit> = try {
        if (language.isNotBlank() && layoutId.isNotBlank()) {
            dataStore.edit { preferences ->
                val current = preferences[PreferenceKeys.ACTIVE_LAYOUT_BY_LANGUAGE]
                    ?.let { decodePerAppLayouts(it) } ?: emptyMap()
                if (current[language] != layoutId) {
                    preferences[PreferenceKeys.ACTIVE_LAYOUT_BY_LANGUAGE] =
                        encodePerAppLayouts(current.toMutableMap().apply { this[language] = layoutId })
                }
            }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun encodePerAppLayouts(map: Map<String, String>): String =
        map.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    private fun decodePerAppLayouts(raw: String): Map<String, String> =
        raw
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                    parts[0] to parts[1]
                } else {
                    null
                }
            }.toMap()

    // --- Per-(language·layout·geometry) look store (plan 1A.2) ---
    // A keyed map "comboKey -> encoded KeyboardLookKnobs", stored outside KeyboardSettings so writes here
    // don't perturb the settings flow. Combo keys are "language|layout|geometry"; a per-geometry BASELINE
    // uses the bare geometry string as its key. Reads resolve DEFAULT -> geometry baseline -> combo fork
    // (nullable-field overlay); writes target either the combo key (an on-keyboard fork, 1C) or the
    // geometry key (the baseline edited in the Keyboard UI screen).

    /** Emits the raw look map whenever it changes, so the service can re-resolve + re-apply live. */
    val perGeometryLook: Flow<String?> =
        dataStore.data
            .map { it[PreferenceKeys.PER_GEOMETRY_LOOK] }
            .distinctUntilChanged()

    /**
     * The DECODED look map (key -> knobs), emitted on change. The IME caches this in memory so it can resolve
     * the per-(app·layout·geometry) size SYNCHRONOUSLY on the show path — an async resolve sized the window a
     * frame late and clipped the keyboard.
     */
    val perGeometryLookMap: Flow<Map<String, KeyboardLookKnobs>> =
        perGeometryLook.map { raw -> raw?.let { decodeLookMap(it) } ?: emptyMap() }

    /**
     * The geometry bucket the running keyboard is currently using, published by the IME service so the
     * Keyboard UI screen can follow it live (e.g. update the selector when the user rotates or folds).
     */
    val currentGeometry: Flow<String?> =
        dataStore.data
            .map { it[PreferenceKeys.CURRENT_GEOMETRY] }
            .distinctUntilChanged()

    suspend fun setCurrentGeometry(geometry: String) {
        try {
            dataStore.edit {
                if (it[PreferenceKeys.CURRENT_GEOMETRY] != geometry) {
                    it[PreferenceKeys.CURRENT_GEOMETRY] = geometry
                }
            }
        } catch (e: Exception) {
            // best-effort; the UI falls back to a Configuration-derived guess
        }
    }

    /** The last REAL app's size-override key (`app|layoutId|geometry`), for the Keyboard UI reset button. */
    val currentSizeTarget: Flow<String?> =
        dataStore.data
            .map { it[PreferenceKeys.CURRENT_SIZE_TARGET] }
            .distinctUntilChanged()

    suspend fun setCurrentSizeTarget(key: String) {
        try {
            dataStore.edit {
                if (it[PreferenceKeys.CURRENT_SIZE_TARGET] != key) {
                    it[PreferenceKeys.CURRENT_SIZE_TARGET] = key
                }
            }
        } catch (e: Exception) {
            // best-effort; the reset button just stays a no-op if this never published
        }
    }

    /**
     * The layout language the running keyboard is currently using, published by the IME service so the
     * settings-side editor entry points (the space-menu / Settings "Edit keyboard" items) can target the
     * keyboard that's actually on screen. Reads fall back to the first active language when unset.
     */
    suspend fun setCurrentLayoutLanguage(language: String) {
        try {
            if (language.isBlank()) return
            dataStore.edit {
                if (it[PreferenceKeys.CURRENT_LAYOUT_LANGUAGE] != language) {
                    it[PreferenceKeys.CURRENT_LAYOUT_LANGUAGE] = language
                }
            }
        } catch (e: Exception) {
            // best-effort; callers fall back to the first active language
        }
    }

    suspend fun getCurrentLayoutLanguage(): String? = try {
        dataStore.data.first()[PreferenceKeys.CURRENT_LAYOUT_LANGUAGE]?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /**
     * The user-set real filesystem path of the Library's optional git archive (All-Files-Access; no SAF).
     * Read/written ONLY in the Library tab — never on the keyboard hot path or at boot. Null/blank → the
     * Library shows the internal store only.
     */
    /** Whether the Library's Git-archive section is collapsed (persisted across sessions). */
    suspend fun getLibraryGitFolded(): Boolean = try {
        dataStore.data.first()[PreferenceKeys.LIBRARY_GIT_FOLDED] ?: false
    } catch (_: Exception) {
        false
    }

    suspend fun setLibraryGitFolded(folded: Boolean) {
        try {
            dataStore.edit { it[PreferenceKeys.LIBRARY_GIT_FOLDED] = folded }
        } catch (_: Exception) {
        }
    }

    suspend fun getLibraryRepoPath(): String? = try {
        dataStore.data.first()[PreferenceKeys.LIBRARY_REPO_PATH]?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    suspend fun setLibraryRepoPath(path: String): Result<Unit> = try {
        dataStore.edit {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) it.remove(PreferenceKeys.LIBRARY_REPO_PATH)
            else it[PreferenceKeys.LIBRARY_REPO_PATH] = trimmed
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** The real filesystem directory (All-Files-Access) backups are written to / read from. Null if unset. */
    suspend fun getExportImportPath(): String? = try {
        dataStore.data.first()[PreferenceKeys.EXPORT_IMPORT_PATH]?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    suspend fun setExportImportPath(path: String): Result<Unit> = try {
        dataStore.edit {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) it.remove(PreferenceKeys.EXPORT_IMPORT_PATH)
            else it[PreferenceKeys.EXPORT_IMPORT_PATH] = trimmed
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Backup-relevant raw DataStore strings, keyed by the public RAW_KEY_* name → the actual key. */
    private val rawBackupKeys: Map<String, Preferences.Key<String>> = mapOf(
        RAW_KEY_PER_GEOMETRY_LOOK to PreferenceKeys.PER_GEOMETRY_LOOK,
        RAW_KEY_LIBRARY_LOOK to PreferenceKeys.LIBRARY_LOOK,
        RAW_KEY_ACTIVE_LAYOUT_BY_LANGUAGE to PreferenceKeys.ACTIVE_LAYOUT_BY_LANGUAGE,
        RAW_KEY_PER_APP_LAYOUT_LANGUAGES to PreferenceKeys.PER_APP_LAYOUT_LANGUAGES,
        RAW_KEY_CUSTOM_SUGGESTIONS to PreferenceKeys.CUSTOM_SUGGESTIONS,
        RAW_KEY_CUSTOM_SUGGESTIONS_BY_LANG to PreferenceKeys.CUSTOM_SUGGESTIONS_BY_LANG
    )

    /**
     * Raw encoded DataStore strings that [exportPreferences] deliberately OMITS but the backup module needs:
     * the per-geometry look blob (colours/sizing), the library look, the active-layout-per-language
     * selection, the per-app layout-language memory, and the custom-suggestions toolbar. Only the [names]
     * requested (a subset of RAW_KEY_*) that are actually present are returned.
     */
    suspend fun exportRawBackupValues(names: Set<String>): Map<String, String> = try {
        val prefs = dataStore.data.first()
        buildMap {
            rawBackupKeys.forEach { (name, key) ->
                if (name in names) prefs[key]?.takeIf { it.isNotBlank() }?.let { put(name, it) }
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    /** Write back raw encoded values produced by [exportRawBackupValues] (unknown names ignored). */
    suspend fun importRawBackupValues(values: Map<String, String>): Result<Unit> = try {
        dataStore.edit { mp ->
            values.forEach { (name, value) -> rawBackupKeys[name]?.let { mp[it] = value } }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * The Library git archive's HTTPS remote (URL + credentials). Read/written ONLY in the Library tab.
     * The token is a personal-access token stored in app-private DataStore (this device already grants the
     * app All-Files-Access); it is used solely for clone/pull/push and never on the keyboard hot path.
     */
    suspend fun getLibraryRepoRemote(): String? = try {
        dataStore.data.first()[PreferenceKeys.LIBRARY_REPO_REMOTE]?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    suspend fun getLibraryRepoUser(): String = try {
        dataStore.data.first()[PreferenceKeys.LIBRARY_REPO_USER] ?: ""
    } catch (e: Exception) {
        ""
    }

    suspend fun getLibraryRepoToken(): String = try {
        dataStore.data.first()[PreferenceKeys.LIBRARY_REPO_TOKEN] ?: ""
    } catch (e: Exception) {
        ""
    }

    suspend fun setLibraryRepoRemote(url: String, user: String, token: String): Result<Unit> = try {
        dataStore.edit {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isEmpty()) it.remove(PreferenceKeys.LIBRARY_REPO_REMOTE)
            else it[PreferenceKeys.LIBRARY_REPO_REMOTE] = trimmedUrl
            val trimmedUser = user.trim()
            if (trimmedUser.isEmpty()) it.remove(PreferenceKeys.LIBRARY_REPO_USER)
            else it[PreferenceKeys.LIBRARY_REPO_USER] = trimmedUser
            // The token may legitimately contain leading/trailing nothing of note; keep it verbatim.
            if (token.isEmpty()) it.remove(PreferenceKeys.LIBRARY_REPO_TOKEN)
            else it[PreferenceKeys.LIBRARY_REPO_TOKEN] = token
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Published by the IME as it resolves its look — the keyHeightScale the live keyboard is actually using
     *  (null = renderer default), so the Library preview can render at the real on-screen height. */
    suspend fun setCurrentKeyHeightScale(scale: Float?) {
        try {
            dataStore.edit {
                if (scale == null) it.remove(PreferenceKeys.CURRENT_KEY_HEIGHT_SCALE)
                else it[PreferenceKeys.CURRENT_KEY_HEIGHT_SCALE] = scale.toString()
            }
        } catch (e: Exception) {
            // best-effort
        }
    }

    suspend fun getCurrentKeyHeightScale(): Float? = try {
        dataStore.data.first()[PreferenceKeys.CURRENT_KEY_HEIGHT_SCALE]?.toFloatOrNull()
    } catch (e: Exception) {
        null
    }

    /**
     * The per-(app·layout·geometry) SIZE-override key — the on-keyboard resize writes the four size knobs
     * (height/width/lift/split) here so each layout's size is remembered per app, per layout, per device
     * geometry. Distinguished from the bare-`geometry` baseline key (which has no separators) by its two `|`.
     */
    private fun sizeOverrideKey(app: String, layoutId: String, geometry: String): String =
        "$app|$layoutId|$geometry"

    /**
     * The effective knob set for a context: DEFAULT overlaid by the per-geometry baseline (the general default
     * + floating position, edited by the Keyboard UI sliders), then — when an [app] is given — by the
     * per-(app·layout·geometry) size override the on-keyboard resize writes. So each layout keeps its own
     * height/width/lift/split per app and geometry, and one resize never bleeds into other layouts. Callers
     * with no app context (Library / editor previews) pass app = null to resolve just the baseline.
     */
    suspend fun resolveLookKnobs(app: String?, layoutId: String, geometry: String): KeyboardLookKnobs = try {
        val map = dataStore.data.first()[PreferenceKeys.PER_GEOMETRY_LOOK]?.let { decodeLookMap(it) } ?: emptyMap()
        var resolved = KeyboardLookKnobs.DEFAULT
        map[geometry]?.let { resolved = resolved.overlay(it) }
        if (!app.isNullOrEmpty()) {
            map[sizeOverrideKey(app, layoutId, geometry)]?.let { resolved = resolved.overlay(it) }
        }
        resolved
    } catch (e: Exception) {
        KeyboardLookKnobs.DEFAULT
    }

    /** The per-geometry baseline as stored (null = unset), for the Keyboard UI screen to read back. */
    /** The app-wide Library-screen look (separator / spacing / per-category fonts). Defaults inherit. */
    suspend fun getLibraryLook(): com.urik.keyboard.service.LibraryLook = try {
        com.urik.keyboard.service.LibraryLook.decode(
            dataStore.data.first()[PreferenceKeys.LIBRARY_LOOK] ?: ""
        )
    } catch (e: Exception) {
        com.urik.keyboard.service.LibraryLook()
    }

    suspend fun updateLibraryLook(look: com.urik.keyboard.service.LibraryLook): Result<Unit> = try {
        dataStore.edit { preferences ->
            val encoded = look.encode()
            if (encoded.isEmpty()) {
                preferences.remove(PreferenceKeys.LIBRARY_LOOK)
            } else {
                preferences[PreferenceKeys.LIBRARY_LOOK] = encoded
            }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getGeometryBaselineLook(geometry: String): KeyboardLookKnobs? = try {
        dataStore.data.first()[PreferenceKeys.PER_GEOMETRY_LOOK]?.let { decodeLookMap(it)[geometry] }
    } catch (e: Exception) {
        null
    }

    suspend fun updateGeometryBaselineLook(geometry: String, knobs: KeyboardLookKnobs): Result<Unit> =
        putLook(geometry, knobs)

    /** The per-(app·layout·geometry) size override as stored (null = none yet), for the resize commit to merge. */
    suspend fun getSizeOverride(app: String, layoutId: String, geometry: String): KeyboardLookKnobs? = try {
        dataStore.data.first()[PreferenceKeys.PER_GEOMETRY_LOOK]
            ?.let { decodeLookMap(it)[sizeOverrideKey(app, layoutId, geometry)] }
    } catch (e: Exception) {
        null
    }

    suspend fun updateSizeOverride(
        app: String,
        layoutId: String,
        geometry: String,
        knobs: KeyboardLookKnobs
    ): Result<Unit> = putLook(sizeOverrideKey(app, layoutId, geometry), knobs)

    /**
     * Per-app "reset layout to default": remove ONLY this (app·layout·geometry) size override, so this app's
     * keyboard falls back to the geometry baseline while every OTHER app keeps the size it was resized to.
     * No-op if there's no override for this combo.
     */
    suspend fun clearSizeOverride(app: String, layoutId: String, geometry: String): Result<Unit> = try {
        dataStore.edit { preferences ->
            val current = preferences[PreferenceKeys.PER_GEOMETRY_LOOK]?.let { decodeLookMap(it) }
            val key = sizeOverrideKey(app, layoutId, geometry)
            if (current != null && current.containsKey(key)) {
                preferences[PreferenceKeys.PER_GEOMETRY_LOOK] = encodeLookMap(current - key)
            }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun putLook(key: String, knobs: KeyboardLookKnobs): Result<Unit> = try {
        dataStore.edit { preferences ->
            val current = preferences[PreferenceKeys.PER_GEOMETRY_LOOK]?.let { decodeLookMap(it) } ?: emptyMap()
            val encoded = knobs.encode()
            val updated = current.toMutableMap()
            if (encoded.isEmpty()) updated.remove(key) else updated[key] = knobs
            preferences[PreferenceKeys.PER_GEOMETRY_LOOK] = encodeLookMap(updated)
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun encodeLookMap(map: Map<String, KeyboardLookKnobs>): String =
        map.entries.joinToString("\n") { "${it.key}\t${it.value.encode()}" }

    private fun decodeLookMap(raw: String): Map<String, KeyboardLookKnobs> =
        raw
            .lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) {
                    null
                } else {
                    val key = line.substring(0, tab)
                    if (key.isEmpty()) null else key to KeyboardLookKnobs.decode(line.substring(tab + 1))
                }
            }.toMap()

    suspend fun updateHapticFeedback(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.HAPTIC_FEEDBACK] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateVibrationStrength(strength: Int): Result<Unit> = try {
        dataStore.edit {
            it[PreferenceKeys.VIBRATION_STRENGTH] = strength.coerceIn(1, 255)
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateDoubleSpacePeriod(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.DOUBLE_SPACE_PERIOD] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateForceNoPredict(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.FORCE_NO_PREDICT] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateAutoCapitalizationEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.AUTO_CAPITALIZATION_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateSwipeEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SWIPE_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateSpacebarCursorControl(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SPACEBAR_CURSOR_CONTROL] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateCursorSpeed(speed: CursorSpeed): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.CURSOR_SPEED] = speed.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateBackspaceSwipeDelete(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.BACKSPACE_SWIPE_DELETE] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateLongPressPunctuationMode(mode: LongPressPunctuationMode): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.LONG_PRESS_PUNCTUATION_MODE] = mode.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateLongPressDuration(duration: LongPressDuration): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.LONG_PRESS_DURATION] = duration.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateShowNumberRow(show: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SHOW_NUMBER_ROW] = show }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateSpaceBarSize(size: SpaceBarSize): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SPACE_BAR_SIZE] = size.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateKeySize(size: KeySize): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.KEY_SIZE] = size.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateKeyLabelSize(size: KeyLabelSize): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.KEY_LABEL_SIZE] = size.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateKeyboardTheme(themeId: String): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.KEYBOARD_THEME] = themeId }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateFavoriteThemes(favorites: Set<String>): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.FAVORITE_THEMES] = favorites }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateAlternativeKeyboardLayout(layout: AlternativeKeyboardLayout): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.ALTERNATIVE_KEYBOARD_LAYOUT] = layout.name }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateAdaptiveKeyboardModesEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.ADAPTIVE_KEYBOARD_MODES_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateKeyboardDisplayMode(mode: KeyboardDisplayMode?): Result<Unit> = try {
        dataStore.edit {
            if (mode == null) {
                it.remove(PreferenceKeys.KEYBOARD_DISPLAY_MODE)
            } else {
                it[PreferenceKeys.KEYBOARD_DISPLAY_MODE] = mode.name
            }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateOneHandedModeEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.ONE_HANDED_MODE_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateShowLanguageSwitchKey(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SHOW_LANGUAGE_SWITCH_KEY] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateMergedDictionaries(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.MERGED_DICTIONARIES] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updatePauseOnMisspelledWord(enabled: Boolean): Result<Unit> = try {
        dataStore.edit {
            it[PreferenceKeys.PAUSE_ON_MISSPELLED_WORD] = enabled
            if (enabled) it[PreferenceKeys.AUTOCORRECTION_ENABLED] = false
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateAutocorrectionEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit {
            it[PreferenceKeys.AUTOCORRECTION_ENABLED] = enabled
            if (enabled) it[PreferenceKeys.PAUSE_ON_MISSPELLED_WORD] = false
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateShowNumberHints(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.SHOW_NUMBER_HINTS] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateResetToLettersOnDismiss(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.RESET_TO_LETTERS_ON_DISMISS] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateKeyPressHighlightEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.PRESS_HIGHLIGHT_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateKeyPreviewEnabled(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.KEY_PREVIEW_ENABLED] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateVoiceUseAlternate(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.VOICE_USE_ALTERNATE] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateVoiceAutoStop(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.VOICE_AUTO_STOP] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateVoiceSilenceMs(ms: Int): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.VOICE_SILENCE_MS] = ms }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateVoiceTranslate(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.VOICE_TRANSLATE] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateVoiceAutoDetect(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.VOICE_AUTO_DETECT] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateVoiceContinuous(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.VOICE_CONTINUOUS] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateVoiceSessionEndSec(sec: Int): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.VOICE_SESSION_END_SEC] = sec }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateVoiceBeeps(enabled: Boolean): Result<Unit> = try {
        dataStore.edit { it[PreferenceKeys.VOICE_BEEPS] = enabled }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Irreversible. Clears all supported languages atomically within a single transaction.
     */
    suspend fun clearLearnedWords(): Result<Unit> = try {
        database.withTransaction {
            val supportedLanguages = KeyboardSettings.SUPPORTED_LANGUAGES
            supportedLanguages.forEach { languageTag ->
                database.learnedWordDao().clearLanguage(languageTag)
            }
            database.userWordFrequencyDao().clearAll()
            database.userWordBigramDao().clearAll()
            database.userKanjiFrequencyDao().clearAll()
        }
        wordFrequencyRepository.clearCache()
        cacheMemoryManager.forceCleanup()
        Result.success(Unit)
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SettingsRepository",
            severity = ErrorLogger.Severity.CRITICAL,
            exception = e,
            context = mapOf("operation" to "clearLearnedWords")
        )
        Result.failure(e)
    }

    /** Does not affect learned words. Use [clearLearnedWords] to remove learned vocabulary. */
    suspend fun resetToDefaults(): Result<Unit> = try {
        dataStore.edit { it.clear() }
        Result.success(Unit)
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SettingsRepository",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("operation" to "resetToDefaults")
        )
        Result.failure(e)
    }

    /**
     * Exports all exportable preferences as a string map. Excludes [PreferenceKeys.CLIPBOARD_CONSENT_SHOWN].
     * Set<String> values are joined with [EXPORT_SET_DELIMITER].
     */
    suspend fun exportPreferences(): Result<Map<String, String>> = try {
        val prefs = dataStore.data.first()
        val map = mutableMapOf<String, String>()

        booleanExportKeys.forEach { key -> prefs[key]?.let { map[key.name] = it.toString() } }
        intExportKeys.forEach { key -> prefs[key]?.let { map[key.name] = it.toString() } }
        stringExportKeys.forEach { key -> prefs[key]?.let { map[key.name] = it } }
        setExportKeys.forEach { key -> prefs[key]?.let { map[key.name] = it.joinToString(EXPORT_SET_DELIMITER) } }

        Result.success(map)
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SettingsRepository",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("operation" to "exportPreferences")
        )
        Result.failure(e)
    }

    /**
     * Imports preferences from string map. Unknown keys are skipped. Only updates keys present
     * in the map — does not clear absent keys. [PreferenceKeys.CLIPBOARD_CONSENT_SHOWN] is ignored.
     */
    suspend fun importPreferences(prefs: Map<String, String>): Result<Unit> = try {
        val boolLookup = booleanExportKeys.associateBy { it.name }
        val intLookup = intExportKeys.associateBy { it.name }
        val stringLookup = stringExportKeys.associateBy { it.name }
        val setLookup = setExportKeys.associateBy { it.name }

        dataStore.edit { mutablePrefs ->
            prefs.forEach { (keyName, value) ->
                when (keyName) {
                    in boolLookup -> boolLookup[keyName]?.let {
                        mutablePrefs[it] = value.toBoolean()
                    }

                    in intLookup -> intLookup[keyName]?.let {
                        value.toIntOrNull()?.let { v -> mutablePrefs[it] = v }
                    }

                    in setLookup -> setLookup[keyName]?.let {
                        mutablePrefs[it] = if (value.isEmpty()) {
                            emptySet()
                        } else {
                            value.split(EXPORT_SET_DELIMITER).toSet()
                        }
                    }

                    in stringLookup -> stringLookup[keyName]?.let {
                        mutablePrefs[it] = value
                    }
                }
            }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SettingsRepository",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("operation" to "importPreferences")
        )
        Result.failure(e)
    }

    private fun getDefaultSettings(): KeyboardSettings {
        val systemLanguage = Locale.getDefault().language
        return KeyboardSettings.defaultForLocale(systemLanguage)
    }

    companion object {
        const val EXPORT_SET_DELIMITER = ","

        // Public names for the raw backup-state DataStore values (see exportRawBackupValues). Stable strings —
        // they appear verbatim in exported backups, so do not rename.
        const val RAW_KEY_PER_GEOMETRY_LOOK = "per_geometry_look"
        const val RAW_KEY_LIBRARY_LOOK = "library_look"
        const val RAW_KEY_ACTIVE_LAYOUT_BY_LANGUAGE = "active_layout_by_language"
        const val RAW_KEY_PER_APP_LAYOUT_LANGUAGES = "per_app_layout_languages"
        const val RAW_KEY_CUSTOM_SUGGESTIONS = "custom_suggestions"
        const val RAW_KEY_CUSTOM_SUGGESTIONS_BY_LANG = "custom_suggestions_by_lang"

        internal val booleanExportKeys: List<Preferences.Key<Boolean>> = listOf(
            PreferenceKeys.SHOW_SUGGESTIONS,
            PreferenceKeys.SPELL_CHECK_ENABLED,
            PreferenceKeys.LEARN_NEW_WORDS,
            PreferenceKeys.CLIPBOARD_ENABLED,
            PreferenceKeys.HAPTIC_FEEDBACK,
            PreferenceKeys.DOUBLE_SPACE_PERIOD,
            PreferenceKeys.FORCE_NO_PREDICT,
            PreferenceKeys.AUTO_CAPITALIZATION_ENABLED,
            PreferenceKeys.SWIPE_ENABLED,
            PreferenceKeys.SPACEBAR_CURSOR_CONTROL,
            PreferenceKeys.BACKSPACE_SWIPE_DELETE,
            PreferenceKeys.SHOW_NUMBER_ROW,
            PreferenceKeys.ADAPTIVE_KEYBOARD_MODES_ENABLED,
            PreferenceKeys.ONE_HANDED_MODE_ENABLED,
            PreferenceKeys.SHOW_LANGUAGE_SWITCH_KEY,
            PreferenceKeys.MERGED_DICTIONARIES,
            PreferenceKeys.PAUSE_ON_MISSPELLED_WORD,
            PreferenceKeys.AUTOCORRECTION_ENABLED,
            PreferenceKeys.SHOW_NUMBER_HINTS,
            PreferenceKeys.RESET_TO_LETTERS_ON_DISMISS,
            PreferenceKeys.PRESS_HIGHLIGHT_ENABLED,
            PreferenceKeys.KEY_PREVIEW_ENABLED
        )

        internal val intExportKeys: List<Preferences.Key<Int>> = listOf(
            PreferenceKeys.SUGGESTION_COUNT,
            PreferenceKeys.VIBRATION_STRENGTH
        )

        internal val stringExportKeys: List<Preferences.Key<String>> = listOf(
            PreferenceKeys.ACTIVE_LANGUAGES_LIST,
            PreferenceKeys.PRIMARY_LANGUAGE,
            PreferenceKeys.PRIMARY_LAYOUT_LANGUAGE,
            PreferenceKeys.LONG_PRESS_PUNCTUATION_MODE,
            PreferenceKeys.LONG_PRESS_DURATION,
            PreferenceKeys.SPACE_BAR_SIZE,
            PreferenceKeys.KEYBOARD_THEME,
            PreferenceKeys.KEY_SIZE,
            PreferenceKeys.KEY_LABEL_SIZE,
            PreferenceKeys.CURSOR_SPEED,
            PreferenceKeys.ALTERNATIVE_KEYBOARD_LAYOUT,
            PreferenceKeys.KEYBOARD_DISPLAY_MODE
        )

        internal val setExportKeys: List<Preferences.Key<Set<String>>> = listOf(
            PreferenceKeys.FAVORITE_THEMES
        )
    }
}
