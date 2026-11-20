package com.urik.keyboard.settings.library

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The comparison that decides whether the editor shows its Apply/Cancel bar. */
@RunWith(RobolectricTestRunner::class)
class EditorChromeTest {
    @Test
    fun `key order is not a change`() {
        val a = JSONObject().put("type", "compass").put("char", "z")
            .put("flick", JSONObject().put("up", "Z").put("right", "ž"))
        val b = JSONObject().put("flick", JSONObject().put("right", "ž").put("up", "Z"))
            .put("char", "z").put("type", "compass")
        assertEquals(EditorChrome.canonical(a), EditorChrome.canonical(b))
    }

    @Test
    fun `a field removed and typed back is not a change`() {
        val a = JSONObject().put("char", "z").put("slide", "x").put("type", "compass")
        val before = EditorChrome.canonical(a)
        a.remove("slide")
        assertNotEquals(before, EditorChrome.canonical(a))
        a.put("slide", "x")
        assertEquals(before, EditorChrome.canonical(a))
    }

    @Test
    fun `the long-press list is compared in order`() {
        val a = JSONObject().put("longPressKeys", JSONArray(listOf("ž", "ź")))
        val b = JSONObject().put("longPressKeys", JSONArray(listOf("ź", "ž")))
        assertNotEquals(EditorChrome.canonical(a), EditorChrome.canonical(b))
        assertEquals(
            EditorChrome.canonical(a),
            EditorChrome.canonical(JSONObject().put("longPressKeys", JSONArray(listOf("ž", "ź"))))
        )
    }

    @Test
    fun `a whole number and its decimal form are one number`() {
        // A width of 2 read back from its field is 2.0; that must not raise the Apply/Cancel bar.
        assertEquals(
            EditorChrome.canonical(JSONObject().put("width", 2)),
            EditorChrome.canonical(JSONObject().put("width", 2.0))
        )
        assertNotEquals(
            EditorChrome.canonical(JSONObject().put("width", 2)),
            EditorChrome.canonical(JSONObject().put("width", 2.5))
        )
    }

    private fun layout(row: String) = JSONObject("""{"modes":{"letters":{"rows":[$row]}}}""")

    @Test
    fun `a key that was only opened is not a change`() {
        // Opening a key stores it in the editor's long form; the keyboard reads both the same.
        val before = layout("""["a", {"type":"compass","char":"z","flick":{"up":"Z","down":{"action":"paste","label":"P"},"left":{"chord":"M-z"}}}]""")
        val opened = layout(
            """[{"type":"character","char":"a"}, {"type":"compass","char":"z","flick":{
                "up":{"type":"character","char":"Z"},
                "down":{"type":"action","action":"paste","label":"P"},
                "left":{"type":"chord","keys":"M-z"}}}]"""
        )
        assertEquals(EditorChrome.comparable(before), EditorChrome.comparable(opened))
    }

    @Test
    fun `what a key page applied is a change`() {
        val before = layout("""[{"type":"compass","char":"z","longPressKeys":["ž","ź"]}]""")
        val edited = layout("""[{"type":"compass","char":"z","longPressKeys":["ž","ź","ż"]}]""")
        assertNotEquals(EditorChrome.comparable(before), EditorChrome.comparable(edited))
        val widened = layout("""[{"type":"compass","char":"z","longPressKeys":["ž","ź"],"width":2.0}]""")
        assertNotEquals(EditorChrome.comparable(before), EditorChrome.comparable(widened))
        val slide = layout("""[{"type":"compass","char":"z","longPressKeys":["ž","ź"],"flick":{"up":"Z"}}]""")
        assertNotEquals(EditorChrome.comparable(before), EditorChrome.comparable(slide))
    }
}
