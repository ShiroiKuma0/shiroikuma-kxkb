package com.urik.keyboard.automation

import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.data.VoiceCaptureStore
import com.urik.keyboard.markUserUnlocked
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * `voice_capture_offer`: the answer 自由作業盤 acts on. `OK:<uuid>` is its permission to delete its only copy
 * of that recording, so the list must name exactly the clips that are on our disk — and a batch where one
 * clip is broken must still acknowledge the others.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceCaptureOfferTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = VoiceCaptureStore(context)
    private val offer = VoiceCaptureOffer(store)

    @Before
    fun clean() {
        context.markUserUnlocked()
        File(context.filesDir, VoiceCaptureStore.DIR).deleteRecursively()
    }

    @Test
    fun `a batch of good clips is accepted whole`() {
        val clips = (1..3).map { "u$it" to wav() }
        val answer = offer.apply(itemsOf(clips), opener(clips))

        assertEquals("OK:u1,u2,u3", answer.result)
        assertNull(answer.rejected)
        assertEquals(3, store.entries().size)
    }

    @Test
    fun `one broken clip is rejected by name and the rest are still acknowledged`() {
        val good = listOf("good1" to wav(), "good2" to wav())
        val broken = "broken" to wav()
        val items = JSONArray()
        good.forEachIndexed { index, (uuid, bytes) -> items.put(item(uuid, bytes, index)) }
        // A checksum of something else entirely: the copy happens, the verification fails, the file goes.
        items.put(item(broken.first, broken.second, 2).put("sha256", "11".repeat(32)))

        val answer = offer.apply(items.toString(), opener(good + broken))

        assertEquals("OK:good1,good2", answer.result)
        assertEquals("broken:sha256", answer.rejected)
        assertEquals(setOf("good1", "good2"), store.entries().map { it.uuid }.toSet())
    }

    @Test
    fun `a missing descriptor is rejected per uuid`() {
        val clips = listOf("here" to wav(), "gone" to wav())
        val answer = offer.apply(itemsOf(clips)) { key ->
            if (key == "fd_1") null else opener(clips)(key)
        }

        assertEquals("OK:here", answer.result)
        assertEquals("gone:nofd", answer.rejected)
    }

    @Test
    fun `a declared format we did not agree on is rejected without reading a byte`() {
        val clip = wav()
        val items = JSONArray().put(item("odd", clip, 0).put("sampleRate", 44100))
        var opened = false

        val answer = offer.apply(items.toString()) {
            opened = true
            null
        }

        assertEquals("OK:", answer.result)
        assertEquals("odd:format", answer.rejected)
        assertFalse(opened)
    }

    @Test
    fun `a clip we already hold is answered OK so the other side can delete it`() {
        val clips = listOf("twice" to wav())
        assertEquals("OK:twice", offer.apply(itemsOf(clips), opener(clips)).result)

        val again = offer.apply(itemsOf(clips), opener(clips))

        assertEquals("OK:twice", again.result)
        assertNull(again.rejected)
        assertEquals(1, store.entries().size)
    }

    @Test
    fun `more than the batch cap, or an unparseable payload, is one whole-call error`() {
        val many = (1..VoiceCaptureOffer.MAX_ITEMS + 1).map { "u$it" to wav() }
        assertEquals("ERROR:items", offer.apply(itemsOf(many), opener(many)).result)
        assertEquals("ERROR:items", offer.apply("not json at all") { null }.result)
        assertEquals("ERROR:items", offer.apply(null) { null }.result)
        assertEquals("OK:", offer.apply("[]") { null }.result)
    }

    @Test
    fun `an offer that would not fit the budget is refused whole`() {
        val clip = wav()
        val items = JSONArray().put(item("huge", clip, 0).put("byteLength", 600L * 1024 * 1024))

        val answer = offer.apply(items.toString(), opener(listOf("huge" to clip)))

        assertEquals("ERROR:budget", answer.result)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `before the first unlock nothing can be taken in`() {
        Shadows.shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val clips = listOf("locked" to wav())

        assertEquals("ERROR:locked", offer.apply(itemsOf(clips), opener(clips)).result)
    }

    // ---- the offer's shape -----------------------------------------------------------------------------

    private fun itemsOf(clips: List<Pair<String, ByteArray>>): String {
        val items = JSONArray()
        clips.forEachIndexed { index, (uuid, bytes) -> items.put(item(uuid, bytes, index)) }
        return items.toString()
    }

    private fun item(uuid: String, bytes: ByteArray, index: Int): JSONObject = JSONObject()
        .put("uuid", uuid)
        .put("fd", "fd_$index")
        .put("capturedAt", 1_700_000_000_000L)
        .put("durationMs", 1000)
        .put("language", "en")
        .put("sampleRate", 16000)
        .put("channels", 1)
        .put("bitsPerSample", 16)
        .put("byteLength", bytes.size.toLong())
        .put("sha256", sha(bytes))

    private fun opener(clips: List<Pair<String, ByteArray>>): (String) -> InputStream? = { key ->
        key.removePrefix("fd_").toIntOrNull()?.let { index ->
            clips.getOrNull(index)?.second?.let(::ByteArrayInputStream)
        }
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun wav(seconds: Double = 1.0): ByteArray {
        val count = (16000 * seconds).toInt()
        val dataBytes = count * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        for (i in 0 until count) buffer.putShort(if (i % 2 == 0) 6000 else -6000)
        return buffer.array()
    }
}
