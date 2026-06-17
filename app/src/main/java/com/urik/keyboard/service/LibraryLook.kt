package com.urik.keyboard.service

/**
 * App-wide look of the Library screen — separator colour/thickness, inter-row spacing, the layout indent,
 * and per-category (language heading / layout name / badge) font, weight, size and colour. Nullable fields
 * inherit the [Companion] defaults (白い熊's HighContrastYellow). Stored as one encoded string in the
 * settings store; edited field-by-field on the kxkb UI page, read by the Library screen.
 */
data class LibraryLook(
    val separatorColor: Int? = null,
    val separatorThicknessDp: Int? = null,
    val rowSpacingDp: Int? = null,
    val indentDp: Int? = null,
    val headingFont: String? = null,
    val headingWeight: Int? = null,
    val headingSizeSp: Int? = null,
    val headingColor: Int? = null,
    val nameFont: String? = null,
    val nameWeight: Int? = null,
    val nameSizeSp: Int? = null,
    val nameColor: Int? = null,
    val badgeFont: String? = null,
    val badgeWeight: Int? = null,
    val badgeSizeSp: Int? = null,
    val badgeColor: Int? = null
) {
    fun encode(): String = buildList {
        separatorColor?.let { add("sc=$it") }
        separatorThicknessDp?.let { add("st=$it") }
        rowSpacingDp?.let { add("rs=$it") }
        indentDp?.let { add("in=$it") }
        headingFont?.let { add("hf=$it") }
        headingWeight?.let { add("hw=$it") }
        headingSizeSp?.let { add("hz=$it") }
        headingColor?.let { add("hc=$it") }
        nameFont?.let { add("nf=$it") }
        nameWeight?.let { add("nw=$it") }
        nameSizeSp?.let { add("nz=$it") }
        nameColor?.let { add("nc=$it") }
        badgeFont?.let { add("bf=$it") }
        badgeWeight?.let { add("bw=$it") }
        badgeSizeSp?.let { add("bz=$it") }
        badgeColor?.let { add("bc=$it") }
    }.joinToString(";")

    companion object {
        const val DEF_SEPARATOR_COLOR = 0xFFFFFF00.toInt()
        const val DEF_SEPARATOR_THICKNESS = 1
        const val DEF_ROW_SPACING = 3
        const val DEF_INDENT = 48
        const val DEF_HEADING_COLOR = 0xFFFFFF00.toInt()
        const val DEF_HEADING_WEIGHT = 700
        const val DEF_HEADING_SIZE = 22
        const val DEF_NAME_COLOR = 0xFFFFF176.toInt()
        const val DEF_NAME_WEIGHT = 400
        const val DEF_NAME_SIZE = 18
        const val DEF_BADGE_COLOR = 0xFFBDB76B.toInt()
        const val DEF_BADGE_WEIGHT = 400
        const val DEF_BADGE_SIZE = 13

        /** Lenient inverse of [encode]; unknown/garbled tokens are ignored (= inherit the default). */
        fun decode(raw: String): LibraryLook {
            var sc: Int? = null
            var st: Int? = null
            var rs: Int? = null
            var indent: Int? = null
            var hf: String? = null
            var hw: Int? = null
            var hz: Int? = null
            var hc: Int? = null
            var nf: String? = null
            var nw: Int? = null
            var nz: Int? = null
            var nc: Int? = null
            var bf: String? = null
            var bw: Int? = null
            var bz: Int? = null
            var bc: Int? = null
            raw.split(";").forEach { token ->
                val i = token.indexOf('=')
                if (i <= 0) return@forEach
                val k = token.substring(0, i)
                val v = token.substring(i + 1)
                when (k) {
                    "sc" -> sc = v.toIntOrNull()
                    "st" -> st = v.toIntOrNull()
                    "rs" -> rs = v.toIntOrNull()
                    "in" -> indent = v.toIntOrNull()
                    "hf" -> hf = v
                    "hw" -> hw = v.toIntOrNull()
                    "hz" -> hz = v.toIntOrNull()
                    "hc" -> hc = v.toIntOrNull()
                    "nf" -> nf = v
                    "nw" -> nw = v.toIntOrNull()
                    "nz" -> nz = v.toIntOrNull()
                    "nc" -> nc = v.toIntOrNull()
                    "bf" -> bf = v
                    "bw" -> bw = v.toIntOrNull()
                    "bz" -> bz = v.toIntOrNull()
                    "bc" -> bc = v.toIntOrNull()
                }
            }
            return LibraryLook(sc, st, rs, indent, hf, hw, hz, hc, nf, nw, nz, nc, bf, bw, bz, bc)
        }
    }
}
