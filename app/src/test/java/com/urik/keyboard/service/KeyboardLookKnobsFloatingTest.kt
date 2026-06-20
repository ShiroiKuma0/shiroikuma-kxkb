package com.urik.keyboard.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Guards the floating-panel rect knobs through the encode/decode + overlay round-trips. */
class KeyboardLookKnobsFloatingTest {
    @Test
    fun `floating fields survive encode-decode round trip`() {
        val knobs = KeyboardLookKnobs(
            floatXFraction = 0.25f,
            floatYFraction = 0.8f,
            floatWidthFraction = 0.7f,
            floatHeightScale = 1.4f
        )

        val decoded = KeyboardLookKnobs.decode(knobs.encode())

        assertEquals(0.25f, decoded.floatXFraction)
        assertEquals(0.8f, decoded.floatYFraction)
        assertEquals(0.7f, decoded.floatWidthFraction)
        assertEquals(1.4f, decoded.floatHeightScale)
    }

    @Test
    fun `unset floating fields stay null after round trip`() {
        val decoded = KeyboardLookKnobs.decode(KeyboardLookKnobs().encode())

        assertNull(decoded.floatXFraction)
        assertNull(decoded.floatYFraction)
        assertNull(decoded.floatWidthFraction)
        assertNull(decoded.floatHeightScale)
    }

    @Test
    fun `overlay lets the override floating rect win and fills gaps from the base`() {
        val base = KeyboardLookKnobs(floatXFraction = 0.1f, floatWidthFraction = 0.9f)
        val over = KeyboardLookKnobs(floatXFraction = 0.5f, floatHeightScale = 2f)

        val result = base.overlay(over)

        assertEquals(0.5f, result.floatXFraction) // override wins
        assertEquals(0.9f, result.floatWidthFraction) // base fills the gap
        assertEquals(2f, result.floatHeightScale) // only override has it
    }
}
