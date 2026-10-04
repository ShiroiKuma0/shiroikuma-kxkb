package com.urik.keyboard.service

import android.content.res.Configuration
import com.urik.keyboard.settings.KeySize
import com.urik.keyboard.ui.keyboard.components.VoiceReviewColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The four voice-review mark colours through the whole knob chain. A knob that is missed in one of these
 * steps looks like a setting that silently does not stick, which is exactly the class of bug the dead
 * sliders of +222 were — so each step is pinned here rather than trusted.
 */
class KeyboardLookKnobsVoiceMarksTest {
    private val set = KeyboardLookKnobs(
        voiceMarkUnsureColor = 0xFFAA0000.toInt(),
        voiceMarkUnknownColor = 0xFFFFFFFF.toInt(),
        voiceMarkReplacedColor = 0xFF1E5AFF.toInt(),
        voiceMarkCorrectedColor = 0xFF00D084.toInt()
    )

    @Test
    fun `the marks survive encode and decode`() {
        val decoded = KeyboardLookKnobs.decode(set.encode())

        assertEquals(0xFFAA0000.toInt(), decoded.voiceMarkUnsureColor)
        assertEquals(0xFFFFFFFF.toInt(), decoded.voiceMarkUnknownColor)
        assertEquals(0xFF1E5AFF.toInt(), decoded.voiceMarkReplacedColor)
        assertEquals(0xFF00D084.toInt(), decoded.voiceMarkCorrectedColor)
    }

    @Test
    fun `unset marks stay unset, so the built-in defaults keep showing`() {
        val decoded = KeyboardLookKnobs.decode(KeyboardLookKnobs().encode())

        assertNull(decoded.voiceMarkUnsureColor)
        assertNull(decoded.voiceMarkUnknownColor)
        assertNull(decoded.voiceMarkReplacedColor)
        assertNull(decoded.voiceMarkCorrectedColor)
    }

    @Test
    fun `an override wins over the baseline, and the baseline fills the rest`() {
        val baseline = KeyboardLookKnobs(voiceMarkUnsureColor = 0xFF111111.toInt())
        val override = KeyboardLookKnobs(voiceMarkUnknownColor = 0xFF222222.toInt())

        val merged = baseline.overlay(override)

        assertEquals(0xFF111111.toInt(), merged.voiceMarkUnsureColor)
        assertEquals(0xFF222222.toInt(), merged.voiceMarkUnknownColor)
        assertNull(merged.voiceMarkReplacedColor)
    }

    @Test
    fun `the marks are STYLE, so apply-to-all fans them to every keyboard`() {
        val style = set.styleOnly()

        assertEquals(set.voiceMarkUnsureColor, style.voiceMarkUnsureColor)
        assertEquals(set.voiceMarkUnknownColor, style.voiceMarkUnknownColor)
        assertEquals(set.voiceMarkReplacedColor, style.voiceMarkReplacedColor)
        assertEquals(set.voiceMarkCorrectedColor, style.voiceMarkCorrectedColor)
        // ... and not dimensions, which stay confined to the geometry they were edited on.
        assertNull(set.dimensionOnly().voiceMarkUnsureColor)
    }

    @Test
    fun `a changed mark is the only thing the delta carries`() {
        val before = KeyboardLookKnobs(keyHeightScale = 1.2f, voiceMarkUnsureColor = 0xFF111111.toInt())
        val after = before.copy(voiceMarkUnsureColor = 0xFF222222.toInt())

        val delta = after.changedFrom(before)

        assertEquals(0xFF222222.toInt(), delta.voiceMarkUnsureColor)
        assertNull(delta.keyHeightScale)
    }

    @Test
    fun `the resolved dimensions carry the marks the keyboard draws with`() {
        val base = AdaptiveDimensions.compute(
            PostureInfo(
                sizeClass = DeviceSizeClass.COMPACT,
                posture = DevicePosture.NORMAL,
                screenWidthPx = 1080,
                screenHeightPx = 2400,
                isTablet = false,
                orientation = Configuration.ORIENTATION_PORTRAIT
            ),
            KeySize.MEDIUM,
            2.75f
        )

        val dims = set.applyTo(base, density = 2.75f)

        assertEquals(0xFFAA0000.toInt(), dims.voiceMarkUnsureColor)
        assertEquals(0xFFFFFFFF.toInt(), dims.voiceMarkUnknownColor)
        assertEquals(0xFF1E5AFF.toInt(), dims.voiceMarkReplacedColor)
        assertEquals(0xFF00D084.toInt(), dims.voiceMarkCorrectedColor)
        // A keyboard given no knob draws the built-in default instead.
        assertNull(KeyboardLookKnobs().applyTo(base, density = 2.75f).voiceMarkUnsureColor)
        assertNotNull(VoiceReviewColors.UNSURE)
    }
}
