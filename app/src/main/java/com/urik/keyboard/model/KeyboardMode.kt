package com.urik.keyboard.model

import com.urik.keyboard.service.AdaptiveDimensions

enum class KeyboardDisplayMode {
    STANDARD,
    ONE_HANDED_LEFT,
    ONE_HANDED_RIGHT,
    SPLIT,

    /**
     * Floating keyboard: a movable, resizable panel drawn at a persisted on-screen rect (NOT full-width
     * docked). Like [SPLIT] it's an explicit mode chosen from the Mode picker; the input view stays full
     * height but transparent except the panel, and `onComputeInsets` exposes only the panel rect as
     * touchable so the app behind stays interactive. The rect is persisted per geometry in the look store.
     */
    FLOATING
}

data class KeyboardModeConfig(
    val mode: KeyboardDisplayMode,
    val widthFactor: Float = 1.0f,
    val offsetX: Float = 0f,
    val splitGapPx: Int = 0,
    val adaptiveDimensions: AdaptiveDimensions? = null
) {
    companion object {
        private const val ONE_HANDED_WIDTH_FACTOR = 0.90f

        fun standard() = KeyboardModeConfig(
            mode = KeyboardDisplayMode.STANDARD,
            widthFactor = 1.0f,
            offsetX = 0f
        )

        fun floating() = KeyboardModeConfig(
            mode = KeyboardDisplayMode.FLOATING,
            widthFactor = 1.0f,
            offsetX = 0f
        )

        fun oneHandedLeft() = KeyboardModeConfig(
            mode = KeyboardDisplayMode.ONE_HANDED_LEFT,
            widthFactor = ONE_HANDED_WIDTH_FACTOR,
            offsetX = 0f
        )

        fun oneHandedRight(screenWidth: Int) = KeyboardModeConfig(
            mode = KeyboardDisplayMode.ONE_HANDED_RIGHT,
            widthFactor = ONE_HANDED_WIDTH_FACTOR,
            offsetX = screenWidth * (1f - ONE_HANDED_WIDTH_FACTOR)
        )
    }
}
