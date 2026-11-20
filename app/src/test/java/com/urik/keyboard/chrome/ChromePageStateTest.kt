package com.urik.keyboard.chrome

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a stored section order is laid over the sections a build actually has. */
class ChromePageStateTest {
    private val built = listOf("export", "voice", "geometry", "keys", "maintenance")

    @Test
    fun `no stored order is the built order`() {
        assertEquals(built, ChromePageState.merge(emptyList(), built))
    }

    @Test
    fun `a stored order is kept`() {
        val saved = listOf("keys", "export", "maintenance", "voice", "geometry")
        assertEquals(saved, ChromePageState.merge(saved, built))
    }

    @Test
    fun `a section a later build adds lands after its built predecessor`() {
        // Saved before "geometry" existed: it goes in behind "voice", where the build has it.
        val saved = listOf("keys", "voice", "export", "maintenance")
        assertEquals(
            listOf("keys", "voice", "geometry", "export", "maintenance"),
            ChromePageState.merge(saved, built)
        )
    }

    @Test
    fun `a new first section goes to the top`() {
        assertEquals(
            listOf("export", "keys", "voice"),
            ChromePageState.merge(listOf("keys", "voice"), listOf("export", "voice", "keys"))
        )
    }

    @Test
    fun `a section that is gone and a repeated key are dropped`() {
        val saved = listOf("keys", "retired", "keys", "export", "voice", "geometry", "maintenance")
        assertEquals(
            listOf("keys", "export", "voice", "geometry", "maintenance"),
            ChromePageState.merge(saved, built)
        )
    }
}
