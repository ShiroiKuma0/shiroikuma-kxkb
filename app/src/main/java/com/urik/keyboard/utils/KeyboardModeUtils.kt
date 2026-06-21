package com.urik.keyboard.utils

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.urik.keyboard.model.KeyboardMode

object KeyboardModeUtils {
    fun isNumberInputType(editorInfo: EditorInfo?): Boolean {
        val inputType = editorInfo?.inputType ?: return false
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        return inputClass == InputType.TYPE_CLASS_NUMBER
    }

    fun isTextClassInput(editorInfo: EditorInfo?): Boolean {
        val inputType = editorInfo?.inputType ?: return false
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        return inputClass == InputType.TYPE_CLASS_TEXT
    }

    fun shouldResetToLettersOnEnter(currentMode: KeyboardMode, editorInfo: EditorInfo?): Boolean =
        currentMode != KeyboardMode.LETTERS && isTextClassInput(editorInfo)

    fun determineTargetMode(editorInfo: EditorInfo?, currentMode: KeyboardMode): KeyboardMode {
        // A number-entry field must NOT auto-switch to the NUMBERS page (the "sym"-style layout):
        // the user types numbers from the main LETTERS layout. We only drop back to LETTERS when we
        // somehow arrive in NUMBERS, which is now reachable solely via the manual mode-switch key.
        return if (currentMode == KeyboardMode.NUMBERS) KeyboardMode.LETTERS else currentMode
    }
}
