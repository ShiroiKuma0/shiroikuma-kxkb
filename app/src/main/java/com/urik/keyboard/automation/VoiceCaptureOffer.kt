package com.urik.keyboard.automation

import com.urik.keyboard.data.VoiceCaptureStore
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * `voice_capture_offer`, minus the binder: 自由作業盤 hands us the clips of a walk, we copy the ones that
 * check out into the capture inbox and answer which uuids are durably ours.
 *
 * Kept out of [AutomationProvider] so the whole rule set can be tested with ordinary streams — the
 * provider does the caller check, turns each `fd_<n>` into a stream and closes the descriptors.
 *
 * **`OK:` means "written and fsynced here", never "accepted for processing"** — the other side deletes its
 * only copy on that word, and a success reply that outran the write is exactly how the 2026-09-03 empty
 * backups happened. So the copying is synchronous and the batch is small.
 */
class VoiceCaptureOffer(private val store: VoiceCaptureStore) {
    /** [result] is the `result` extra; [rejected] the optional per-uuid refusal list ("uuid:reason,…"). */
    class Answer(val result: String, val rejected: String? = null)

    fun apply(itemsJson: String?, open: (String) -> InputStream?): Answer {
        if (!store.usable) return Answer("ERROR:locked")
        val items = parse(itemsJson) ?: return Answer("ERROR:items")
        if (items.length() > MAX_ITEMS) return Answer("ERROR:items")
        if (items.length() == 0) return Answer("OK:")

        var declared = 0L
        for (i in 0 until items.length()) declared += items.optJSONObject(i)?.optLong("byteLength") ?: 0L
        if (declared > store.freeBytes()) return Answer("ERROR:budget")

        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i)
            if (item == null) {
                rejected.add("?:${VoiceCaptureStore.Reject.FORMAT.tag}")
                continue
            }
            val uuid = item.optString("uuid")
            if (!VoiceCaptureStore.isSafeUuid(uuid)) {
                rejected.add("?:${VoiceCaptureStore.Reject.FORMAT.tag}")
                continue
            }
            // The format is declared AND verified: a mismatch here is a contract change on the other side,
            // and saying so per uuid beats decoding a file that is not what we agreed on.
            val agreed = item.optInt("sampleRate", SAMPLE_RATE) == SAMPLE_RATE &&
                item.optInt("channels", 1) == 1 &&
                item.optInt("bitsPerSample", 16) == 16
            if (!agreed) {
                rejected.add("$uuid:${VoiceCaptureStore.Reject.FORMAT.tag}")
                continue
            }
            val fdKey = item.optString("fd")
            val stream = if (fdKey.isEmpty()) null else open(fdKey)
            when (
                val outcome = store.import(
                    uuid = uuid,
                    capturedAt = item.optLong("capturedAt"),
                    durationMs = item.optLong("durationMs"),
                    language = item.optString("language", "en"),
                    declaredBytes = item.optLong("byteLength"),
                    sha256 = item.optString("sha256"),
                    source = stream
                )
            ) {
                // Already here (or here once and since reviewed): the caller should delete its copy too,
                // which is what OK says — a "duplicate" refusal would make it offer that clip for ever.
                VoiceCaptureStore.Import.Stored, VoiceCaptureStore.Import.Duplicate -> accepted.add(uuid)
                is VoiceCaptureStore.Import.Refused -> rejected.add("$uuid:${outcome.reason.tag}")
            }
        }
        return Answer(
            result = "OK:" + accepted.joinToString(","),
            rejected = rejected.takeIf { it.isNotEmpty() }?.joinToString(",")
        )
    }

    private fun parse(itemsJson: String?): JSONArray? {
        if (itemsJson.isNullOrBlank()) return null
        return try {
            JSONArray(itemsJson)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** The batch cap: ten descriptors, ten clips, one synchronous copy that cannot hold a binder long. */
        const val MAX_ITEMS = 10
        private const val SAMPLE_RATE = 16000
    }
}
