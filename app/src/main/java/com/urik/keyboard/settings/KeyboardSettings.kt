package com.urik.keyboard.settings

import android.icu.util.ULocale
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.settings.KeyboardSettings.Companion.DEFAULT_CUSTOM_SUGGESTIONS
import com.urik.keyboard.settings.KeyboardSettings.Companion.DEFAULT_LANGUAGE

enum class KeySize(val displayNameRes: Int, val scaleFactor: Float) {
    SMALL(R.string.key_size_small, 0.85f),
    MEDIUM(R.string.key_size_medium, 1.0f),
    LARGE(R.string.key_size_large, 1.15f),
    EXTRA_LARGE(R.string.key_size_extra_large, 1.35f)
}

enum class LongPressDuration(val displayNameRes: Int, val durationMs: Long) {
    SHORT(R.string.long_press_duration_short, 300L),
    MEDIUM(R.string.long_press_duration_medium, 500L),
    LONG(R.string.long_press_duration_long, 800L)
}

enum class SpaceBarSize(val displayNameRes: Int, val widthMultiplier: Float) {
    COMPACT(R.string.space_bar_size_compact, 3.0f),
    STANDARD(R.string.space_bar_size_standard, 4.0f),
    WIDE(R.string.space_bar_size_wide, 5.0f)
}

enum class KeyLabelSize(val displayNameRes: Int, val scaleFactor: Float) {
    SMALL(R.string.key_label_size_small, 0.85f),
    MEDIUM(R.string.key_label_size_medium, 1.0f),
    LARGE(R.string.key_label_size_large, 1.2f)
}

enum class CursorSpeed(val displayNameRes: Int, val sensitivityDp: Float) {
    SLOW(R.string.cursor_speed_slow, 40f),
    MEDIUM(R.string.cursor_speed_medium, 25f),
    FAST(R.string.cursor_speed_fast, 15f),
    VERY_FAST(R.string.cursor_speed_very_fast, 8f)
}

enum class LongPressPunctuationMode(val displayNameRes: Int) {
    OFF(R.string.long_press_punctuation_off),
    SPACEBAR(R.string.long_press_punctuation_spacebar),
    PERIOD(R.string.long_press_punctuation_period)
}

/**
 * Alternative keyboard layout styles.
 *
 * Determines which physical key arrangement to use. Character variations
 * and spell checking always use the user's selected language regardless
 * of layout choice.
 */
enum class AlternativeKeyboardLayout(val displayNameRes: Int) {
    DEFAULT(R.string.alternative_layout_default),
    QWERTY(R.string.alternative_layout_qwerty),
    AZERTY(R.string.alternative_layout_azerty),
    QWERTZ(R.string.alternative_layout_qwertz),
    DVORAK(R.string.alternative_layout_dvorak),
    COLEMAK(R.string.alternative_layout_colemak),
    WORKMAN(R.string.alternative_layout_workman),
    HCESAR(R.string.alternative_layout_hcesar),
    BDS(R.string.alternative_layout_bds)
}

/**
 * Keyboard configuration and user preferences.
 *
 * Direct construction bypasses validation. SettingsRepository enforces validation
 * at persistence boundaries via [validated]. Test code may construct directly.
 */
data class KeyboardSettings(
    val spellCheckEnabled: Boolean = true,
    val showSuggestions: Boolean = true,
    val suggestionCount: Int = 3,
    val learnNewWords: Boolean = true,
    val clipboardEnabled: Boolean = true,
    val clipboardConsentShown: Boolean = false,
    val activeLanguages: List<String> = listOf(DEFAULT_LANGUAGE),
    val primaryLanguage: String = DEFAULT_LANGUAGE,
    val primaryLayoutLanguage: String = DEFAULT_LANGUAGE,
    val hapticFeedback: Boolean = true,
    val vibrationStrength: Int = 128,
    val doubleSpacePeriod: Boolean = true,
    val forceNoPredict: Boolean = false,
    val autoCapitalizationEnabled: Boolean = true,
    val swipeEnabled: Boolean = true,
    val spacebarCursorControl: Boolean = true,
    val backspaceSwipeDelete: Boolean = true,
    val longPressPunctuationMode: LongPressPunctuationMode = LongPressPunctuationMode.PERIOD,
    val longPressDuration: LongPressDuration = LongPressDuration.MEDIUM,
    val showNumberRow: Boolean = true,
    val spaceBarSize: SpaceBarSize = SpaceBarSize.STANDARD,
    val keySize: KeySize = KeySize.MEDIUM,
    val keyLabelSize: KeyLabelSize = KeyLabelSize.MEDIUM,
    val cursorSpeed: CursorSpeed = CursorSpeed.MEDIUM,
    val keyboardTheme: String = "high_contrast_yellow",
    val favoriteThemes: Set<String> = emptySet(),
    val alternativeKeyboardLayout: AlternativeKeyboardLayout = AlternativeKeyboardLayout.DEFAULT,
    val adaptiveKeyboardModesEnabled: Boolean = false,
    val keyboardDisplayMode: KeyboardDisplayMode? = null,
    val oneHandedModeEnabled: Boolean = false,
    val showLanguageSwitchKey: Boolean = false,
    val mergedDictionaries: Boolean = true,
    val pauseOnMisspelledWord: Boolean = true,
    val autocorrectionEnabled: Boolean = false,
    val showNumberHints: Boolean = false,
    val resetToLettersOnDismiss: Boolean = true,
    val keyPressHighlightEnabled: Boolean = true,
    /** Magnified key-preview bubble above a pressed character key. Default ON. */
    val keyPreviewEnabled: Boolean = true,
    /**
     * Legacy single custom-suggestion row, newline-separated. Retained as English's value (and the migration
     * source) now that the row is per-language — see [customSuggestionsByLang]. Empty = English row off.
     */
    val customSuggestions: String = DEFAULT_CUSTOM_SUGGESTIONS,
    /**
     * Per-language custom-suggestion overrides (`language → newline-separated raw row`). A language absent
     * here uses its built-in default ([com.urik.keyboard.service.CustomSuggestionDefaults]); an explicit
     * empty string means the user cleared that language's row.
     */
    val customSuggestionsByLang: Map<String, String> = emptyMap(),
    /**
     * Whisper voice input. Dictation follows the keyboard's layout language (GNU counts as
     * English); a long-press on the mic key quick-flips to the pair's OTHER language — English,
     * or Czech when the primary already is English. This is that flip's persisted state.
     */
    val voiceUseAlternate: Boolean = false,
    /** End the utterance automatically after [voiceSilenceMs] of post-speech silence (WebRTC VAD). */
    val voiceAutoStop: Boolean = true,
    val voiceSilenceMs: Int = 800,
    /** Whisper's built-in translate-to-English action instead of same-language transcription. */
    val voiceTranslate: Boolean = false,
    /** Let Whisper auto-detect the spoken language instead of following the keyboard. */
    val voiceAutoDetect: Boolean = false,
    /**
     * Continuous dictation: every VAD end-of-utterance commits that sentence and the mic keeps
     * listening (decoding runs in parallel), until a mic tap or [voiceSessionEndSec] of silence.
     * Only effective with [voiceAutoStop].
     */
    val voiceContinuous: Boolean = true,
    val voiceSessionEndSec: Int = 10,
    /** Dictation beeps: one when a pause commits the sentence, three when the session ends. */
    val voiceBeeps: Boolean = true
) {
    /**
     * Whether word learning is enabled via [learnNewWords] flag.
     */
    val isWordLearningEnabled: Boolean
        get() = learnNewWords

    /**
     * Whether clipboard monitoring is fully active (enabled AND user consented).
     */
    val isClipboardFullyActive: Boolean
        get() = clipboardEnabled && clipboardConsentShown

    /** Returns 0 if suggestions are disabled. */
    val effectiveSuggestionCount: Int
        get() = if (showSuggestions) suggestionCount else 0

    /** Returns 0 if haptic feedback is disabled. */
    val effectiveVibrationAmplitude: Int
        get() = if (hapticFeedback) vibrationStrength else 0

    /**
     * Filters languages to the supported set, de-duplicates, clamps suggestion count,
     * ensures primary language and layout language are active. Falls back to
     * [DEFAULT_LANGUAGE] if validation fails.
     */
    fun validated(): KeyboardSettings {
        val validActiveLanguages =
            activeLanguages
                .filter { it in SUPPORTED_LANGUAGES }
                .distinct()
                .take(MAX_ACTIVE_LANGUAGES)
                .ifEmpty { listOf(DEFAULT_LANGUAGE) }

        val validPrimaryLanguage =
            if (validActiveLanguages.contains(primaryLanguage)) {
                primaryLanguage
            } else {
                validActiveLanguages.first()
            }

        val validPrimaryLayoutLanguage =
            if (validActiveLanguages.contains(primaryLayoutLanguage)) {
                primaryLayoutLanguage
            } else {
                validActiveLanguages.first()
            }

        return copy(
            suggestionCount = suggestionCount.coerceIn(MIN_SUGGESTION_COUNT, MAX_SUGGESTION_COUNT),
            activeLanguages = validActiveLanguages,
            primaryLanguage = validPrimaryLanguage,
            primaryLayoutLanguage = validPrimaryLayoutLanguage
        )
    }

    companion object {
        const val MIN_SUGGESTION_COUNT = 1
        const val MAX_SUGGESTION_COUNT = 3

        const val DEFAULT_LANGUAGE = "en"

        /**
         * Default custom-suggestion list, newline-separated, re-derived in spirit from the design-
         * reference fork's Multiling-style "topBar" default set (short symbols, bracket/quote pairs,
         * emoji and date stamps). Used as the out-of-the-box value so a fresh install shows a useful
         * row; an explicit empty string (the user cleared the list) is honoured and stays empty.
         */
        val DEFAULT_CUSTOM_SUGGESTIONS: String =
            listOf(
                "+", "-", "*", "#", "“…”", "\"…\"", "(…)", "[Paste]", ":@)", "[…]",
                "{{yyyy-MM-dd ", "☺", "❤", "♡ ", "{…}", "{{yyyy-MM-dd_HH-mm-ss"
            ).joinToString("\n")

        /**
         * Languages with full keyboard layout, dictionary, and localization support.
         */
        val SUPPORTED_LANGUAGES =
            setOf(
                "ar", "ca", "cs", "de", "el", "en", "es", "fa", "fr", "it",
                "bg", "ja", "nl", "pl", "pt", "ru", "sk", "sv", "uk",
                // GNU: a no-prediction compass "code mode" pseudo-language (no dictionary).
                "gnu"
            )

        /**
         * Maximum number of simultaneously-active languages. The fork removes
         * upstream's hard cap of 3 so the must-have set (cs/en/ru/ja) and beyond
         * can coexist; the only real bound is the number of supported languages,
         * since an unsupported language can never become active.
         */
        val MAX_ACTIVE_LANGUAGES = SUPPORTED_LANGUAGES.size

        /** Display names are in the system's current display locale and capitalized. */
        fun getLanguageDisplayNames(): Map<String, String> {
            val displayLocale = ULocale.getDefault()
            return SUPPORTED_LANGUAGES.associateWith { languageCode ->
                // GNU is a no-prediction "code mode" pseudo-language, not a real locale.
                if (languageCode == "gnu") return@associateWith "GNU"
                val displayName =
                    ULocale
                        .forLanguageTag(languageCode)
                        .getDisplayName(displayLocale)

                if (displayName.isNullOrEmpty() || displayName.length <= 2) {
                    java.util.Locale
                        .forLanguageTag(languageCode)
                        .getDisplayLanguage(java.util.Locale.getDefault())
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                } else {
                    displayName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
            }
        }

        /** Falls back to [DEFAULT_LANGUAGE] for unsupported locales. */
        fun defaultForLocale(languageCode: String): KeyboardSettings {
            val lang = if (languageCode in SUPPORTED_LANGUAGES) languageCode else DEFAULT_LANGUAGE

            return KeyboardSettings(
                activeLanguages = listOf(lang),
                primaryLanguage = lang,
                primaryLayoutLanguage = lang
            )
        }
    }
}
