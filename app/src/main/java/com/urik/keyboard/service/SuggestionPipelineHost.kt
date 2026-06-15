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
}
