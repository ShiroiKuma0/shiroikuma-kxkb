package com.urik.keyboard.service

import com.urik.keyboard.model.KeyboardState

interface SuggestionPipelineHost {
    fun showSuggestions(): Boolean
    fun effectiveSuggestionCount(): Int
    fun getKeyboardState(): KeyboardState
    fun shouldAutoCapitalize(text: String): Boolean
    fun currentLanguage(): String

    /** The active layout/keyboard language, which can differ from [currentLanguage] (the primary). */
    fun currentLayoutLanguage(): String

    /**
     * The label for the trailing "register this reading" affordance shown at the end of the Japanese
     * candidate row while a reading is composing (e.g. "＋登録"). Tapping it opens the reading→surface
     * registration dialog instead of committing. (Japanese FIX 2.)
     */
    fun japaneseRegisterLabel(): String = "＋登録"
}
