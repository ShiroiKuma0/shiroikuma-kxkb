package com.urik.keyboard.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import com.urik.keyboard.service.BackupManager
import com.urik.keyboard.service.BackupPart
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.isUserUnlocked
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The sister-app **state-export automation contract** — the wire shape every 白い熊 app exposes so 自由作業盤's
 * 保存復元 project can back them all up headlessly in one run (reference implementations: renrakusaki's
 * `BackupContactsReceiver`, the EMUI-proven round-trip, and 自由作業盤's own `StateExportReceiver`).
 *
 * - `<pkg>.action.EXPORT_STATE`: run the ordinary category-ZIP export ([BackupManager]) with no Activity and no
 *   user interaction. Extras (all String): `token` (required — [AutomationAuth]), `path` (optional absolute
 *   directory, wins over the app's configured export folder), `items` (optional comma list of [BackupPart] ids;
 *   absent/empty = everything), `progress_action` (optional — see below), plus the reply trio
 *   `reply_action` / `reply_package` / `reply_id`.
 * - `<pkg>.action.LIST_CATEGORIES`: token-gated, instant category enumeration for the caller's checkbox picker.
 *   `id<TAB>label` per line; this app's parts are flat, so no third (parent-id) field is ever emitted.
 *
 * **ONE ZIP per request** — the single file named by [BackupManager.exportFileName] is the whole backup, with
 * every selected part as an entry inside it. Nothing else is written next to it.
 *
 * Reply: a FRESH broadcast to `reply_package` with action `reply_action`, extras `reply_id` (echoed verbatim)
 * and `result` = `OK:<path>|<bytes>|<human size>|<n> categories` (EXPORT_STATE), `OK:` + the `id<TAB>label`
 * lines (LIST_CATEGORIES), or `ERROR:<reason>`. Exactly one terminal reply, single-fire guarded by an
 * [AtomicBoolean]. **No binders** ([android.os.ResultReceiver] / [android.app.PendingIntent] /
 * [android.os.Messenger]) and **no reliance on the ordered-broadcast result** — EMUI severs both between
 * third-party apps (verified on 白い熊's Mate XT, 2026-07-23); the plain reply broadcast is the only channel
 * that works. [Intent.FLAG_INCLUDE_STOPPED_PACKAGES] so a backgrounded or stopped caller still hears us.
 *
 * Progress: while exporting, plain broadcasts to `reply_package` with action `progress_action` — extras
 * `reply_id`, `app` (display label), `text` (numbers-first, e.g. `区分 3/8 — 学習した単語`), and the structured
 * `current`/`total` (long) + `unit` (String). Real counts, never a percentage; throttled to at most one every
 * 500 ms, with the completion one always sent.
 */
class StateExportReceiver : BroadcastReceiver() {

    /** Pulled lazily, and only after the gate passes, so a rejected request never builds the DI graph. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BackupEntryPoint {
        fun backupManager(): BackupManager

        fun settingsRepository(): SettingsRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val replyAction = intent.getStringExtra(EXTRA_REPLY_ACTION)?.trim().orEmpty()
        val replyPackage = intent.getStringExtra(EXTRA_REPLY_PACKAGE)?.trim().orEmpty()
        val replyId = intent.getStringExtra(EXTRA_REPLY_ID)?.trim().orEmpty()
        val progressAction = intent.getStringExtra(EXTRA_PROGRESS_ACTION)?.trim().orEmpty()
        val pathOverride = intent.getStringExtra(EXTRA_PATH)?.trim().orEmpty()
        val items = intent.getStringExtra(EXTRA_ITEMS)?.trim().orEmpty()

        val replied = AtomicBoolean(false)
        fun reply(result: String) {
            if (replyAction.isEmpty() || replyPackage.isEmpty()) return
            if (!replied.compareAndSet(false, true)) return
            app.sendBroadcast(
                Intent(replyAction).apply {
                    setPackage(replyPackage)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(EXTRA_REPLY_ID, replyId)
                    putExtra(EXTRA_RESULT, result)
                }
            )
        }

        // Gate first — "disabled" and "bad token" stay distinct, they debug differently.
        if (!AutomationAuth.enabled(app)) {
            reply("ERROR:automation disabled")
            return
        }
        if (!AutomationAuth.isTokenValid(app, token)) {
            reply("ERROR:bad token")
            return
        }
        // Before first unlock every store this export reads (DataStore, Room, filesDir) is LOCKED. The receiver
        // is not directBootAware so the system should not deliver here at all — but say so rather than crash.
        if (!app.isUserUnlocked) {
            reply("ERROR:device locked (before first unlock)")
            return
        }

        when (action) {
            listCategoriesAction(app) ->
                reply(
                    "OK:" +
                        BackupPart.entries.joinToString("\n") { "${it.id}\t${app.getString(it.labelRes)}" }
                )

            exportStateAction(app) -> {
                val parts: Set<BackupPart> = if (items.isEmpty()) {
                    BackupPart.entries.toSet()
                } else {
                    val ids = items.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val resolved = ids.mapNotNull { BackupPart.fromId(it) }
                    if (resolved.size != ids.size) {
                        reply("ERROR:unknown category in items: $items")
                        return
                    }
                    resolved.toSet()
                }
                exportAsync(app, parts, pathOverride, progressAction, replyPackage, replyId, ::reply)
            }

            else -> reply("ERROR:unknown action: $action")
        }
    }

    /** Holds the broadcast open with `goAsync()` and runs the real export off the main thread. */
    private fun exportAsync(
        app: Context,
        parts: Set<BackupPart>,
        pathOverride: String,
        progressAction: String,
        replyPackage: String,
        replyId: String,
        reply: (String) -> Unit
    ) {
        val appLabel = runCatching {
            app.packageManager.getApplicationLabel(app.applicationInfo).toString()
        }.getOrDefault(app.packageName)
        var lastProgressAt = 0L

        fun progress(done: Int, total: Int, partLabel: String) {
            if (progressAction.isEmpty() || replyPackage.isEmpty()) return
            val now = System.currentTimeMillis()
            // At most one every 500 ms — but the completion one always goes out.
            if (done < total && now - lastProgressAt < PROGRESS_MIN_INTERVAL_MS) return
            lastProgressAt = now
            app.sendBroadcast(
                Intent(progressAction).apply {
                    setPackage(replyPackage)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(EXTRA_REPLY_ID, replyId)
                    putExtra(EXTRA_PROGRESS_APP, appLabel)
                    putExtra(EXTRA_PROGRESS_TEXT, "区分 $done/$total — $partLabel")
                    putExtra(EXTRA_PROGRESS_CURRENT, done.toLong())
                    putExtra(EXTRA_PROGRESS_TOTAL, total.toLong())
                    putExtra(EXTRA_PROGRESS_UNIT, "区分")
                }
            )
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val entry = EntryPointAccessors.fromApplication(app, BackupEntryPoint::class.java)
                // Directory precedence: the `path` extra → the app's configured export folder → error.
                val dirPath = pathOverride.ifEmpty {
                    entry.settingsRepository().getExportImportPath()?.trim().orEmpty()
                }
                if (dirPath.isEmpty()) {
                    reply("ERROR:no-directory")
                    return@launch
                }
                // We declare MANAGE_EXTERNAL_STORAGE for exactly this; it may still be ungranted.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    reply("ERROR:no-storage-access")
                    return@launch
                }
                val dir = File(dirPath)
                dir.mkdirs()
                if (!dir.isDirectory) {
                    reply("ERROR:not a directory: $dirPath")
                    return@launch
                }
                val file = File(dir, BackupManager.exportFileName())
                val result = file.outputStream().use { out ->
                    entry.backupManager().export(parts, out, appVersionName(app), ::progress)
                }
                val written = parts.size - result.errors.size
                if (written <= 0) {
                    file.delete()
                    reply("ERROR:every category failed: ${result.errors.joinToString(", ")}")
                    return@launch
                }
                val bytes = file.length()
                val failed = if (result.errors.isEmpty()) "" else " (${result.errors.size} failed)"
                reply("OK:${file.absolutePath}|$bytes|${humanSize(bytes)}|$written categories$failed")
            } catch (e: Exception) {
                reply("ERROR:${e.message ?: e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        // `<pkg>.action.…` — derived from the installed applicationId, exactly as the manifest declares them
        // with `${applicationId}` (this project does not generate a BuildConfig).
        fun exportStateAction(context: Context): String = "${context.packageName}.action.EXPORT_STATE"

        fun listCategoriesAction(context: Context): String = "${context.packageName}.action.LIST_CATEGORIES"

        // Contract extras — deliberately bare names, shared verbatim by every sister app.
        const val EXTRA_TOKEN = "token"
        const val EXTRA_PATH = "path"
        const val EXTRA_ITEMS = "items"
        const val EXTRA_PROGRESS_ACTION = "progress_action"
        const val EXTRA_REPLY_ACTION = "reply_action"
        const val EXTRA_REPLY_PACKAGE = "reply_package"
        const val EXTRA_REPLY_ID = "reply_id"
        const val EXTRA_RESULT = "result"
        const val EXTRA_PROGRESS_APP = "app"
        const val EXTRA_PROGRESS_TEXT = "text"
        const val EXTRA_PROGRESS_CURRENT = "current"
        const val EXTRA_PROGRESS_TOTAL = "total"
        const val EXTRA_PROGRESS_UNIT = "unit"

        private const val PROGRESS_MIN_INTERVAL_MS = 500L

        private fun appVersionName(context: Context): String = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0"

        /** Display size for the reply's third field — [Locale.ROOT] so it never renders `4,6 MB`. */
        fun humanSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.1f KB", bytes / (1L shl 10).toDouble())
            else -> "$bytes B"
        }
    }
}
