package com.urik.keyboard.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LayoutResyncTest {
    private fun ctrlOf(merged: JSONObject, row: Int, col: Int): JSONObject =
        merged.getJSONObject("modes").getJSONObject("letters")
            .getJSONArray("rows").getJSONArray(row).getJSONObject(col)

    @Test
    fun `pulls in a new flick direction on a matched key, keeping the user's edit`() {
        val shadow = JSONObject(
            """{"modes":{"letters":{"rows":[[
                {"type":"compass","char":"Ctrl","flick":{"up":{"layer":"alt0","label":"sym"}}},
                {"type":"compass","char":"x","appearance":{"color":123}}
            ]]}}}"""
        )
        val stock = JSONObject(
            """{"modes":{"letters":{"rows":[[
                {"type":"compass","char":"Ctrl","flick":{"up":{"layer":"alt0","label":"sym"},"right":{"layer":"alt3","label":"Num"}}},
                {"type":"compass","char":"x"}
            ]]}}}"""
        )

        val merged = LayoutResync.mergeFromStock(shadow, stock)
        val flick = ctrlOf(merged, 0, 0).getJSONObject("flick")
        assertTrue("alt3 right flick pulled in", flick.has("right"))
        assertEquals("alt3", flick.getJSONObject("right").getString("layer"))
        assertEquals("existing sym flick untouched", "alt0", flick.getJSONObject("up").getString("layer"))
        assertEquals("user edit on the x key preserved", 123, ctrlOf(merged, 0, 1).getJSONObject("appearance").getInt("color"))
    }

    @Test
    fun `never overwrites an existing user binding on the same direction`() {
        val shadow = JSONObject(
            """{"modes":{"letters":{"rows":[[{"type":"compass","char":"Ctrl","flick":{"right":{"layer":"alt2","label":"mine"}}}]]}}}"""
        )
        val stock = JSONObject(
            """{"modes":{"letters":{"rows":[[{"type":"compass","char":"Ctrl","flick":{"right":{"layer":"alt3","label":"Num"}}}]]}}}"""
        )
        val merged = LayoutResync.mergeFromStock(shadow, stock)
        assertEquals("alt2", ctrlOf(merged, 0, 0).getJSONObject("flick").getJSONObject("right").getString("layer"))
    }

    @Test
    fun `copies in a whole mode the shadow lacks`() {
        val shadow = JSONObject("""{"modes":{"letters":{"rows":[[]]}}}""")
        val stock = JSONObject(
            """{"modes":{"letters":{"rows":[[]]},"symbols":{"rows":[[{"type":"compass","char":"@"}]]}}}"""
        )
        val merged = LayoutResync.mergeFromStock(shadow, stock)
        assertTrue(merged.getJSONObject("modes").has("symbols"))
    }

    @Test
    fun `matches a key that drifted column within its row`() {
        // The user inserted a key before Ctrl: it sits at index 1 in the shadow but index 0 in stock.
        val shadow = JSONObject(
            """{"modes":{"letters":{"rows":[[
                {"type":"compass","char":"NEW"},
                {"type":"compass","char":"Ctrl","flick":{"up":{"layer":"alt0"}}}
            ]]}}}"""
        )
        val stock = JSONObject(
            """{"modes":{"letters":{"rows":[[
                {"type":"compass","char":"Ctrl","flick":{"up":{"layer":"alt0"},"right":{"layer":"alt3"}}}
            ]]}}}"""
        )
        val merged = LayoutResync.mergeFromStock(shadow, stock)
        assertTrue(ctrlOf(merged, 0, 1).getJSONObject("flick").has("right"))
    }

    @Test
    fun `inserts new long-press candidates at their stock positions`() {
        // The +231 i-key case: stock gained ″ between the lowercase and uppercase glyph groups.
        val shadow = JSONObject(
            """{"modes":{"letters":{"rows":[[
                {"type":"compass","char":"i","longPressKeys":["í","ī","ï","Í","Ī","Ï"]}
            ]]}}}"""
        )
        val stock = JSONObject(
            """{"modes":{"letters":{"rows":[[
                {"type":"compass","char":"i","longPressKeys":["í","ī","ï","″","Í","Ī","Ï"]}
            ]]}}}"""
        )
        val merged = LayoutResync.mergeFromStock(shadow, stock)
        val row = ctrlOf(merged, 0, 0).getJSONArray("longPressKeys")
        assertEquals(listOf("í", "ī", "ï", "″", "Í", "Ī", "Ï"), (0 until row.length()).map { row.getString(it) })
    }

    @Test
    fun `creates the long-press row when the shadow has none and keeps user reordering`() {
        val shadow = JSONObject(
            """{"modes":{"letters":{"rows":[[
                {"type":"compass","char":"f"},
                {"type":"compass","char":"r","longPressKeys":["Ř","ř"]}
            ]]}}}"""
        )
        val stock = JSONObject(
            """{"modes":{"letters":{"rows":[[
                {"type":"compass","char":"f","longPressKeys":["′","℉"]},
                {"type":"compass","char":"r","longPressKeys":["ř","√","Ř"]}
            ]]}}}"""
        )
        val merged = LayoutResync.mergeFromStock(shadow, stock)
        val fRow = ctrlOf(merged, 0, 0).getJSONArray("longPressKeys")
        assertEquals(listOf("′", "℉"), (0 until fRow.length()).map { fRow.getString(it) })
        // The user's own order (Ř before ř) survives; the new √ lands after its stock predecessor ř.
        val rRow = ctrlOf(merged, 0, 1).getJSONArray("longPressKeys")
        assertEquals(listOf("Ř", "ř", "√"), (0 until rRow.length()).map { rRow.getString(it) })
    }

    @Test
    fun `copies in a shifted face the shadow lacks and merges one it has`() {
        val shadow = JSONObject(
            """{"modes":{"letters":{"rows":[[
                {"type":"compass","char":"i","longPressKeys":["í"]},
                {"type":"compass","char":"f","shifted":{"type":"compass","char":"F","flick":{"up":"F"}}}
            ]]}}}"""
        )
        val stock = JSONObject(
            """{"modes":{"letters":{"rows":[[
                {"type":"compass","char":"i","shifted":{"type":"compass","char":"I","longPressKeys":["Í","″"]}},
                {"type":"compass","char":"f","shifted":{"type":"compass","char":"F","flick":{"up":"F"},"longPressKeys":["℉","′"]}}
            ]]}}}"""
        )
        val merged = LayoutResync.mergeFromStock(shadow, stock)
        // i: the whole new face arrives verbatim.
        val iShifted = ctrlOf(merged, 0, 0).getJSONObject("shifted")
        assertEquals("I", iShifted.getString("char"))
        assertEquals("″", iShifted.getJSONArray("longPressKeys").getString(1))
        // f: the existing face is merged in place, gaining the stock long-press row.
        val fShifted = ctrlOf(merged, 0, 1).getJSONObject("shifted")
        assertEquals("F", fShifted.getString("char"))
        assertEquals(listOf("℉", "′"), fShifted.getJSONArray("longPressKeys").let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    @Test
    fun `does not mutate the input shadow`() {
        val shadow = JSONObject(
            """{"modes":{"letters":{"rows":[[{"type":"compass","char":"Ctrl","flick":{}}]]}}}"""
        )
        val stock = JSONObject(
            """{"modes":{"letters":{"rows":[[{"type":"compass","char":"Ctrl","flick":{"right":{"layer":"alt3"}}}]]}}}"""
        )
        LayoutResync.mergeFromStock(shadow, stock)
        val flick = shadow.getJSONObject("modes").getJSONObject("letters")
            .getJSONArray("rows").getJSONArray(0).getJSONObject(0).getJSONObject("flick")
        assertFalse("shadow input left untouched", flick.has("right"))
    }
}
