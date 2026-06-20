package com.urik.keyboard.service

import java.text.Normalizer

/**
 * Combining "dead key" support for the diacritic keys (Czech ´ acute / ˇ caron, plus ¨ diaeresis, ˚ ring,
 * ¯ macron). Typing a diacritic ARMS a pending combining mark instead of emitting it; the next base letter
 * composes with it via Unicode NFC — ˇ then c → č, ´ then e → é, ˚ then u → ů, ¨ then o → ö. A base with no
 * precomposed form (or a non-letter) ends the dead key without composing.
 *
 * Stateless/pure: the pending-mark state lives in the caller (KeyEventRouter); this just maps and composes.
 */
object DeadKeys {

    // Spacing diacritic (as it appears on a key) → its combining counterpart (U+03xx).
    private val COMBINING: Map<Char, Char> = mapOf(
        '´' to '́', // ´ acute     → á é í ó ú ý ć ń ŕ ĺ ś ź …
        'ˇ' to '̌', // ˇ caron     → č ě ř š ž ď ť ň ľ …
        '¨' to '̈', // ¨ diaeresis → ä ë ï ö ü ÿ
        '˚' to '̊', // ˚ ring above→ ů å
        '¯' to '̄'  // ¯ macron    → ā ē ī ō ū
    )
    private val SPACING: Map<Char, Char> = COMBINING.entries.associate { (spacing, combining) -> combining to spacing }

    /** The combining mark a dead-key diacritic arms, or null if [s] isn't a dead-key diacritic. */
    fun combiningFor(s: String): Char? = if (s.length == 1) COMBINING[s[0]] else null

    /** The spacing diacritic to emit literally when an armed dead key cannot combine (inverse of arming). */
    fun spacingFor(combining: Char): String = SPACING[combining]?.toString() ?: ""

    /**
     * Compose [base] with the armed [combining] mark via NFC. Returns the single precomposed character
     * (e.g. "č", "Á"), or null when there is no precomposed form (the caller then falls back to the spacing
     * diacritic + the base). Uppercase bases compose too (C + caron → Č), so shift works naturally.
     */
    fun compose(base: String, combining: Char): String? {
        if (base.length != 1) return null
        val composed = Normalizer.normalize("$base$combining", Normalizer.Form.NFC)
        return if (composed.length == 1 && composed[0] != base[0]) composed else null
    }
}
