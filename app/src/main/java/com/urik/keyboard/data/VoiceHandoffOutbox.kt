package com.urik.keyboard.data

import android.content.Context
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.isUserUnlocked
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * The hand-over queue: sentences kept in the walk-capture review, waiting to reach 言語島 (contract §2).
 *
 * A sentence enters when it is kept and leaves only when 自由作業盤 has said, in so many uuids, that it is
 * durably in ITS inbox. So nothing is lost when that app is not installed, not running or refusing, and
 * nothing is sent twice: the push is at-least-once and the other side de-duplicates by uuid, which is
 * exactly the division of labour the contract sets out.
 *
 * `filesDir/voice_handoff/`: `outbox.json` (what is still waiting) and `sent.json` (what has gone, as a
 * uuid and a time — the count 白い熊 sees, and the memory that keeps a late pull or ack idempotent).
 *
 * Credential-protected storage: nothing is read or written before the first unlock.
 */
class VoiceHandoffOutbox(private val context: Context) {
    /** One reviewed sentence on its way to 言語島. [recognized] is what the recogniser made of it. */
    class Item(
        val uuid: String,
        val text: String,
        val recognized: String,
        val language: String,
        val capturedAt: Long,
        val queuedAt: Long
    ) {
        /** The §2 item object, built here so the wire shape lives in one place. */
        fun toJson(): JSONObject = JSONObject()
            .put("uuid", uuid)
            .put("text", text)
            .put("recognized", recognized)
            .put("language", language)
            .put("capturedAt", capturedAt)
    }

    private val dir: File get() = File(context.filesDir, DIR)

    val usable: Boolean get() = context.isUserUnlocked

    /** Everything still waiting, oldest first — a walk reads in the order it was spoken. */
    fun pending(): List<Item> = read(OUTBOX).sortedBy { it.capturedAt }

    fun pendingCount(): Int = read(OUTBOX).size

    fun sentCount(): Int = readSent().size

    /**
     * Queue a kept sentence. A uuid already waiting is replaced (a correction made after the first keep
     * wins); one already handed over is NOT queued again — 言語島 has it, and sending it twice would ask
     * 白い熊 to file the same sentence twice.
     */
    fun add(item: Item) {
        if (!usable) return
        if (item.uuid in readSent()) return
        val kept = read(OUTBOX).filterNot { it.uuid == item.uuid } + item
        write(OUTBOX, kept)
    }

    /** 自由作業盤 has these: drop them from the queue and remember them as gone. */
    fun markSent(uuids: Collection<String>) {
        if (!usable || uuids.isEmpty()) return
        val gone = uuids.toHashSet()
        val remaining = read(OUTBOX).filterNot { it.uuid in gone }
        write(OUTBOX, remaining)
        remember(gone)
    }

    // ---- storage ---------------------------------------------------------------------------------------

    private fun read(name: String): List<Item> {
        if (!usable) return emptyList()
        val file = File(dir, name)
        if (!file.isFile) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i -> itemFrom(arr.optJSONObject(i) ?: return@mapNotNull null) }
        } catch (e: Exception) {
            log("read-$name", e)
            emptyList()
        }
    }

    private fun itemFrom(o: JSONObject): Item? {
        val uuid = o.optString("uuid").takeIf { VoiceCaptureStore.isSafeUuid(it) } ?: return null
        val text = o.optString("text").takeIf { it.isNotBlank() } ?: return null
        return Item(
            uuid = uuid,
            text = text,
            recognized = o.optString("recognized", text),
            language = o.optString("language", "en"),
            capturedAt = o.optLong("capturedAt"),
            queuedAt = o.optLong("queuedAt")
        )
    }

    private fun write(name: String, items: List<Item>) {
        if (!usable) return
        try {
            dir.mkdirs()
            val arr = JSONArray()
            for (item in items) arr.put(item.toJson().put("queuedAt", item.queuedAt))
            writeAtomically(File(dir, name), arr.toString())
        } catch (e: Exception) {
            log("write-$name", e)
        }
    }

    private fun readSent(): Set<String> {
        if (!usable) return emptySet()
        val file = File(dir, SENT)
        if (!file.isFile) return emptySet()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length())
                .mapNotNull { i -> arr.optJSONObject(i)?.optString("uuid")?.takeIf { it.isNotEmpty() } }
                .toSet()
        } catch (e: Exception) {
            log("read-sent", e)
            emptySet()
        }
    }

    private fun remember(uuids: Collection<String>) {
        try {
            val file = File(dir, SENT)
            val existing = if (file.isFile) JSONArray(file.readText()) else JSONArray()
            val known = readSent()
            val now = System.currentTimeMillis()
            for (uuid in uuids) {
                if (uuid in known) continue
                existing.put(JSONObject().put("uuid", uuid).put("sentAt", now))
            }
            // Only a cap, not a policy: a year of walks is far under it.
            val trimmed = if (existing.length() > MAX_SENT) {
                JSONArray().apply {
                    for (i in existing.length() - MAX_SENT until existing.length()) put(existing.get(i))
                }
            } else {
                existing
            }
            dir.mkdirs()
            writeAtomically(file, trimmed.toString())
        } catch (e: Exception) {
            log("remember", e)
        }
    }

    private fun writeAtomically(target: File, content: String) {
        val part = File(target.parentFile, target.name + ".part")
        FileOutputStream(part).use { out ->
            out.write(content.toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!part.renameTo(target)) {
            target.delete()
            part.renameTo(target)
        }
    }

    private fun log(operation: String, e: Exception) {
        ErrorLogger.logException(
            component = "VoiceHandoffOutbox",
            severity = ErrorLogger.Severity.LOW,
            exception = e,
            context = mapOf("operation" to operation)
        )
    }

    companion object {
        const val DIR = "voice_handoff"
        private const val OUTBOX = "outbox.json"
        private const val SENT = "sent.json"
        private const val MAX_SENT = 20_000
    }
}
