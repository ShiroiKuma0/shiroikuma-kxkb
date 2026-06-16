package com.urik.keyboard.service

/**
 * Per-geometry "look" overrides that ride the single [AdaptiveDimensions] delivery seam.
 *
 * Each field is nullable — null means "inherit" (keep whatever the base dimensions / the renderer
 * default already provides). [applyTo] overlays the set fields onto a base [AdaptiveDimensions];
 * unset fields pass through untouched. Values are density-independent (dp / unitless scale) so a
 * single saved knob set renders identically across screens; [applyTo] resolves them to px using the
 * caller's `density`.
 *
 * This is the backbone struct for the per-(language·layout·geometry) look store (plan 1A): the live
 * watcher resolves the active knob set and overlays it here, the Keyboard UI sliders edit it, and the
 * resize gesture writes it. For now only the render-look fields are wired; sizing knobs (height, lift,
 * width-scale, split) join as the store and the editor land in 1A.2.
 */
data class KeyboardLookKnobs(
    /** Key corner radius in dp. 0 = square keys. */
    val cornerRadiusDp: Float? = null,
    /** Key border stroke width in dp (the focused/activated stroke is drawn at 2×). */
    val keyBorderWidthDp: Float? = null,
    /** Draw key labels with a bold typeface. */
    val boldKeyLabels: Boolean? = null,
    /** Multiplier on the computed key-label text size (1.0 = unchanged). */
    val keyFontScale: Float? = null
) {
    fun applyTo(base: AdaptiveDimensions, density: Float): AdaptiveDimensions = base.copy(
        cornerRadiusPx = cornerRadiusDp?.let { (it * density).toInt().coerceAtLeast(0) } ?: base.cornerRadiusPx,
        keyBorderWidthPx = keyBorderWidthDp?.let { (it * density).toInt().coerceAtLeast(1) } ?: base.keyBorderWidthPx,
        boldKeyLabels = boldKeyLabels ?: base.boldKeyLabels,
        keyFontScale = keyFontScale ?: base.keyFontScale
    )

    companion object {
        /**
         * 白い熊's signature look: square keys + bold labels (yellow-on-black already supplies the
         * high contrast via [com.urik.keyboard.theme.HighContrastYellow]). Border width and font scale
         * stay at the renderer default until tuned through the Keyboard UI sliders (1A.2). Geometry/
         * theme-independent — it is the seed for every geometry's baseline knob set.
         */
        val DEFAULT = KeyboardLookKnobs(
            cornerRadiusDp = 0f,
            boldKeyLabels = true
        )
    }
}
