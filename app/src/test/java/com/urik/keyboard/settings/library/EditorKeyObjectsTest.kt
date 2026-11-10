package com.urik.keyboard.settings.library

import com.urik.keyboard.data.KeyboardJsonCodec
import com.urik.keyboard.model.KeyboardKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Opening a slide in the key editor must leave what the slide does untouched. */
@RunWith(RobolectricTestRunner::class)
class EditorKeyObjectsTest {
    private fun clusterWithDown(down: Any): KeyboardKey.FlickKey = KeyboardJsonCodec.parseKey(
        JSONObject().put("type", "cluster").put("cluster", "sh)").put("char", "h")
            .put("flick", JSONObject().put("down", down)),
        KeyboardKey.ActionType.ENTER
    ) as KeyboardKey.FlickKey

    private fun assertOpeningKeeps(binding: JSONObject) {
        val before = clusterWithDown(binding)
        val opened = clusterWithDown(EditorKeyObjects.asKeyObject(JSONObject(binding.toString()), inSlide = true))
        assertEquals(before.down, opened.down)
        assertEquals(before.bindings["down"], opened.bindings["down"])
    }

    @Test
    fun `an action slide stays the action with its label`() {
        assertOpeningKeeps(JSONObject().put("action", "tab").put("label", "⇥"))
        assertEquals("⇥", clusterWithDown(JSONObject().put("action", "tab").put("label", "⇥")).down)
    }

    @Test
    fun `a chord slide stays the chord`() = assertOpeningKeeps(JSONObject().put("chord", "C-e").put("label", "End"))

    @Test
    fun `a layer slide stays the layer`() = assertOpeningKeeps(JSONObject().put("layer", "alt0").put("label", "Num"))

    @Test
    fun `a text slide stays its text`() = assertOpeningKeeps(JSONObject().put("text", "/"))

    @Test
    fun `outside a slide a binding object is read as the character it shows`() {
        val cell = EditorKeyObjects.asKeyObject(JSONObject().put("action", "tab").put("label", "⇥"))
        assertEquals("character", cell.getString("type"))
        assertEquals("⇥", cell.getString("char"))
    }
}
