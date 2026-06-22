package com.urik.keyboard.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Interprets the special-key shortcuts re-derived from the futokxkb / Multiling toolbar (and any layout key
 * that carries one), so they DO their thing instead of being inserted as literal text:
 *
 *  - **Cursor pairs** — `X…Y` (the `…` U+2026 is a cursor sentinel): insert `XY` and leave the cursor BETWEEN
 *    them. E.g. `“…”`, `(…)`, `[…]`, `{…}`, `«…»`. A bare `…` (no other char) stays a literal ellipsis.
 *  - **Action tokens** — `[Paste]` / `[Copy]` / `[Cut]` / `[All]` / `[Tab]`: a clipboard / editor action.
 *  - **Date templates** — `{{` + a [SimpleDateFormat] pattern (e.g. `{{yyyy-MM-dd `): the formatted now.
 *  - **Anything else** — committed verbatim (the plain literals `+ - * # :@) ☺ …`).
 *
 * The Multiling multi-step macro syntax (`[MC:…]`) is intentionally NOT handled here — it falls through to a
 * literal commit for now.
 */
object SpecialTokens {
    sealed interface Effect {
        /** Commit [prefix] then [suffix]; with a non-empty [suffix] the cursor is left between the two. */
        data class Commit(val prefix: String, val suffix: String = "") : Effect

        /** A named editor action routed through the service's action dispatcher (paste/copy/cut/…). */
        data class Action(val name: String) : Effect
    }

    /** Bracketed `[Name]` → the canonical action name the service understands. */
    private val ACTIONS = mapOf(
        "paste" to "paste",
        "copy" to "copy",
        "cut" to "cut",
        "all" to "select_all",
        "selectall" to "select_all",
        "tab" to "tab"
    )

    private val ACTION_RE = Regex("""\[(\w+)]""")
    private const val CURSOR = '…'

    /**
     * True when [token] does something other than commit its own literal text — used to decide whether to
     * intercept a tapped toolbar entry / key. Deliberately cheap (no [Date] allocation, no formatting).
     */
    fun isSpecial(token: String): Boolean = when {
        token.length > 2 && token.startsWith("{{") -> true
        ACTION_RE.matchEntire(token)?.let { ACTIONS.containsKey(it.groupValues[1].lowercase()) } == true -> true
        token.length >= 2 && token.indexOf(CURSOR) >= 0 -> true
        else -> false
    }

    fun interpret(token: String, now: Date = Date()): Effect {
        // {{<SimpleDateFormat pattern> — the rest of the token is the pattern (a trailing literal is fine).
        if (token.length > 2 && token.startsWith("{{")) {
            val pattern = token.substring(2)
            return try {
                Effect.Commit(SimpleDateFormat(pattern, Locale.getDefault()).format(now))
            } catch (_: IllegalArgumentException) {
                Effect.Commit(token)
            }
        }
        // [Name] action — must be the whole token (so `[…]` / `[MC:…]` don't match and stay text).
        ACTION_RE.matchEntire(token)?.let { m ->
            ACTIONS[m.groupValues[1].lowercase()]?.let { return Effect.Action(it) }
        }
        // X…Y cursor pair — split on the first sentinel; needs a char on at least one side.
        val cut = token.indexOf(CURSOR)
        if (cut >= 0) {
            val prefix = token.substring(0, cut)
            val suffix = token.substring(cut + 1)
            if (prefix.isNotEmpty() || suffix.isNotEmpty()) return Effect.Commit(prefix, suffix)
        }
        return Effect.Commit(token)
    }
}
