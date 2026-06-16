package com.urik.keyboard.service

/**
 * Per-geometry "look" overrides that ride the single [AdaptiveDimensions] delivery seam.
 *
 * Each field is nullable — null means "inherit" (keep whatever the layer beneath, or the renderer default,
 * already provides). [applyTo] overlays the set fields onto a base [AdaptiveDimensions]; [overlay] composes
 * two knob sets (the resolution order is default → per-geometry baseline → per-combo fork, each overriding
 * the last). Values are density-independent (dp / unitless scale) so one saved set renders identically
 * across screens; [applyTo] resolves them to px using the caller's `density`.
 *
 * Backbone struct for the per-(language·layout·geometry) look store (plan 1A.2). The live watcher resolves
 * the active set and overlays it here, the Keyboard UI sliders edit the per-geometry baseline, and the
 * on-keyboard resize gesture (1C) writes a per-combo fork.
 */
data class KeyboardLookKnobs(
    /** Key corner radius in dp. 0 = square keys. */
    val cornerRadiusDp: Float? = null,
    /** Key border stroke width in dp (the focused/activated stroke is drawn at 2×). */
    val keyBorderWidthDp: Float? = null,
    /** Draw key labels with a bold typeface. */
    val boldKeyLabels: Boolean? = null,
    /** Multiplier on the computed key-label text size (1.0 = unchanged). */
    val keyFontScale: Float? = null,
    /** Multiplier on the key height (1.0 = unchanged). */
    val keyHeightScale: Float? = null,
    /** Multiplier on the inter-key spacing / gap (1.0 = unchanged, 0 = no gaps — keys abut into a grid). */
    val keySpacingScale: Float? = null,
    /** Multiplier on the secondary / flick-hint label size (1.0 = unchanged). */
    val hintScale: Float? = null,
    /** Fraction of the available width the keyboard occupies (1.0 = full width; <1 narrows, centred). */
    val keyboardWidthScale: Float? = null,
    /** Lift the keyboard off the bottom edge by this many dp (0 = docked). */
    val bottomLiftDp: Float? = null,
    /** Key-label font family: "" = system, "@monospace", or an imported font file name. See KeyboardFonts. */
    val fontFamily: String? = null
) {
    fun applyTo(base: AdaptiveDimensions, density: Float): AdaptiveDimensions = base.copy(
        keyHeightPx = keyHeightScale?.let { (base.keyHeightPx * it).toInt().coerceAtLeast(1) } ?: base.keyHeightPx,
        keyMarginHorizontalPx =
            keySpacingScale?.let { (base.keyMarginHorizontalPx * it).toInt().coerceAtLeast(0) }
                ?: base.keyMarginHorizontalPx,
        keyMarginVerticalPx =
            keySpacingScale?.let { (base.keyMarginVerticalPx * it).toInt().coerceAtLeast(0) }
                ?: base.keyMarginVerticalPx,
        cornerRadiusPx = cornerRadiusDp?.let { (it * density).toInt().coerceAtLeast(0) } ?: base.cornerRadiusPx,
        keyBorderWidthPx = keyBorderWidthDp?.let { (it * density).toInt().coerceAtLeast(0) } ?: base.keyBorderWidthPx,
        boldKeyLabels = boldKeyLabels ?: base.boldKeyLabels,
        keyFontScale = keyFontScale ?: base.keyFontScale,
        hintScale = hintScale ?: base.hintScale,
        fontFamily = fontFamily ?: base.fontFamily
    )

    /** Returns a new set where [o]'s set (non-null) fields win and this set fills the gaps. */
    fun overlay(o: KeyboardLookKnobs): KeyboardLookKnobs = KeyboardLookKnobs(
        cornerRadiusDp = o.cornerRadiusDp ?: cornerRadiusDp,
        keyBorderWidthDp = o.keyBorderWidthDp ?: keyBorderWidthDp,
        boldKeyLabels = o.boldKeyLabels ?: boldKeyLabels,
        keyFontScale = o.keyFontScale ?: keyFontScale,
        keyHeightScale = o.keyHeightScale ?: keyHeightScale,
        keySpacingScale = o.keySpacingScale ?: keySpacingScale,
        hintScale = o.hintScale ?: hintScale,
        keyboardWidthScale = o.keyboardWidthScale ?: keyboardWidthScale,
        bottomLiftDp = o.bottomLiftDp ?: bottomLiftDp,
        fontFamily = o.fontFamily ?: fontFamily
    )

    /** Compact `k=v;` encoding; null fields are omitted. Pairs with [decode] (lenient). */
    fun encode(): String = buildList {
        cornerRadiusDp?.let { add("cr=$it") }
        keyBorderWidthDp?.let { add("bw=$it") }
        boldKeyLabels?.let { add("bold=${if (it) 1 else 0}") }
        keyFontScale?.let { add("fs=$it") }
        keyHeightScale?.let { add("hs=$it") }
        keySpacingScale?.let { add("ks=$it") }
        hintScale?.let { add("hn=$it") }
        keyboardWidthScale?.let { add("kw=$it") }
        bottomLiftDp?.let { add("bl=$it") }
        fontFamily?.let { add("ff=$it") }
    }.joinToString(";")

    companion object {
        /**
         * 白い熊's signature look: square keys + bold labels (yellow-on-black supplies the contrast via
         * [com.urik.keyboard.theme.HighContrastYellow]). Border width and the scale knobs stay at the
         * renderer default until tuned. The seed beneath every geometry's baseline.
         */
        val DEFAULT = KeyboardLookKnobs(
            cornerRadiusDp = 0f,
            boldKeyLabels = true
        )

        /** Lenient inverse of [encode]; unknown/garbled tokens are ignored (= inherit). */
        fun decode(raw: String): KeyboardLookKnobs {
            var cr: Float? = null
            var bw: Float? = null
            var bold: Boolean? = null
            var fs: Float? = null
            var hs: Float? = null
            var ks: Float? = null
            var hn: Float? = null
            var kw: Float? = null
            var bl: Float? = null
            var ff: String? = null
            for (token in raw.split(";")) {
                val i = token.indexOf('=')
                if (i <= 0) continue
                val value = token.substring(i + 1)
                when (token.substring(0, i)) {
                    "cr" -> cr = value.toFloatOrNull()
                    "bw" -> bw = value.toFloatOrNull()
                    "bold" -> bold = value.toIntOrNull()?.let { it != 0 }
                    "fs" -> fs = value.toFloatOrNull()
                    "hs" -> hs = value.toFloatOrNull()
                    "ks" -> ks = value.toFloatOrNull()
                    "hn" -> hn = value.toFloatOrNull()
                    "kw" -> kw = value.toFloatOrNull()
                    "bl" -> bl = value.toFloatOrNull()
                    "ff" -> ff = value
                }
            }
            return KeyboardLookKnobs(cr, bw, bold, fs, hs, ks, hn, kw, bl, ff)
        }
    }
}
