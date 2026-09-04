package com.urik.keyboard.automation

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.urik.keyboard.service.BackupManager
import com.urik.keyboard.service.BackupPart
import com.urik.keyboard.utils.isUserUnlocked
import org.json.JSONArray
import org.json.JSONObject

/**
 * The data door: export this keyboard's own state, and put it back, for a caller we can identify.
 *
 * ## Why a provider and not the broadcast receiver next to it
 *
 * Two reasons, and the first is the whole point of contract v2.
 *
 * **A broadcast cannot tell you who sent it.** v1's answer to that was a shared secret, which cannot survive
 * the wipe this feature exists to recover from. A provider gets the caller's identity from the framework for
 * free — see [AutomationCallers] for what is actually checked, and why a `shiroikuma.*` prefix test would have
 * been strictly *weaker* than the token it replaces.
 *
 * **A list needs a synchronous answer.** 応用管理 draws a row per installed app before any export exists; a
 * broadcast round trip per app to fill a list is the wrong shape entirely.
 *
 * ## What does NOT happen here
 *
 * The payload. [call] validates, starts a foreground service and returns — tens of megabytes over minutes
 * inside a binder call would block the caller, report no progress, refuse cancellation and die silently if
 * this process were killed. The bytes go through a descriptor the caller opened, and the terminal answer comes
 * back on the broadcast the family already proved on EMUI.
 *
 * ## Why a descriptor and not a path
 *
 * A backup is not a stable directory while it is being assembled: 応用管理 writes into a temporary path and
 * renames on commit, and it encrypts and checksums **per file it knows about**. A file this app dropped into
 * that directory itself would be renamed out from under it, would sit in plaintext inside an otherwise
 * encrypted backup, and would be unverified rather than verified-and-failing. A descriptor is also a
 * capability that **expires when it is closed**.
 *
 * It also means the automation path no longer needs `MANAGE_EXTERNAL_STORAGE` — that permission was only ever
 * required because v1 handed apps an absolute path. (This app still declares it for the §1 receiver's `path`
 * extra and its own Export/import page.)
 *
 * ## One thing specific to a keyboard
 *
 * **Before first unlock every store a backup touches is locked** — DataStore, Room and `filesDir` are all
 * credential-protected. This app's IME is `directBootAware` and therefore *runs* on the lock screen, so unlike
 * most sister apps its process may genuinely be alive when a call arrives. This provider is not
 * `directBootAware`, so the system should not publish it that early — but the guard is stated rather than
 * assumed, because the alternative is a crash inside a binder call.
 */
class AutomationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * Every method answers a [Bundle] with [KEY_RESULT] — `OK…` or `ERROR:…`, the same vocabulary the broadcast
     * contract uses, so a caller has one grammar to parse rather than two.
     *
     * A refusal is **returned, never thrown**: an exception across a binder reaches the caller as a
     * `RuntimeException` carrying our stack trace, which tells 白い熊 nothing and tells a misbehaving caller
     * rather more than it should.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context?.applicationContext ?: return fail("ERROR:not ready")

        // WHO, before WHAT. A caller we cannot identify gets the same answer whatever it asked for.
        when (val verdict = AutomationCallers.verify(ctx, callingPackage)) {
            is AutomationCallers.Verdict.Refused -> return fail(verdict.why)
            AutomationCallers.Verdict.Allowed -> Unit
        }
        // Then this app's own switches — a token is ignored unless this app asks for one.
        AutomationAuth.refuse(ctx, extras?.getString(KEY_TOKEN))?.let { return fail(it) }

        // Cancel is answerable while locked; everything else reads or writes credential-protected stores.
        if (method != METHOD_CANCEL && !ctx.isUserUnlocked) {
            return fail("ERROR:device locked (before first unlock)")
        }

        return when (method) {
            METHOD_DESCRIBE -> ok(describe(ctx))
            METHOD_EXPORT -> start(ctx, extras, importing = false)
            METHOD_IMPORT -> start(ctx, extras, importing = true)
            METHOD_CANCEL -> {
                AutomationJobs.cancel(extras?.getString(KEY_JOB_ID))
                ok("OK:cancelled")
            }
            else -> fail("ERROR:unknown method: $method")
        }
    }

    /**
     * What this app would export, answered without exporting anything.
     *
     * Returned from the call rather than written into the archive, deliberately: 応用管理 must draw a row
     * before an export exists, and at restore must judge compatibility **before** streaming megabytes into an
     * app that would reject them — which it cannot do if the header is buried inside an encrypted archive.
     *
     * `contains` is rendered verbatim to 白い熊, so the part labels say plainly what each part holds — a
     * keyboard's backup includes the words it has learned from what was typed on it, and a line reading only
     * "Learned words" would not have said so.
     */
    private fun describe(ctx: Context): String {
        val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val parts = BackupPart.entries.filter { it.defaultSelected }
        val header = JSONObject()
            .put("app_id", ctx.packageName)
            .put("version_code", @Suppress("DEPRECATION") (info?.versionCode ?: 0))
            .put("version_name", info?.versionName.orEmpty())
            .put("format", FORMAT)
            .put("min_format_readable", MIN_FORMAT_READABLE)
            // This keyboard imports into stores it creates on demand and merges per row, so it does not need
            // to have been launched once before a restore lands.
            .put("requires_launch_first", false)
            .put("contains", JSONArray(parts.map { ctx.getString(it.labelRes) }))
        return "OK:$header"
    }

    /**
     * Hand the descriptor to a foreground service and get out of the way.
     *
     * The descriptor is **duplicated** before it leaves this method. The one in [extras] belongs to the binder
     * transaction and is closed when `call()` returns; a service reading it afterwards would find it shut.
     * That is a bug you only see under load, so it is not left to the service to remember.
     *
     * A refused service start (Android 12+ restricts starting a foreground service from a binder call, which
     * is a background start) is answered as an `ERROR:` like any other refusal rather than thrown across the
     * binder — and the descriptor is released rather than stranded in a map nothing will ever read, because a
     * descriptor we still hold is a file the caller cannot close, checksum or encrypt.
     * [AutomationDataService.start] closes it itself on that path, so there is deliberately no `dup.close()`
     * here: closing it twice would be a different bug in place of the leak.
     */
    private fun start(ctx: Context, extras: Bundle?, importing: Boolean): Bundle {
        @Suppress("DEPRECATION")
        val fd = extras?.getParcelable<ParcelFileDescriptor>(KEY_FD) ?: return fail("ERROR:no descriptor")
        val dup = runCatching { fd.dup() }.getOrNull() ?: return fail("ERROR:descriptor unusable")
        val jobId = AutomationJobs.begin()
        AutomationDataService.start(ctx, jobId, dup, importing, extras)?.let { why ->
            AutomationJobs.finish(jobId)
            return fail(why)
        }
        return ok("OK:$jobId")
    }

    private fun ok(result: String) = Bundle().apply { putString(KEY_RESULT, result) }

    private fun fail(why: String) = Bundle().apply { putString(KEY_RESULT, why) }

    // A provider that is only ever `call()`ed still has to answer these. Refusing loudly beats returning an
    // empty cursor, which reads downstream as "there is no data" rather than "wrong door".
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? =
        throw UnsupportedOperationException("automation is call() only")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("automation is call() only")

    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")

    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")

    companion object {
        const val METHOD_DESCRIBE = "describe"
        const val METHOD_EXPORT = "export"
        const val METHOD_IMPORT = "import"
        const val METHOD_CANCEL = "cancel"

        const val KEY_RESULT = "result"
        const val KEY_FD = "fd"
        const val KEY_TOKEN = "token"
        const val KEY_JOB_ID = "job_id"
        const val KEY_ITEMS = "items"
        const val KEY_REPLY_ACTION = "reply_action"
        const val KEY_REPLY_PACKAGE = "reply_package"
        const val KEY_PROGRESS_ACTION = "progress_action"

        /**
         * This app's archive format — [BackupManager.FORMAT_VERSION], stated here as the number the contract
         * negotiates on so the two can never disagree. Bumped when an older build could no longer read what we
         * write.
         */
        const val FORMAT = BackupManager.FORMAT_VERSION

        /**
         * The oldest archive this build can still read.
         *
         * Version skew has a direction: old data into a newer app is normally fine, because an app migrates
         * its own storage; newer data into an older app is not. This field is what lets a caller refuse the
         * second case at discovery time, before anything is streamed.
         */
        const val MIN_FORMAT_READABLE = 1
    }
}
