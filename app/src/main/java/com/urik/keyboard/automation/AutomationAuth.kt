package com.urik.keyboard.automation

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The external-automation gate: a master switch, and a token that is now OPTIONAL.
 *
 * ## What changed in contract v2, and why (白い熊, 2026-09-04)
 *
 * v1 shipped this app **closed**: [enabled] defaulted to false and every automation request also had to carry
 * a 48-character secret 白い熊 had pasted from this app's settings into the caller's. That is the wrong shape
 * for where the family is going — **a pasted secret cannot survive a wipe**, and the case the automation now
 * exists to serve is 応用管理 restoring apps *and their data* onto a clean phone, where nothing has been
 * configured and nobody has pasted anything. A gate that only works once the phone is already set up is no
 * gate for setting the phone up.
 *
 * So [enabled] defaults to **true** and [requireToken] is a new, separate switch defaulting to **false**. The
 * token still exists, still regenerates, still never leaves the phone — it is simply opt-in.
 *
 * ## Idempotent about the token — required, not a nicety
 *
 * **A token handed to an app that does not require one is IGNORED, never an error.** Tokens live in task
 * arguments and workspace variables that outlive the setting they were pasted for; a caller still sending one
 * — because it was configured last year, or because another app on the batch does want one — must be served.
 * Refusing it would turn "白い熊 turned a switch off" into "half the batch mysteriously fails".
 *
 * ## Device-local by design
 *
 * The prefs file is its OWN SharedPreferences file, and the backup engine ([com.urik.keyboard.service.BackupManager])
 * exports DataStore preferences, Room rows and custom layouts only — never this file. No automation setting,
 * least of all the token, travels in an export ZIP or reaches another phone.
 *
 * The identity of a caller at the **data door** is a separate question with a separate answer — see
 * [AutomationCallers], which pins the caller's package name, uid and signing certificate whether or not a
 * token is being asked for.
 */
object AutomationAuth {

    private const val PREFS_FILE = "kxkb_automation"
    private const val KEY_ENABLED = "automation_enabled"
    private const val KEY_REQUIRE_TOKEN = "automation_require_token"
    private const val KEY_TOKEN = "automation_token"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /**
     * Whether this app answers automation at all. **Default true** since contract v2.
     *
     * Kept as a switch rather than removed: it is the only way to close this one app off, and a feature that
     * can be turned on but never off is one 白い熊 cannot retreat from.
     */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** Whether a caller must present [token]. **Default false** — the token is opt-in now. */
    fun requireToken(context: Context): Boolean = prefs(context).getBoolean(KEY_REQUIRE_TOKEN, false)

    fun setRequireToken(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_TOKEN, value).apply()
    }

    /** The shared secret — 24 random bytes, hex; generated on first read so the settings row always shows one. */
    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() } ?: regenerateToken(context)

    fun regenerateToken(context: Context): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    /** Abbreviated form for the settings row — `80922d8c…4c49a87c`. */
    fun abbreviate(token: String): String =
        if (token.length <= 20) token else "${token.take(8)}…${token.takeLast(8)}"

    /**
     * True when the caller's token matches the stored secret. Compared **constant-time**
     * ([MessageDigest.isEqual]) so a wrong token leaks nothing through timing.
     */
    fun isTokenValid(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return MessageDigest.isEqual(candidate.toByteArray(), token(context).toByteArray())
    }

    /**
     * The whole gate, in the one place every entry point asks — the receiver ([StateExportReceiver]) and the
     * data door ([AutomationProvider]) alike.
     *
     * Returns null to proceed, or the exact `ERROR:` string to answer with. Written as one function so no
     * receiver, provider or service can implement the two checks in a subtly different order, which is how
     * "disabled" and "bad token" drift apart across forty-two apps. They stay distinct because they debug
     * differently.
     *
     * **A token supplied when [requireToken] is off is IGNORED, never an error.**
     */
    fun refuse(context: Context, candidate: String?): String? = when {
        !enabled(context) -> "ERROR:automation disabled"
        requireToken(context) && !isTokenValid(context, candidate) -> "ERROR:bad token"
        else -> null
    }
}
