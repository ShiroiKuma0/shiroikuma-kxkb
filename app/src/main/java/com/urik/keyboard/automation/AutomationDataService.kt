package com.urik.keyboard.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.urik.keyboard.R
import com.urik.keyboard.service.BackupCancelledException
import com.urik.keyboard.service.BackupManager
import com.urik.keyboard.service.BackupPart
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where a data export or import started at the door ([AutomationProvider]) actually runs.
 *
 * ## Why a foreground service and not the provider call
 *
 * The call returns in milliseconds; this can run for as long as the data takes. Two hard reasons it cannot be
 * done anywhere cheaper:
 *
 * - **A binder call holds the caller.** 応用管理 is drawing a list; a multi-second synchronous call would
 *   freeze its UI, report no progress and refuse cancellation.
 * - **A backgrounded app writing for minutes is frozen mid-stream on this phone**, which yields a truncated
 *   archive underneath a success reply — the worst possible failure, because it is indistinguishable from a
 *   good backup until the day it is restored.
 *
 * ## The descriptor
 *
 * Already duplicated by [AutomationProvider] before it got here, because the original belongs to the binder
 * transaction and is closed the moment `call()` returns. This service owns the copy and closes it in a
 * `finally` — leaking one would hold the caller's file open indefinitely, and the caller cannot checksum or
 * encrypt a file that is still open.
 *
 * ## What it exports
 *
 * The very same [BackupManager] engine, and therefore the very same one-ZIP archive, as the Export/import page
 * and the §1 broadcast receiver. There is no second export format and no second file: a keyboard's whole state
 * is the parts of [BackupPart], written as entries inside one archive streamed straight into the descriptor.
 */
class AutomationDataService : Service() {

    /** Pulled lazily so a job that never starts does not build the DI graph. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BackupEntryPoint {
        fun backupManager(): BackupManager
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val importing = intent?.getBooleanExtra(EXTRA_IMPORTING, false) ?: false

        // GO FOREGROUND FIRST, BEFORE ANY RETURN. Once `startForegroundService` has been called the platform
        // requires `startForeground` whatever this service then decides, and enforces that by killing the
        // process with `ForegroundServiceDidNotStartInTimeException`. Every early return below — no intent,
        // no job id, a handover entry already drained — is a request we simply ignore, and ignoring one must
        // not take the app down: a caller retrying with a stale job id would otherwise KILL the keyboard.
        // (Within 5 s, and a refused notification must not take the job down either, hence runCatching.)
        runCatching { startForeground(NOTIFICATION_ID, notification(importing)) }

        val jobId = intent?.getStringExtra(EXTRA_JOB) ?: return stop(startId)
        val fd = HANDOVER.remove(jobId) ?: return stop(startId)
        val items = intent.getStringExtra(AutomationProvider.KEY_ITEMS)
        val replyAction = intent.getStringExtra(AutomationProvider.KEY_REPLY_ACTION)
        val replyPackage = intent.getStringExtra(AutomationProvider.KEY_REPLY_PACKAGE)
        val progressAction = intent.getStringExtra(AutomationProvider.KEY_PROGRESS_ACTION)

        // ONE flag rather than a guard per failure. The descriptor has been taken out of the handover map
        // and nothing owns it until the coroutine below does; `startForeground` throwing is only one way to
        // leave that window, so the `finally` closes it on EVERY way out that is not a successful handover.
        var handedOff = false

        val replied = AtomicBoolean(false)
        fun reply(result: String) {
            // Exactly one terminal answer per job, whatever path got here — a synchronous failure and an
            // asynchronous success must never both fire. The guard the family has carried since app one.
            if (!replied.compareAndSet(false, true)) return
            AutomationJobs.finish(jobId)
            if (replyAction.isNullOrEmpty() || replyPackage.isNullOrEmpty()) return
            sendBroadcast(
                Intent(replyAction).apply {
                    setPackage(replyPackage)
                    // Without this a backgrounded caller never hears the answer, and on a clean phone the
                    // caller may not have been launched at all.
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(AutomationProvider.KEY_JOB_ID, jobId)
                    putExtra(AutomationProvider.KEY_RESULT, result)
                }
            )
        }

        try {
            scope.launch {
                try {
                    fd.use { open ->
                        if (importing) {
                            runImport(open, items, ::reply)
                        } else {
                            runExport(jobId, open, items, progressAction, replyPackage, ::reply)
                        }
                    }
                } catch (_: BackupCancelledException) {
                    reply("ERROR:cancelled")
                } catch (t: Throwable) {
                    reply("ERROR:${t.message ?: t.javaClass.simpleName}")
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            handedOff = true
        } finally {
            if (!handedOff) {
                runCatching { fd.close() }
                AutomationJobs.finish(jobId)
                stop(startId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Stream one backup ZIP into the caller's descriptor.
     *
     * The byte count is taken **as it goes** rather than stat'ed afterwards: the caller owns the file and we
     * may not be able to see it at all — it can be an anonymous pipe, or a descriptor into a directory this app
     * cannot list.
     */
    private suspend fun runExport(
        jobId: String,
        fd: ParcelFileDescriptor,
        items: String?,
        progressAction: String?,
        replyPackage: String?,
        reply: (String) -> Unit
    ) {
        val parts = resolve(items)
        if (parts == null) {
            reply("ERROR:unknown category in items: $items")
            return
        }
        if (parts.isEmpty()) {
            reply("ERROR:no categories selected")
            return
        }
        val manager = EntryPointAccessors.fromApplication(applicationContext, BackupEntryPoint::class.java)
            .backupManager()
        var written = 0L

        // §3 applies to this door exactly as it does to the receiver's: an app silent for two minutes is
        // presumed dead, and an archive that takes minutes to write is precisely the case this door exists
        // for. The SAME sender the receiver uses, with the `job_id` as the correlation id — it writes that id
        // into both the `job_id` and `reply_id` extras so one reader on the caller's side serves both doors.
        val progress = AutomationProgress(
            context = this,
            progressAction = progressAction.orEmpty(),
            replyPackage = replyPackage.orEmpty(),
            correlationId = jobId,
            appLabel = AutomationProgress.appLabel(this),
            ordered = AutomationProgress.writeOrder(parts)
        )

        val result = ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            val counting = object : OutputStream() {
                override fun write(b: Int) {
                    out.write(b)
                    written++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                    written += len
                }
            }
            manager.export(
                parts = parts,
                out = counting,
                appVersion = versionName(),
                onProgress = { done, total, partLabel -> progress.send(done, total, partLabel, written) },
                isCancelled = { AutomationJobs.isCancelled(jobId) }
            )
        }
        val ok = parts.size - result.errors.size
        if (ok <= 0) {
            reply("ERROR:every category failed: ${result.errors.joinToString(", ")}")
            return
        }
        val failed = if (result.errors.isEmpty()) "" else " (${result.errors.size} failed)"
        reply("OK:$written|${StateExportReceiver.humanSize(written)}|$ok categories$failed")
    }

    /**
     * Spool the whole archive to disk, validate it there, and only then apply it.
     *
     * **Nothing is written until the whole archive has arrived and been checked** — a partial read that failed
     * halfway would otherwise import half an archive, and a half-restored keyboard is worse than one that
     * refused. That guarantee is unchanged from reading it into a byte array; only the bound moves from RAM to
     * disk, because an archive is not required to be small and an app that assumes it is fails on the one
     * restore that matters. The spool file lives in `cacheDir` and is deleted in a `finally` whatever happens.
     */
    private suspend fun runImport(fd: ParcelFileDescriptor, items: String?, reply: (String) -> Unit) {
        val spool = File.createTempFile("automation-import", ".zip", cacheDir)
        try {
            val copied = ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                spool.outputStream().use { out -> input.copyTo(out) }
            }
            if (copied == 0L) {
                reply("ERROR:empty archive")
                return
            }
            importSpooled(spool, items, reply)
        } finally {
            spool.delete()
        }
    }

    private suspend fun importSpooled(spool: File, items: String?, reply: (String) -> Unit) {
        val manager = EntryPointAccessors.fromApplication(applicationContext, BackupEntryPoint::class.java)
            .backupManager()
        // Every category the archive actually carries — asking for one it lacks is how a restore ends up
        // reporting success over nothing. When the caller named `items`, restore the intersection.
        val present = spool.inputStream().use { manager.partsInBackup(it) }
        if (present.isEmpty()) {
            reply("ERROR:archive carries no categories")
            return
        }
        val wanted = if (items.isNullOrBlank()) {
            present
        } else {
            val asked = resolve(items)
            if (asked == null) {
                reply("ERROR:unknown category in items: $items")
                return
            }
            present intersect asked
        }
        if (wanted.isEmpty()) {
            reply("ERROR:archive carries none of the requested categories")
            return
        }
        val result = spool.inputStream().use { manager.import(wanted, it) }
        val ok = wanted.size - result.errors.size
        if (ok <= 0) {
            reply("ERROR:every category failed: ${result.errors.joinToString(", ")}")
            return
        }
        val failed = if (result.errors.isEmpty()) "" else " (${result.errors.size} failed)"
        // 応用管理 force-stops us straight after this, and that is deliberate: a running process writes its
        // cached SharedPreferences back out at orderly shutdown and would silently undo the import that just
        // happened. The guarantee lives on its side so that forty-two apps do not each have to remember it.
        reply("OK:$ok categories restored$failed")
    }

    /** Absent/empty `items` means this app's DEFAULT set — exactly the parts `LIST_CATEGORIES` reports as `on`. */
    private fun resolve(items: String?): Set<BackupPart>? {
        if (items.isNullOrBlank()) return BackupPart.entries.filter { it.defaultSelected }.toSet()
        val ids = items.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val found = ids.mapNotNull { BackupPart.fromId(it) }
        return if (found.size == ids.size) found.toSet() else null
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "0"

    private fun notification(importing: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.automation_data_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val title =
            if (importing) R.string.automation_data_importing else R.string.automation_data_exporting
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(title))
            .setSmallIcon(
                if (importing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload
            )
            .setOngoing(true)
            .build()
    }

    /** Leave the foreground and stop. Never called before [startForeground] — see [onStartCommand]. */
    private fun stop(startId: Int): Int {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "automation_data"
        private const val NOTIFICATION_ID = 9714
        private const val EXTRA_JOB = "job"
        private const val EXTRA_IMPORTING = "importing"

        /**
         * The descriptor's way across, because an Intent is the wrong vehicle for one.
         *
         * A [ParcelFileDescriptor] in an Intent extra is duplicated by the system on delivery and the copy's
         * lifetime stops being ours to reason about. Handing it through a map keyed by the job id keeps exactly
         * one open descriptor with exactly one owner — this service, which closes it in a `finally`.
         */
        private val HANDOVER = ConcurrentHashMap<String, ParcelFileDescriptor>()

        /**
         * Hand the descriptor over and start the service. Returns null on success, or the `ERROR:` reason to
         * answer the caller with.
         *
         * A refused start is **answered, not thrown**: `startForegroundService` from a binder call is a
         * background start, and on API 31+ it can be refused outright with
         * `ForegroundServiceStartNotAllowedException` unless this app is exempt from battery optimisation.
         * The descriptor is taken back out of the handover map and closed **here**, so the caller's open file
         * is never stranded in a map nothing will ever read. (When 応用管理 reports a failed row naming a
         * foreground service start, the fix is the battery-optimisation exemption on this app, not the code.)
         */
        fun start(
            context: Context,
            jobId: String,
            fd: ParcelFileDescriptor,
            importing: Boolean,
            extras: Bundle?
        ): String? {
            HANDOVER[jobId] = fd
            return runCatching { startService(context, jobId, importing, extras) }.fold(
                onSuccess = { null },
                onFailure = { t ->
                    HANDOVER.remove(jobId)
                    runCatching { fd.close() }
                    "ERROR:cannot start service: ${t.message ?: t.javaClass.simpleName}"
                }
            )
        }

        private fun startService(context: Context, jobId: String, importing: Boolean, extras: Bundle?) {
            context.startForegroundService(
                Intent(context, AutomationDataService::class.java).apply {
                    putExtra(EXTRA_JOB, jobId)
                    putExtra(EXTRA_IMPORTING, importing)
                    putExtra(AutomationProvider.KEY_ITEMS, extras?.getString(AutomationProvider.KEY_ITEMS))
                    putExtra(
                        AutomationProvider.KEY_REPLY_ACTION,
                        extras?.getString(AutomationProvider.KEY_REPLY_ACTION)
                    )
                    putExtra(
                        AutomationProvider.KEY_REPLY_PACKAGE,
                        extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE)
                    )
                    putExtra(
                        AutomationProvider.KEY_PROGRESS_ACTION,
                        extras?.getString(AutomationProvider.KEY_PROGRESS_ACTION)
                    )
                }
            )
        }
    }
}
