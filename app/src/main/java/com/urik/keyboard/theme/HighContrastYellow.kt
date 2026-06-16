package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

/**
 * 白い熊's signature high-contrast look: pure yellow on black, yellow key borders forming a grid.
 * The square keys / bold labels / thicker borders / bigger letters come from the per-geometry look
 * knobs (corner radius ~0, bold weight, border width, font scale); this theme supplies the colours.
 */
data object HighContrastYellow : KeyboardTheme {
    override val id = "high_contrast_yellow"
    override val displayName = "High Contrast Yellow"
    override val colors =
        ThemeColors(
            keyboardBackground = "#000000".toColorInt(),
            keyBackgroundCharacter = "#000000".toColorInt(),
            keyBackgroundAction = "#000000".toColorInt(),
            keyBackgroundSpace = "#000000".toColorInt(),
            keyTextCharacter = "#FFFF00".toColorInt(),
            keyTextAction = "#FFFF00".toColorInt(),
            keyBorder = "#FFFF00".toColorInt(),
            keyBorderFocused = "#FFFFFF".toColorInt(),
            keyBorderPressed = "#FFFFFF".toColorInt(),
            statePressed = "#332b00".toColorInt(),
            stateActivated = "#4d4000".toColorInt(),
            stateCapsLock = "#4d4000".toColorInt(),
            suggestionBarBackground = "#000000".toColorInt(),
            suggestionText = "#FFFF00".toColorInt(),
            keyShadow = 0x00000000,
            focusIndicator = "#FFFF00".toColorInt(),
            swipePrimary = "#FFFF00".toColorInt(),
            swipeSecondary = "#FFAA00".toColorInt(),
            swipeCurrent = "#FFFFFF".toColorInt()
        )
}
