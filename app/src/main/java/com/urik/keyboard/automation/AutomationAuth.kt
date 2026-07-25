package com.urik.keyboard.automation

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * External-automation intent surface ([StateExportReceiver]): a master switch plus a shared secret that every
 * automation broadcast must carry — the sister-app model (renrakusaki's `Config`, 自由作業盤's `AutomationAuth`).
 *
 * Device-local by design: the prefs file is its OWN SharedPreferences file, and the backup engine
 * ([com.urik.keyboard.service.BackupManager]) exports DataStore preferences, Room rows and custom layouts only —
 * never this file. The token therefore never travels in an export ZIP and never leaves the phone.
 *
 * Nothing here is reachable until 白い熊 flips [enabled] on (default **false**); every request checks the switch
 * and the token separately so "disabled" and "bad token" stay distinct, debuggable failures.
 */
object AutomationAuth {

    private const val PREFS_FILE = "kxkb_automation"
    private const val KEY_ENABLED = "automation_enabled"
    private const val KEY_TOKEN = "automation_token"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
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
}
