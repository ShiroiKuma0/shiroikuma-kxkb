package com.urik.keyboard.ui.keyboard.components

import android.os.VibrationEffect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HapticSignatureTest {
    // ── Duration floors ───────────────────────────────────────────────────────

    @Test
    fun `LetterClick duration is at least 32ms`() {
        assertTrue(HapticSignature.LetterClick.durationMs >= 32L)
    }

    @Test
    fun `PunctuationTick duration is at least 28ms`() {
        assertTrue(HapticSignature.PunctuationTick.durationMs >= 28L)
    }

    @Test
    fun `BackspaceChirp duration is at least 28ms`() {
        assertTrue(HapticSignature.BackspaceChirp.durationMs >= 28L)
    }

    @Test
    fun `SpaceThump duration is at least 35ms`() {
        assertTrue(HapticSignature.SpaceThump.durationMs >= 35L)
    }

    @Test
    fun `ShiftPulse duration is at least 42ms`() {
        assertTrue(HapticSignature.ShiftPulse.durationMs >= 42L)
    }

    @Test
    fun `EnterCompletion duration is at least 51ms`() {
        assertTrue(HapticSignature.EnterCompletion.durationMs >= 51L)
    }

    @Test
    fun `NumberClick duration is at least 25ms`() {
        assertTrue(HapticSignature.NumberClick.durationMs >= 25L)
    }

    // ── Duration hierarchy ────────────────────────────────────────────────────

    @Test
    fun `LetterClick duration is greater than PunctuationTick duration`() {
        assertTrue(HapticSignature.LetterClick.durationMs > HapticSignature.PunctuationTick.durationMs)
    }

    @Test
    fun `PunctuationTick duration is less than BackspaceChirp duration`() {
        assertTrue(HapticSignature.PunctuationTick.durationMs < HapticSignature.BackspaceChirp.durationMs)
    }

    // ── DEFAULT_AMPLITUDE routing ─────────────────────────────────────────────

    @Test
    fun `LetterClick createEffect with DEFAULT_AMPLITUDE does not throw`() {
        val effect = HapticSignature.LetterClick.createEffect(VibrationEffect.DEFAULT_AMPLITUDE)
        assertNotNull(effect)
    }

    @Test
    fun `PunctuationTick createEffect with DEFAULT_AMPLITUDE does not throw`() {
        val effect = HapticSignature.PunctuationTick.createEffect(VibrationEffect.DEFAULT_AMPLITUDE)
        assertNotNull(effect)
    }

    // ── Strength drives the pulse length as well as the amplitude ─────────────

    @Test
    fun `durationScaleFor maps the default strength to 1 and the maximum to 2`() {
        assertEquals(1f, HapticSignature.durationScaleFor(128), 0.001f)
        assertEquals(2f, HapticSignature.durationScaleFor(255), 0.01f)
        // The floor: a barely-there setting still yields a pulse, never a zero-length one.
        assertEquals(0.25f, HapticSignature.durationScaleFor(1), 0.001f)
    }

    @Test
    fun `full strength doubles every segment - the leading delay stays zero`() {
        val scaled = HapticSignature.scaledTimings(longArrayOf(0, 15, 20), HapticSignature.durationScaleFor(255))
        assertArrayEquals(longArrayOf(0, 30, 40), scaled)
    }

    @Test
    fun `the weakest strength never produces a zero-length segment`() {
        val scaled = HapticSignature.scaledTimings(longArrayOf(0, 3, 1), HapticSignature.durationScaleFor(1))
        assertArrayEquals(longArrayOf(0, 1, 1), scaled)
    }

    @Test
    fun `every signature builds at full strength with and without amplitude control`() {
        val scale = HapticSignature.durationScaleFor(255)
        val all = listOf(
            HapticSignature.LetterClick, HapticSignature.SpaceThump, HapticSignature.BackspaceChirp,
            HapticSignature.ShiftPulse, HapticSignature.EnterCompletion, HapticSignature.PunctuationTick,
            HapticSignature.VoicePulse, HapticSignature.VoiceFlipDouble, HapticSignature.NumberClick
        )
        all.forEach { signature ->
            assertNotNull(signature.createEffect(255, scale))
            assertNotNull(signature.createEffect(VibrationEffect.DEFAULT_AMPLITUDE, scale))
        }
    }
}
