package com.urik.keyboard.automation

import android.content.Context
import android.content.Intent
import com.urik.keyboard.service.BackupPart

/**
 * The ONE §3 progress sender, shared by both automation doors.
 *
 * Contract v2 is explicit that an export driven through the provider ([AutomationProvider]) reports progress
 * exactly as the broadcast receiver's does — §3's own rule is that **an app silent for two minutes is presumed
 * dead**, and an archive that takes minutes to write is precisely the case the data door exists for. It is
 * equally explicit that an app which already has a §1 sender should *parameterise that one* rather than write a
 * second: two implementations of the same watchdog drift, and the one that drifts is always the one nobody is
 * looking at.
 *
 * So both callers construct this. The only thing that differs between them is [correlationId] — the §1
 * receiver's `reply_id`, the §2a door's `job_id` — and it is written into **both** the `reply_id` and `job_id`
 * extras so one progress reader on the caller's side serves both doors.
 *
 * Real counts, never a percentage. [done] is the POSITION of the part being written (1 while the first is
 * written, and a final call with `done == total`), and [total] is the number of parts **actually being
 * exported** after `items` filtering — which is what lets the caller's panel recognise the count as a walk
 * through the list it is showing.
 */
internal class AutomationProgress(
    private val context: Context,
    private val progressAction: String,
    private val replyPackage: String,
    private val correlationId: String,
    private val appLabel: String,
    /** The parts being exported, in write order — so a `done` position can name the category id it is on. */
    private val ordered: List<BackupPart>
) {

    private var lastSentAt = 0L

    /** True when there is somewhere to send to at all; a caller that passed no `progress_action` gets nothing. */
    private val active: Boolean
        get() = progressAction.isNotEmpty() && replyPackage.isNotEmpty()

    /**
     * Send one progress broadcast. Throttled to at most one every [MIN_INTERVAL_MS] — except the completion
     * one, which always goes out.
     *
     * [bytesWritten] is §3's second counter; omitted (negative) for a path that does not know it.
     */
    fun send(done: Int, total: Int, partLabel: String, bytesWritten: Long = -1L) {
        if (!active) return
        val now = System.currentTimeMillis()
        if (done < total && now - lastSentAt < MIN_INTERVAL_MS) return
        lastSentAt = now
        context.sendBroadcast(
            Intent(progressAction).apply {
                setPackage(replyPackage)
                // Without this a backgrounded or stopped caller never hears us.
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                // Both extras carry the one correlation id, so a single reader serves both doors.
                putExtra(StateExportReceiver.EXTRA_REPLY_ID, correlationId)
                putExtra(AutomationProvider.KEY_JOB_ID, correlationId)
                putExtra(StateExportReceiver.EXTRA_PROGRESS_APP, appLabel)
                // WHICH category is being written right now. The panel highlights that row and ticks off
                // everything above it; without it, it can only guess from the count.
                putExtra(StateExportReceiver.EXTRA_PROGRESS_ITEM, ordered.getOrNull(done - 1)?.id.orEmpty())
                putExtra(StateExportReceiver.EXTRA_PROGRESS_TEXT, "区分 $done/$total — $partLabel")
                putExtra(StateExportReceiver.EXTRA_PROGRESS_CURRENT, done.toLong())
                putExtra(StateExportReceiver.EXTRA_PROGRESS_TOTAL, total.toLong())
                putExtra(StateExportReceiver.EXTRA_PROGRESS_UNIT, "区分")
                if (bytesWritten >= 0) putExtra(StateExportReceiver.EXTRA_PROGRESS_BYTES, bytesWritten)
            }
        )
    }

    companion object {
        private const val MIN_INTERVAL_MS = 500L

        /** The parts of [parts] in the order [com.urik.keyboard.service.BackupManager] writes them. */
        fun writeOrder(parts: Set<BackupPart>): List<BackupPart> = BackupPart.entries.filter { it in parts }

        fun appLabel(context: Context): String = runCatching {
            context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        }.getOrDefault(context.packageName)
    }
}
