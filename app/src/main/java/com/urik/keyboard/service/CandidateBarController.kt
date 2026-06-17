package com.urik.keyboard.service

import android.view.View
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.ui.keyboard.components.SwipeKeyboardView

class CandidateBarController(private val viewProvider: () -> SwipeKeyboardView?) {
    fun updateSuggestions(suggestions: List<String>) {
        viewProvider()?.updateSuggestions(suggestions)
    }

    fun clearSuggestions() {
        viewProvider()?.clearSuggestions()
    }

    /** Move the cluster-candidate highlight (Tab) without rebuilding the bar. */
    fun setSelectedSuggestion(index: Int) {
        viewProvider()?.setSelectedSuggestion(index)
    }

    /** Toggle the Space-commits-candidate highlight (on for cluster layouts). */
    fun setSuggestionSelectionEnabled(enabled: Boolean) {
        viewProvider()?.setSuggestionSelectionEnabled(enabled)
    }

    fun showDegradedIndicator(degraded: Boolean) {
        viewProvider()?.showDegradedIndicator(degraded)
    }

    fun forceClearAllSuggestions() {
        viewProvider()?.forceClearAllSuggestions()
    }

    fun updateInlineAutofillSuggestions(views: List<View>, animate: Boolean) {
        viewProvider()?.updateInlineAutofillSuggestions(views, animate)
    }

    fun hideEmojiPicker() {
        viewProvider()?.hideEmojiPicker()
    }

    fun showEmojiPicker() {
        viewProvider()?.showEmojiPicker()
    }

    fun handleSearchInput(key: KeyboardKey): Boolean = viewProvider()?.handleSearchInput(key) ?: false

    fun clearAutofillIfShowing(): Boolean = viewProvider()?.clearAutofillIfShowing() ?: false
}
