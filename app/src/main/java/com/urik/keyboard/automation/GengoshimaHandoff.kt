package com.urik.keyboard.automation

import android.content.ContentResolver
import android.os.Bundle
import com.urik.keyboard.data.VoiceCaptureStore
import com.urik.keyboard.data.VoiceHandoffOutbox
import org.json.JSONArray

/**
 * Handing reviewed sentences to 言語島 (contract §2), in both directions:
 *
 * - **Push** (the normal one): we call `gengoshima_intake` on 自由作業盤's door with up to
 *   [MAX_ITEMS] sentences and drop from the outbox exactly the uuids it names in `OK:` — the ones it says
 *   are durably in its own inbox. Anything else leaves the queue untouched, so a refusal, an app that is
 *   not installed and a phone that answered nothing all mean the same safe thing: try again later.
 * - **Pull** (the fallback): 自由作業盤 reads the queue through OUR door and acknowledges what it stored.
 *   Same bookkeeping, opposite direction, for the day a push is refused.
 *
 * The transport is `ContentResolver.call()` in both directions and never a broadcast: EMUI severs
 * ordered-broadcast results, so a broadcast's `setResultCode` could not carry the receipt this whole
 * design rests on.
 */
class GengoshimaHandoff(private val outbox: VoiceHandoffOutbox) {
    /** [sent] is what 言語島 now holds; [error] the verbatim refusal when the batch did not go. */
    class Pushed(val sent: List<String>, val error: String? = null)

    /**
     * One batch. [call] is the provider call — `(method, extras) -> reply`, null when there was no answer
     * at all — kept as a seam so the whole exchange can be tested without a second app on the device.
     */
    fun pushBatch(call: (String, Bundle) -> Bundle?): Pushed {
        val pending = outbox.pending().take(MAX_ITEMS)
        if (pending.isEmpty()) return Pushed(emptyList())
        val items = JSONArray()
        for (item in pending) items.put(item.toJson())
        val extras = Bundle().apply { putString(AutomationProvider.KEY_ITEMS, items.toString()) }
        val reply = call(METHOD_INTAKE, extras) ?: return Pushed(emptyList(), "ERROR:no answer")
        val result = reply.getString(AutomationProvider.KEY_RESULT) ?: return Pushed(emptyList(), "ERROR:no result")
        if (!result.startsWith(OK)) return Pushed(emptyList(), result)
        // Only what it named: a uuid missing from the list stays queued and goes out again next time.
        val offered = pending.map { it.uuid }.toHashSet()
        val acked = result.removePrefix(OK).split(',').map { it.trim() }.filter { it in offered }
        outbox.markSent(acked)
        return Pushed(acked)
    }

    /** Every batch, until the queue is empty or something refuses. */
    fun pushAll(call: (String, Bundle) -> Bundle?): Pushed {
        val sent = mutableListOf<String>()
        repeat(MAX_BATCHES) {
            val batch = pushBatch(call)
            if (batch.error != null) return Pushed(sent, batch.error)
            if (batch.sent.isEmpty()) return Pushed(sent)
            sent.addAll(batch.sent)
        }
        return Pushed(sent)
    }

    /** The same, through a real [ContentResolver]: an unreachable door is a refusal, never a crash. */
    fun pushAll(resolver: ContentResolver): Pushed = pushAll { method, extras ->
        runCatching {
            @Suppress("DEPRECATION")
            resolver.call(AUTHORITY, method, null, extras)
        }.getOrNull()
    }

    // ---- the pull fallback, on our own door -------------------------------------------------------------

    /** The queue as a §2 `items` array, oldest first, for 自由作業盤 to store and then acknowledge. */
    fun pullItems(limit: Int): String {
        val items = JSONArray()
        for (item in outbox.pending().take(limit.coerceIn(1, MAX_ITEMS))) items.put(item.toJson())
        return items.toString()
    }

    /**
     * 自由作業盤 says it stored these uuids. Only ones actually waiting are dropped and remembered: a uuid
     * we never queued is ignored rather than recorded as gone, or a sentence kept later under that uuid
     * would be silently refused by the outbox for ever.
     */
    fun ack(itemsJson: String?): String {
        val uuids = parseUuids(itemsJson) ?: return "ERROR:items"
        val waiting = outbox.pending().map { it.uuid }.toHashSet()
        val dropped = uuids.filter { it in waiting }
        outbox.markSent(dropped)
        return OK + dropped.joinToString(",")
    }

    private fun parseUuids(itemsJson: String?): List<String>? {
        if (itemsJson.isNullOrBlank()) return null
        return try {
            val arr = JSONArray(itemsJson)
            (0 until arr.length())
                .map { i -> arr.optString(i) }
                .filter { VoiceCaptureStore.isSafeUuid(it) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** 自由作業盤's data door and the one method on it we are allowed to call. */
        const val AUTHORITY = "shiroikuma.jiyusagyoban.automation"
        const val METHOD_INTAKE = "gengoshima_intake"

        /** The §2 batch cap. */
        const val MAX_ITEMS = 50

        /** 25 batches is 1 250 sentences — a bound, not an expectation. */
        private const val MAX_BATCHES = 25

        private const val OK = "OK:"
    }
}
