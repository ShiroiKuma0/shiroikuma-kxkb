package com.urik.keyboard.data

import com.urik.keyboard.model.KeyboardKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The width the key editor writes is the width the keyboard draws: a `case` key is drawn by the width of its
 * `normal` face, by its own only where that face has none, and the per-key appearance numbers the editor now lets you type reach the model.
 */
@RunWith(RobolectricTestRunner::class)
class KeyWidthCodecTest {
    private fun parse(json: String): KeyboardKey =
        KeyboardJsonCodec.parseKey(JSONObject(json), KeyboardKey.ActionType.ENTER)

    @Test
    fun `a case key is as wide as its normal face`() {
        // The bundled en 9c cluster boards say 1.5 on the case and 2.0 on the face, and are drawn 2.0 wide.
        val key = parse(
            """{"type":"case","width":1.5,
                "normal":{"type":"cluster","char":"e","main":"aev","width":2.0}}"""
        ) as KeyboardKey.FlickKey
        assertEquals(2.0f, key.width, 0.0001f)
    }

    @Test
    fun `a case width counts where the normal face has none`() {
        val key = parse(
            """{"type":"case","width":1.5,
                "normal":{"type":"compass","char":"a"},
                "shifted":{"type":"compass","char":"A"}}"""
        ) as KeyboardKey.FlickKey
        assertEquals(1.5f, key.width, 0.0001f)
    }

    @Test
    fun `a case key whose normal face is an action takes the case width too`() {
        val key = parse(
            """{"type":"case","width":1.5,"normal":{"type":"action","action":"shift"}}"""
        ) as KeyboardKey.Action
        assertEquals(1.5f, key.width, 0.0001f)
    }

    @Test
    fun `a case key survives a round trip with its width`() {
        val key = parse(
            """{"type":"case","width":1.5,
                "normal":{"type":"compass","char":"a"},
                "shifted":{"type":"compass","char":"A"}}"""
        )
        val emitted = KeyboardJsonCodec.emitKey(key)
        assertEquals(key, KeyboardJsonCodec.parseKey(emitted, KeyboardKey.ActionType.ENTER))
    }

    @Test
    fun `an action key keeps its width`() {
        val key = parse("""{"type":"action","action":"space","width":4.0}""") as KeyboardKey.Action
        assertEquals(4.0f, key.width, 0.0001f)
        assertEquals(4.0, KeyboardJsonCodec.emitKey(key).getDouble("width"), 0.0001)
    }

    @Test
    fun `typed appearance numbers reach the key`() {
        val key = parse(
            """{"type":"compass","char":"Esc","appearance":{"fontScale":0.6,"hintScale":0.75,
                "labelOffsetX":-0.05,"labelOffsetY":0.12,"flickTopOffset":0.26,"flickLeftOffset":0.1}}"""
        ) as KeyboardKey.FlickKey
        val a = key.appearance
        assertNotNull(a)
        assertEquals(0.6f, a!!.fontScale!!, 0.0001f)
        assertEquals(0.75f, a.hintScale!!, 0.0001f)
        assertEquals(-0.05f, a.labelOffsetX!!, 0.0001f)
        assertEquals(0.12f, a.labelOffsetY!!, 0.0001f)
        assertEquals(0.26f, a.flickTopOffset!!, 0.0001f)
        assertEquals(0.1f, a.flickLeftOffset!!, 0.0001f)
    }

    @Test
    fun `the character class the editor writes is read`() {
        val digit = parse("""{"type":"compass","char":"1","keyType":"number"}""") as KeyboardKey.FlickKey
        assertEquals(KeyboardKey.KeyType.NUMBER, digit.type)
        val plain = parse("""{"type":"compass","char":"1"}""") as KeyboardKey.FlickKey
        assertEquals(KeyboardKey.KeyType.LETTER, plain.type)
    }
}
