package com.urik.keyboard.data

import android.content.Context
import com.urik.keyboard.model.KeyboardKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Verifies the [KeyboardJsonCodec] round-trip contract on every shipped layout: parsing a key, emitting it,
 * and parsing the emitted JSON yields an equal model. This is the lossless guarantee the editor and the
 * converter rely on — nothing the model represents may be dropped or altered by a save.
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardJsonCodecTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `every shipped layout key round-trips through parse-emit-parse`() {
        val files = context.assets.list("layouts").orEmpty()
            .filter { it.endsWith(".json") && it != "registry.json" }
        assertEquals("expected the codec test to see the bundled layouts", true, files.isNotEmpty())

        var keysChecked = 0
        for (file in files) {
            val root = JSONObject(
                context.assets.open("layouts/$file").bufferedReader().use { it.readText() }
            )
            val modes = root.optJSONObject("modes") ?: continue
            for (modeKey in modes.keys()) {
                val rows = modes.getJSONObject(modeKey).getJSONArray("rows")
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONArray(r)
                    for (c in 0 until row.length()) {
                        val keyJson = row.getJSONObject(c)
                        val model = KeyboardJsonCodec.parseKey(keyJson, KeyboardKey.ActionType.ENTER)
                        val reparsed = KeyboardJsonCodec.parseKey(
                            KeyboardJsonCodec.emitKey(model), KeyboardKey.ActionType.ENTER
                        )
                        assertEquals(
                            "round-trip mismatch in $file/$modeKey row $r col $c", model, reparsed
                        )
                        // Emission must also be stable (idempotent) once in the model.
                        assertEquals(
                            "emit not stable in $file/$modeKey row $r col $c",
                            KeyboardJsonCodec.emitKey(model).toString(),
                            KeyboardJsonCodec.emitKey(reparsed).toString()
                        )
                        keysChecked++
                    }
                }
            }
        }
        assertEquals("expected to have checked many keys", true, keysChecked > 100)
    }

    @Test
    fun `multi-state case round-trips and derives the generic shifted face`() {
        val json = JSONObject(
            """
            {
              "type": "case",
              "normal": {"type": "flick", "char": "8", "keyType": "number", "flick": {"up": "´", "down": "ˇ"}},
              "shiftedManually": {"type": "flick", "char": "´", "keyType": "symbol"},
              "shiftLocked": {"type": "flick", "char": "¨", "keyType": "symbol"},
              "symbols": {"type": "flick", "char": "*", "keyType": "symbol"}
            }
            """.trimIndent()
        )
        val m1 = KeyboardJsonCodec.parseKey(json, KeyboardKey.ActionType.ENTER) as KeyboardKey.FlickKey
        // Base face is `normal`; the distinct states populate caseFaces.
        assertEquals("8", m1.center)
        assertEquals("´", m1.caseFaces["shiftedManually"]?.center)
        assertEquals("¨", m1.caseFaces["shiftLocked"]?.center)
        assertEquals("*", m1.caseFaces["symbols"]?.center)
        // No explicit "shifted" branch → the generic shifted face derives from shiftedManually.
        assertEquals("´", m1.shifted?.center)

        val m2 = KeyboardJsonCodec.parseKey(
            KeyboardJsonCodec.emitKey(m1), KeyboardKey.ActionType.ENTER
        )
        assertEquals("case key must survive a parse→emit→parse round-trip", m1, m2)
    }

    @Test
    fun `column key round-trips with vertical band and derived ends`() {
        val json = JSONObject(
            """{"type": "column", "main": "abc", "char": "b", "keyType": "letter", "flick": {"left": "(", "right": ")"}}"""
        )
        val m1 = KeyboardJsonCodec.parseKey(json, KeyboardKey.ActionType.ENTER) as KeyboardKey.FlickKey
        assertEquals(true, m1.columnar)
        assertEquals("abc", m1.clusterMains)
        assertEquals("b", m1.center)
        // The band ends fall onto the up/down slides when not given explicitly.
        assertEquals("a", m1.up)
        assertEquals("c", m1.down)
        assertEquals("(", m1.left)

        val emitted = KeyboardJsonCodec.emitKey(m1)
        assertEquals("column", emitted.getString("type"))
        assertEquals("abc", emitted.getString("main"))
        val m2 = KeyboardJsonCodec.parseKey(emitted, KeyboardKey.ActionType.ENTER)
        assertEquals("column key must survive a parse→emit→parse round-trip", m1, m2)
    }

    @Test
    fun `per-key appearance round-trips with colours and scales`() {
        val json = JSONObject(
            """{"type": "character", "char": "q", "keyType": "letter",
                "appearance": {"color": "#FFFF0000", "fontScale": 1.5, "backgroundColor": "#FF101010",
                               "borderColor": "#FFFFFF00", "labelOffsetX": 0.1}}"""
        )
        val m1 = KeyboardJsonCodec.parseKey(json, KeyboardKey.ActionType.ENTER)
        val app = m1.appearance!!
        assertEquals(0xFFFF0000.toInt(), app.color)
        assertEquals(0xFF101010.toInt(), app.backgroundColor)
        assertEquals(0xFFFFFF00.toInt(), app.borderColor)
        assertEquals(1.5f, app.fontScale!!, 0.0001f)
        assertEquals(0.1f, app.labelOffsetX!!, 0.0001f)

        val m2 = KeyboardJsonCodec.parseKey(
            KeyboardJsonCodec.emitKey(m1), KeyboardKey.ActionType.ENTER
        )
        assertEquals("appearance must survive a parse→emit→parse round-trip", m1, m2)
    }

    @Test
    fun `per-key attributes round-trip and accept futokxkb aliases`() {
        val json = JSONObject(
            """{"type": "cluster", "main": "aev", "char": "e",
                "attributes": {"width": "Custom2", "style": "Action", "shiftable": false,
                               "rowSpan": 2, "showPopup": true}}"""
        )
        val m1 = KeyboardJsonCodec.parseKey(json, KeyboardKey.ActionType.ENTER)
        val attrs = m1.attributes!!
        assertEquals("Custom2", attrs.widthClass) // accepted via the futokxkb `width` alias
        assertEquals("Action", attrs.style)
        assertEquals(false, attrs.shiftable)
        assertEquals(true, attrs.showPopup)
        assertEquals(2f, attrs.heightRows!!, 0.0001f) // accepted via the `rowSpan` alias

        val m2 = KeyboardJsonCodec.parseKey(
            KeyboardJsonCodec.emitKey(m1), KeyboardKey.ActionType.ENTER
        )
        assertEquals("attributes must survive a parse→emit→parse round-trip", m1, m2)
    }

    @Test
    fun `macro chord and cycle tap keys parse and round-trip`() {
        val macro = KeyboardJsonCodec.parseKey(
            JSONObject("""{"type": "macro", "text": "1ˢᵗ", "keyType": "symbol"}"""),
            KeyboardKey.ActionType.ENTER
        ) as KeyboardKey.FlickKey
        assertEquals(KeyboardKey.TapKind.MACRO, macro.tapKind)
        assertEquals("1ˢᵗ", macro.center)

        val chord = KeyboardJsonCodec.parseKey(
            JSONObject("""{"type": "chord", "keys": "C-x C-s", "label": "Save"}"""),
            KeyboardKey.ActionType.ENTER
        ) as KeyboardKey.FlickKey
        assertEquals(KeyboardKey.TapKind.CHORD, chord.tapKind)
        assertEquals("Save", chord.center)
        assertEquals(
            "C-x C-s",
            (chord.bindings["center"] as KeyboardKey.FlickBinding.Chord).spec
        )

        val cycle = KeyboardJsonCodec.parseKey(
            JSONObject("""{"type": "cycle", "taps": "–—-", "label": "–"}"""),
            KeyboardKey.ActionType.ENTER
        ) as KeyboardKey.FlickKey
        assertEquals(KeyboardKey.TapKind.CYCLE, cycle.tapKind)
        assertEquals(listOf("–", "—", "-"), cycle.cycleTaps)

        for (key in listOf(macro, chord, cycle)) {
            val rt = KeyboardJsonCodec.parseKey(
                KeyboardJsonCodec.emitKey(key), KeyboardKey.ActionType.ENTER
            )
            assertEquals("tap key must survive a parse→emit→parse round-trip", key, rt)
        }
    }
}
