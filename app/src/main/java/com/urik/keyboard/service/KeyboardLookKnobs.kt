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
    /**
     * Per-edge-row height multipliers (1.0 = unchanged) — scale ONLY the first row (the number/function row)
     * and the last row (the space/bottom row) relative to the letter rows, like futokxkb's
     * top/bottom row height factors. null = inherit (no change), so existing looks render identically.
     */
    val topRowHeightScale: Float? = null,
    val bottomRowHeightScale: Float? = null,
    /** Multipliers on the inter-key gap, per axis (1.0 = unchanged, 0 = no gap — keys abut into a grid). */
    val keySpacingHScale: Float? = null,
    val keySpacingVScale: Float? = null,
    /** Multiplier on the secondary / flick-hint label size (1.0 = unchanged) — the base for both rows. */
    val hintScale: Float? = null,
    /** General secondary-character colour / font / weight (the rows inherit these unless they override). */
    val hintColor: Int? = null,
    val hintFont: String? = null,
    val hintWeight: Int? = null,
    /** Fraction of the available width the keyboard occupies (1.0 = full width; <1 narrows, centred). */
    val keyboardWidthScale: Float? = null,
    /**
     * Split-keyboard gap: 0 = no split (whole keyboard), >0 splits each non-spacebar row in the middle with
     * a see-through gap of `splitFraction * MAX_SPLIT_GAP_DP` dp. Odd rows duplicate the middle column.
     */
    val splitFraction: Float? = null,
    /** Lift the keyboard off the bottom edge by this many dp (0 = docked). */
    val bottomLiftDp: Float? = null,
    /** Key-label font family: "" = system, "@monospace", or an imported font file name. See KeyboardFonts. */
    val fontFamily: String? = null,
    /** Per-geometry colour overrides (ARGB Int; null = use the active theme's colour). */
    val keyboardBgColor: Int? = null,
    val keyBgColor: Int? = null,
    /** Background for FUNCTIONAL keys (shift/backspace/enter/space/mode-switch). null = inherit [keyBgColor]. */
    val functionalKeyBgColor: Int? = null,
    val keyTextColor: Int? = null,
    val keyBorderColor: Int? = null,
    /** Colour the Shift icon turns when caps lock is on (null = the key text colour). */
    val capsLockShiftColor: Int? = null,
    /** Key-label font weight 100..900 (null = derive from boldKeyLabels; 0 = the family's own weight). */
    val labelWeight: Int? = null,
    // --- Secondary-character rows (compass / cluster flick hints). Top row = up / up-diagonals,
    //     bottom row = down / down-diagonals, with left/right on the centre line. Each row formats
    //     independently: colour (ARGB; null = dimmed key text), size scale (× the global hint size),
    //     font family, and distance (dp inset from that edge). ---
    val hintTopColor: Int? = null,
    val hintTopScale: Float? = null,
    val hintTopFont: String? = null,
    val hintTopMarginDp: Float? = null,
    val hintBottomColor: Int? = null,
    val hintBottomScale: Float? = null,
    val hintBottomFont: String? = null,
    val hintBottomMarginDp: Float? = null,
    /** Horizontal insets (dp): the left column (left side + left diagonals) and the right column. */
    val hintLeftMarginDp: Float? = null,
    val hintRightMarginDp: Float? = null,
    /** Cluster-key main-character horizontal offsets (dp), left and right main, individually. */
    val clusterLeftOffsetDp: Float? = null,
    val clusterRightOffsetDp: Float? = null,
    // --- Suggestion / candidate bar ---
    val suggestionBarHeightScale: Float? = null,
    val suggestionBgColor: Int? = null,
    val suggestionFont: String? = null,
    val suggestionWeight: Int? = null,
    val suggestionTextScale: Float? = null,
    val suggestionColor: Int? = null,
    // --- Floating-keyboard panel rect (used only in FLOATING display mode; persisted per geometry).
    //     x/y are fractions [0..1] of the free travel (left/top edge .. right/bottom edge) so the rect
    //     survives density/orientation reasonably; width is a fraction [0..1] of screen width; height is
    //     a multiplier on the keyboard's natural height. null = use the centred ~85% default the first time. ---
    val floatXFraction: Float? = null,
    val floatYFraction: Float? = null,
    val floatWidthFraction: Float? = null,
    val floatHeightScale: Float? = null
) {
    fun applyTo(base: AdaptiveDimensions, density: Float): AdaptiveDimensions = base.copy(
        keyHeightPx = keyHeightScale?.let { (base.keyHeightPx * it).toInt().coerceAtLeast(1) } ?: base.keyHeightPx,
        topRowHeightScale = topRowHeightScale ?: base.topRowHeightScale,
        bottomRowHeightScale = bottomRowHeightScale ?: base.bottomRowHeightScale,
        splitGapPx =
            splitFraction?.let { (it.coerceAtLeast(0f) * MAX_SPLIT_GAP_DP * density).toInt() } ?: base.splitGapPx,
        keyMarginHorizontalPx =
            keySpacingHScale?.let { (base.keyMarginHorizontalPx * it).toInt().coerceAtLeast(0) }
                ?: base.keyMarginHorizontalPx,
        keyMarginVerticalPx =
            keySpacingVScale?.let { (base.keyMarginVerticalPx * it).toInt().coerceAtLeast(0) }
                ?: base.keyMarginVerticalPx,
        cornerRadiusPx = cornerRadiusDp?.let { (it * density).toInt().coerceAtLeast(0) } ?: base.cornerRadiusPx,
        keyBorderWidthPx = keyBorderWidthDp?.let { (it * density).toInt().coerceAtLeast(0) } ?: base.keyBorderWidthPx,
        boldKeyLabels = boldKeyLabels ?: base.boldKeyLabels,
        keyFontScale = keyFontScale ?: base.keyFontScale,
        hintScale = hintScale ?: base.hintScale,
        hintColor = hintColor ?: base.hintColor,
        hintFont = hintFont ?: base.hintFont,
        hintWeight = hintWeight ?: base.hintWeight,
        fontFamily = fontFamily ?: base.fontFamily,
        keyboardBgColor = keyboardBgColor ?: base.keyboardBgColor,
        keyBgColor = keyBgColor ?: base.keyBgColor,
        functionalKeyBgColor = functionalKeyBgColor ?: base.functionalKeyBgColor,
        keyTextColor = keyTextColor ?: base.keyTextColor,
        keyBorderColor = keyBorderColor ?: base.keyBorderColor,
        capsLockShiftColor = capsLockShiftColor ?: base.capsLockShiftColor,
        labelWeight = labelWeight ?: base.labelWeight,
        hintTopColor = hintTopColor ?: base.hintTopColor,
        hintTopScale = hintTopScale ?: base.hintTopScale,
        hintTopFont = hintTopFont ?: base.hintTopFont,
        hintTopMarginPx = hintTopMarginDp?.let { (it * density).toInt().coerceAtLeast(0) } ?: base.hintTopMarginPx,
        hintBottomColor = hintBottomColor ?: base.hintBottomColor,
        hintBottomScale = hintBottomScale ?: base.hintBottomScale,
        hintBottomFont = hintBottomFont ?: base.hintBottomFont,
        hintBottomMarginPx = hintBottomMarginDp?.let { (it * density).toInt().coerceAtLeast(0) } ?: base.hintBottomMarginPx,
        hintLeftMarginPx = hintLeftMarginDp?.let { (it * density).toInt().coerceAtLeast(0) } ?: base.hintLeftMarginPx,
        hintRightMarginPx = hintRightMarginDp?.let { (it * density).toInt().coerceAtLeast(0) } ?: base.hintRightMarginPx,
        clusterLeftOffsetPx = clusterLeftOffsetDp?.let { (it * density).toInt() } ?: base.clusterLeftOffsetPx,
        clusterRightOffsetPx = clusterRightOffsetDp?.let { (it * density).toInt() } ?: base.clusterRightOffsetPx,
        suggestionBarHeightPx =
            suggestionBarHeightScale?.let { (base.suggestionBarHeightPx * it).toInt().coerceAtLeast(1) }
                ?: base.suggestionBarHeightPx,
        suggestionBgColor = suggestionBgColor ?: base.suggestionBgColor,
        suggestionFont = suggestionFont ?: base.suggestionFont,
        suggestionWeight = suggestionWeight ?: base.suggestionWeight,
        suggestionTextScale = suggestionTextScale ?: base.suggestionTextScale,
        suggestionColor = suggestionColor ?: base.suggestionColor
    )

    /** Returns a new set where [o]'s set (non-null) fields win and this set fills the gaps. */
    fun overlay(o: KeyboardLookKnobs): KeyboardLookKnobs = KeyboardLookKnobs(
        cornerRadiusDp = o.cornerRadiusDp ?: cornerRadiusDp,
        keyBorderWidthDp = o.keyBorderWidthDp ?: keyBorderWidthDp,
        boldKeyLabels = o.boldKeyLabels ?: boldKeyLabels,
        keyFontScale = o.keyFontScale ?: keyFontScale,
        keyHeightScale = o.keyHeightScale ?: keyHeightScale,
        topRowHeightScale = o.topRowHeightScale ?: topRowHeightScale,
        bottomRowHeightScale = o.bottomRowHeightScale ?: bottomRowHeightScale,
        keySpacingHScale = o.keySpacingHScale ?: keySpacingHScale,
        keySpacingVScale = o.keySpacingVScale ?: keySpacingVScale,
        hintScale = o.hintScale ?: hintScale,
        hintColor = o.hintColor ?: hintColor,
        hintFont = o.hintFont ?: hintFont,
        hintWeight = o.hintWeight ?: hintWeight,
        keyboardWidthScale = o.keyboardWidthScale ?: keyboardWidthScale,
        splitFraction = o.splitFraction ?: splitFraction,
        bottomLiftDp = o.bottomLiftDp ?: bottomLiftDp,
        fontFamily = o.fontFamily ?: fontFamily,
        keyboardBgColor = o.keyboardBgColor ?: keyboardBgColor,
        keyBgColor = o.keyBgColor ?: keyBgColor,
        functionalKeyBgColor = o.functionalKeyBgColor ?: functionalKeyBgColor,
        keyTextColor = o.keyTextColor ?: keyTextColor,
        keyBorderColor = o.keyBorderColor ?: keyBorderColor,
        capsLockShiftColor = o.capsLockShiftColor ?: capsLockShiftColor,
        labelWeight = o.labelWeight ?: labelWeight,
        hintTopColor = o.hintTopColor ?: hintTopColor,
        hintTopScale = o.hintTopScale ?: hintTopScale,
        hintTopFont = o.hintTopFont ?: hintTopFont,
        hintTopMarginDp = o.hintTopMarginDp ?: hintTopMarginDp,
        hintBottomColor = o.hintBottomColor ?: hintBottomColor,
        hintBottomScale = o.hintBottomScale ?: hintBottomScale,
        hintBottomFont = o.hintBottomFont ?: hintBottomFont,
        hintBottomMarginDp = o.hintBottomMarginDp ?: hintBottomMarginDp,
        hintLeftMarginDp = o.hintLeftMarginDp ?: hintLeftMarginDp,
        hintRightMarginDp = o.hintRightMarginDp ?: hintRightMarginDp,
        clusterLeftOffsetDp = o.clusterLeftOffsetDp ?: clusterLeftOffsetDp,
        clusterRightOffsetDp = o.clusterRightOffsetDp ?: clusterRightOffsetDp,
        suggestionBarHeightScale = o.suggestionBarHeightScale ?: suggestionBarHeightScale,
        suggestionBgColor = o.suggestionBgColor ?: suggestionBgColor,
        suggestionFont = o.suggestionFont ?: suggestionFont,
        suggestionWeight = o.suggestionWeight ?: suggestionWeight,
        suggestionTextScale = o.suggestionTextScale ?: suggestionTextScale,
        suggestionColor = o.suggestionColor ?: suggestionColor,
        floatXFraction = o.floatXFraction ?: floatXFraction,
        floatYFraction = o.floatYFraction ?: floatYFraction,
        floatWidthFraction = o.floatWidthFraction ?: floatWidthFraction,
        floatHeightScale = o.floatHeightScale ?: floatHeightScale
    )

    /** Compact `k=v;` encoding; null fields are omitted. Pairs with [decode] (lenient). */
    fun encode(): String = buildList {
        cornerRadiusDp?.let { add("cr=$it") }
        keyBorderWidthDp?.let { add("bw=$it") }
        boldKeyLabels?.let { add("bold=${if (it) 1 else 0}") }
        keyFontScale?.let { add("fs=$it") }
        keyHeightScale?.let { add("hs=$it") }
        topRowHeightScale?.let { add("trh=$it") }
        bottomRowHeightScale?.let { add("brh=$it") }
        keySpacingHScale?.let { add("ksh=$it") }
        keySpacingVScale?.let { add("ksv=$it") }
        hintScale?.let { add("hn=$it") }
        hintColor?.let { add("hc=$it") }
        hintFont?.let { add("hf=$it") }
        hintWeight?.let { add("hw=$it") }
        keyboardWidthScale?.let { add("kw=$it") }
        splitFraction?.let { add("spf=$it") }
        bottomLiftDp?.let { add("bl=$it") }
        fontFamily?.let { add("ff=$it") }
        keyboardBgColor?.let { add("cbg=$it") }
        keyBgColor?.let { add("kbg=$it") }
        functionalKeyBgColor?.let { add("fkbg=$it") }
        keyTextColor?.let { add("ktx=$it") }
        keyBorderColor?.let { add("kbr=$it") }
        capsLockShiftColor?.let { add("clc=$it") }
        labelWeight?.let { add("lw=$it") }
        hintTopColor?.let { add("htc=$it") }
        hintTopScale?.let { add("hts=$it") }
        hintTopFont?.let { add("htf=$it") }
        hintTopMarginDp?.let { add("htm=$it") }
        hintBottomColor?.let { add("hbc=$it") }
        hintBottomScale?.let { add("hbs=$it") }
        hintBottomFont?.let { add("hbf=$it") }
        hintBottomMarginDp?.let { add("hbm=$it") }
        hintLeftMarginDp?.let { add("hlm=$it") }
        hintRightMarginDp?.let { add("hrm=$it") }
        clusterLeftOffsetDp?.let { add("cll=$it") }
        clusterRightOffsetDp?.let { add("clr=$it") }
        suggestionBarHeightScale?.let { add("sbh=$it") }
        suggestionBgColor?.let { add("sbg=$it") }
        suggestionFont?.let { add("sbf=$it") }
        suggestionWeight?.let { add("sbw=$it") }
        suggestionTextScale?.let { add("sbs=$it") }
        suggestionColor?.let { add("sbc=$it") }
        floatXFraction?.let { add("flx=$it") }
        floatYFraction?.let { add("fly=$it") }
        floatWidthFraction?.let { add("flw=$it") }
        floatHeightScale?.let { add("flh=$it") }
    }.joinToString(";")

    companion object {
        /** Split slider 100% → this many dp of see-through gap between the two halves. */
        const val MAX_SPLIT_GAP_DP = 200f

        /**
         * 白い熊's signature look: square keys + bold labels (yellow-on-black supplies the contrast via
         * [com.urik.keyboard.theme.HighContrastYellow]). Split starts at 0 (whole keyboard). Border width and
         * the scale knobs stay at the renderer default until tuned. The seed beneath every geometry's baseline.
         */
        val DEFAULT = KeyboardLookKnobs(
            cornerRadiusDp = 0f,
            boldKeyLabels = true,
            splitFraction = 0f
        )

        /** Lenient inverse of [encode]; unknown/garbled tokens are ignored (= inherit). */
        fun decode(raw: String): KeyboardLookKnobs {
            var cr: Float? = null
            var bw: Float? = null
            var bold: Boolean? = null
            var fs: Float? = null
            var hs: Float? = null
            var trh: Float? = null
            var brh: Float? = null
            var ksh: Float? = null
            var ksv: Float? = null
            var hn: Float? = null
            var kw: Float? = null
            var spf: Float? = null
            var bl: Float? = null
            var ff: String? = null
            var cbg: Int? = null
            var kbg: Int? = null
            var fkbg: Int? = null
            var ktx: Int? = null
            var kbr: Int? = null
            var clc: Int? = null
            var lw: Int? = null
            var htc: Int? = null
            var hts: Float? = null
            var htf: String? = null
            var htm: Float? = null
            var hbc: Int? = null
            var hbs: Float? = null
            var hbf: String? = null
            var hbm: Float? = null
            var hlm: Float? = null
            var hrm: Float? = null
            var cll: Float? = null
            var clr: Float? = null
            var sbh: Float? = null
            var sbg: Int? = null
            var sbf: String? = null
            var sbw: Int? = null
            var sbs: Float? = null
            var sbc: Int? = null
            var hc: Int? = null
            var hf: String? = null
            var hw: Int? = null
            var flx: Float? = null
            var fly: Float? = null
            var flw: Float? = null
            var flh: Float? = null
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
                    "trh" -> trh = value.toFloatOrNull()
                    "brh" -> brh = value.toFloatOrNull()
                    "ks" -> value.toFloatOrNull()?.let { ksh = it; ksv = it } // legacy: one spacing -> both axes
                    "ksh" -> ksh = value.toFloatOrNull()
                    "ksv" -> ksv = value.toFloatOrNull()
                    "hn" -> hn = value.toFloatOrNull()
                    "kw" -> kw = value.toFloatOrNull()
                    "spf" -> spf = value.toFloatOrNull()
                    "bl" -> bl = value.toFloatOrNull()
                    "ff" -> ff = value
                    "cbg" -> cbg = value.toIntOrNull()
                    "kbg" -> kbg = value.toIntOrNull()
                    "fkbg" -> fkbg = value.toIntOrNull()
                    "ktx" -> ktx = value.toIntOrNull()
                    "kbr" -> kbr = value.toIntOrNull()
                    "clc" -> clc = value.toIntOrNull()
                    "lw" -> lw = value.toIntOrNull()
                    "htc" -> htc = value.toIntOrNull()
                    "hts" -> hts = value.toFloatOrNull()
                    "htf" -> htf = value
                    "htm" -> htm = value.toFloatOrNull()
                    "hbc" -> hbc = value.toIntOrNull()
                    "hbs" -> hbs = value.toFloatOrNull()
                    "hbf" -> hbf = value
                    "hbm" -> hbm = value.toFloatOrNull()
                    "hsm" -> value.toFloatOrNull()?.let { hlm = it; hrm = it } // legacy: one side inset -> both
                    "hlm" -> hlm = value.toFloatOrNull()
                    "hrm" -> hrm = value.toFloatOrNull()
                    "cll" -> cll = value.toFloatOrNull()
                    "clr" -> clr = value.toFloatOrNull()
                    "sbh" -> sbh = value.toFloatOrNull()
                    "sbg" -> sbg = value.toIntOrNull()
                    "sbf" -> sbf = value
                    "sbw" -> sbw = value.toIntOrNull()
                    "sbs" -> sbs = value.toFloatOrNull()
                    "sbc" -> sbc = value.toIntOrNull()
                    "hc" -> hc = value.toIntOrNull()
                    "hf" -> hf = value
                    "hw" -> hw = value.toIntOrNull()
                    "flx" -> flx = value.toFloatOrNull()
                    "fly" -> fly = value.toFloatOrNull()
                    "flw" -> flw = value.toFloatOrNull()
                    "flh" -> flh = value.toFloatOrNull()
                }
            }
            return KeyboardLookKnobs(
                cornerRadiusDp = cr, keyBorderWidthDp = bw, boldKeyLabels = bold, keyFontScale = fs,
                keyHeightScale = hs, topRowHeightScale = trh, bottomRowHeightScale = brh,
                keySpacingHScale = ksh, keySpacingVScale = ksv, hintScale = hn,
                hintColor = hc, hintFont = hf, hintWeight = hw,
                keyboardWidthScale = kw, splitFraction = spf, bottomLiftDp = bl, fontFamily = ff,
                keyboardBgColor = cbg, keyBgColor = kbg, functionalKeyBgColor = fkbg,
                keyTextColor = ktx, keyBorderColor = kbr,
                capsLockShiftColor = clc, labelWeight = lw,
                hintTopColor = htc, hintTopScale = hts, hintTopFont = htf, hintTopMarginDp = htm,
                hintBottomColor = hbc, hintBottomScale = hbs, hintBottomFont = hbf, hintBottomMarginDp = hbm,
                hintLeftMarginDp = hlm, hintRightMarginDp = hrm,
                clusterLeftOffsetDp = cll, clusterRightOffsetDp = clr,
                suggestionBarHeightScale = sbh, suggestionBgColor = sbg, suggestionFont = sbf,
                suggestionWeight = sbw, suggestionTextScale = sbs, suggestionColor = sbc,
                floatXFraction = flx, floatYFraction = fly,
                floatWidthFraction = flw, floatHeightScale = flh
            )
        }
    }
}
