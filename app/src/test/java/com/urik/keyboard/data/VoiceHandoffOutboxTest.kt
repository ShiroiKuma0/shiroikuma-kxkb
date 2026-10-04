package com.urik.keyboard.data

import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.markUserUnlocked
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The hand-over queue. Its whole job is that a reviewed sentence is neither lost nor filed twice: it leaves
 * only when 言語島 has said it holds it, and a uuid already handed over never queues again.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceHandoffOutboxTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val outbox = VoiceHandoffOutbox(context)

    @Before
    fun clean() {
        context.markUserUnlocked()
        File(context.filesDir, VoiceHandoffOutbox.DIR).deleteRecursively()
    }

    private fun item(uuid: String, text: String = "A sentence.", capturedAt: Long = 1L) =
        VoiceHandoffOutbox.Item(uuid, text, "a sentence", "en", capturedAt, 99L)

    @Test
    fun `sentences wait in the order they were spoken`() {
        outbox.add(item("c", capturedAt = 30))
        outbox.add(item("a", capturedAt = 10))
        outbox.add(item("b", capturedAt = 20))

        assertEquals(listOf("a", "b", "c"), outbox.pending().map { it.uuid })
        assertEquals(3, outbox.pendingCount())
        assertEquals(0, outbox.sentCount())
    }

    @Test
    fun `keeping the same sentence again replaces what is queued`() {
        outbox.add(item("one", text = "I walk to the river"))
        outbox.add(item("one", text = "I walked to the river"))

        val queued = outbox.pending().single()
        assertEquals("I walked to the river", queued.text)
    }

    @Test
    fun `a handed-over sentence leaves the queue and never comes back`() {
        outbox.add(item("gone"))
        outbox.add(item("stays", capturedAt = 2))

        outbox.markSent(listOf("gone"))

        assertEquals(listOf("stays"), outbox.pending().map { it.uuid })
        assertEquals(1, outbox.sentCount())

        // 言語島 already has it; queueing it again would ask 白い熊 to file the same sentence twice.
        outbox.add(item("gone"))
        assertEquals(listOf("stays"), outbox.pending().map { it.uuid })
    }

    @Test
    fun `the queue and the memory survive a reload`() {
        outbox.add(item("kept", text = "Something said outside", capturedAt = 77))
        outbox.add(item("sent"))
        outbox.markSent(listOf("sent"))

        val reloaded = VoiceHandoffOutbox(context)
        val queued = reloaded.pending().single()
        assertEquals("kept", queued.uuid)
        assertEquals("Something said outside", queued.text)
        assertEquals("a sentence", queued.recognized)
        assertEquals("en", queued.language)
        assertEquals(77L, queued.capturedAt)
        assertEquals(1, reloaded.sentCount())
    }

    @Test
    fun `the wire item carries exactly the agreed fields`() {
        val json = item("u1", text = "Final text.").toJson()

        assertEquals("u1", json.getString("uuid"))
        assertEquals("Final text.", json.getString("text"))
        assertEquals("a sentence", json.getString("recognized"))
        assertEquals("en", json.getString("language"))
        assertEquals(1L, json.getLong("capturedAt"))
    }

    @Test
    fun `a sentence with no text is not a sentence`() {
        outbox.add(item("blank", text = "   "))
        // Written, but unreadable as an item — it can never be handed over as an empty sentence.
        assertTrue(outbox.pending().none { it.uuid == "blank" })
    }
}
