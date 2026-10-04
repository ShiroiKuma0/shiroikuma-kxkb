package com.urik.keyboard.automation

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.data.VoiceHandoffOutbox
import com.urik.keyboard.markUserUnlocked
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The hand-over exchange, both directions. The rule under test throughout: a sentence leaves our queue ONLY
 * when the other side names it as stored. Everything else — a refusal, an app that is not installed, a
 * phone that answered nothing, a uuid we never offered — leaves the queue as it was.
 */
@RunWith(RobolectricTestRunner::class)
class GengoshimaHandoffTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val outbox = VoiceHandoffOutbox(context)
    private val handoff = GengoshimaHandoff(outbox)

    @Before
    fun clean() {
        context.markUserUnlocked()
        File(context.filesDir, VoiceHandoffOutbox.DIR).deleteRecursively()
    }

    private fun queue(vararg uuids: String) {
        uuids.forEachIndexed { index, uuid ->
            outbox.add(VoiceHandoffOutbox.Item(uuid, "Sentence $uuid.", "sentence $uuid", "en", index + 1L, 0L))
        }
    }

    private fun reply(result: String?): Bundle = Bundle().apply {
        if (result != null) putString(AutomationProvider.KEY_RESULT, result)
    }

    @Test
    fun `a batch goes out in the agreed shape and the acked sentences leave the queue`() {
        queue("a", "b")
        var seenMethod: String? = null
        var seenItems: String? = null

        val pushed = handoff.pushBatch { method, extras ->
            seenMethod = method
            seenItems = extras.getString(AutomationProvider.KEY_ITEMS)
            reply("OK:a,b")
        }

        assertEquals(GengoshimaHandoff.METHOD_INTAKE, seenMethod)
        val items = JSONArray(seenItems)
        assertEquals(2, items.length())
        assertEquals("a", items.getJSONObject(0).getString("uuid"))
        assertEquals("Sentence a.", items.getJSONObject(0).getString("text"))
        assertEquals(listOf("a", "b"), pushed.sent)
        assertNull(pushed.error)
        assertTrue(outbox.pending().isEmpty())
        assertEquals(2, outbox.sentCount())
    }

    @Test
    fun `a sentence left out of the answer stays queued`() {
        queue("a", "b", "c")

        val pushed = handoff.pushBatch { _, _ -> reply("OK:a,c") }

        assertEquals(listOf("a", "c"), pushed.sent)
        assertEquals(listOf("b"), outbox.pending().map { it.uuid })
    }

    @Test
    fun `a refusal leaves everything queued and is reported verbatim`() {
        queue("a", "b")

        val pushed = handoff.pushBatch { _, _ -> reply("ERROR:items") }

        assertEquals("ERROR:items", pushed.error)
        assertTrue(pushed.sent.isEmpty())
        assertEquals(2, outbox.pendingCount())
    }

    @Test
    fun `a door that is not there, or answers nothing, is a refusal and not a loss`() {
        queue("a")

        assertEquals("ERROR:no answer", handoff.pushBatch { _, _ -> null }.error)
        assertEquals("ERROR:no result", handoff.pushBatch { _, _ -> reply(null) }.error)
        assertEquals(1, outbox.pendingCount())
    }

    @Test
    fun `a uuid we never offered is ignored rather than recorded as gone`() {
        queue("mine")

        val pushed = handoff.pushBatch { _, _ -> reply("OK:mine,someone-elses") }

        assertEquals(listOf("mine"), pushed.sent)
        assertEquals(0, outbox.pendingCount())
        assertEquals(1, outbox.sentCount())
    }

    @Test
    fun `an empty queue is not a call at all`() {
        var called = false

        val pushed = handoff.pushBatch { _, _ ->
            called = true
            reply("OK:")
        }

        assertTrue(!called)
        assertTrue(pushed.sent.isEmpty())
        assertNull(pushed.error)
    }

    @Test
    fun `more than one batch goes out until the queue is empty`() {
        queue(*(1..60).map { "u$it" }.toTypedArray())
        val batches = mutableListOf<Int>()

        val pushed = handoff.pushAll { _, extras ->
            val items = JSONArray(extras.getString(AutomationProvider.KEY_ITEMS))
            batches.add(items.length())
            val uuids = (0 until items.length()).map { items.getJSONObject(it).getString("uuid") }
            reply("OK:" + uuids.joinToString(","))
        }

        assertEquals(listOf(GengoshimaHandoff.MAX_ITEMS, 10), batches)
        assertEquals(60, pushed.sent.size)
        assertTrue(outbox.pending().isEmpty())
    }

    @Test
    fun `a batch that refuses stops the run and keeps what already went`() {
        queue(*(1..60).map { "u$it" }.toTypedArray())
        var batch = 0

        val pushed = handoff.pushAll { _, extras ->
            batch++
            if (batch == 1) {
                val items = JSONArray(extras.getString(AutomationProvider.KEY_ITEMS))
                val uuids = (0 until items.length()).map { items.getJSONObject(it).getString("uuid") }
                reply("OK:" + uuids.joinToString(","))
            } else {
                reply("ERROR:items")
            }
        }

        assertEquals(GengoshimaHandoff.MAX_ITEMS, pushed.sent.size)
        assertEquals("ERROR:items", pushed.error)
        assertEquals(10, outbox.pendingCount())
    }

    // ---- the pull fallback ------------------------------------------------------------------------------

    @Test
    fun `a pull reads the queue without changing it, capped at the batch size`() {
        queue(*(1..60).map { "u$it" }.toTypedArray())

        val items = JSONArray(handoff.pullItems(500))

        assertEquals(GengoshimaHandoff.MAX_ITEMS, items.length())
        assertEquals("u1", items.getJSONObject(0).getString("uuid"))
        assertEquals(60, outbox.pendingCount())
    }

    @Test
    fun `an ack drops only what was waiting and is idempotent`() {
        queue("a", "b")

        assertEquals("OK:a", handoff.ack(JSONArray(listOf("a", "never-queued")).toString()))
        assertEquals(listOf("b"), outbox.pending().map { it.uuid })

        // Said twice (a lost reply on their side): nothing left to drop, and no phantom memory of the
        // uuid we never had — a sentence kept under it later must still be able to queue.
        assertEquals("OK:", handoff.ack(JSONArray(listOf("a")).toString()))
        assertEquals(1, outbox.sentCount())
        queue("never-queued")
        assertTrue(outbox.pending().any { it.uuid == "never-queued" })
    }

    @Test
    fun `an unparseable ack is one error`() {
        assertEquals("ERROR:items", handoff.ack("not json"))
        assertEquals("ERROR:items", handoff.ack(null))
    }
}
