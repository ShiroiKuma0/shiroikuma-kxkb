package com.urik.keyboard.ui.keyboard.components

import android.os.VibrationEffect
import kotlin.math.roundToLong

/**
 * The per-key vibration shapes. Each is a short amplitude waveform whose length is scaled by
 * [durationScale] — the "Vibration strength" setting drives BOTH the amplitude (where the vibrator
 * supports amplitude control) and the pulse length, because a ~30 ms pulse is faint on most motors even
 * at amplitude 255, and on a vibrator WITHOUT amplitude control (typical on Huawei) the length is the only
 * thing the setting can change at all. Scale 1.0 = the original shapes (strength 128); 2.0 = strength 255.
 */
sealed class HapticSignature {
    abstract val durationMs: Long

    abstract fun createEffect(baseAmplitude: Int, durationScale: Float = 1f): VibrationEffect

    protected fun createAmplitudeEffect(
        timings: LongArray,
        amplitudes: IntArray,
        totalDurationMs: Long,
        baseAmplitude: Int,
        durationScale: Float
    ): VibrationEffect = if (baseAmplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
        VibrationEffect.createOneShot(scaled(totalDurationMs, durationScale), VibrationEffect.DEFAULT_AMPLITUDE)
    } else {
        VibrationEffect.createWaveform(scaledTimings(timings, durationScale), amplitudes, -1)
    }


    data object LetterClick : HapticSignature() {
        override val durationMs = 32L

        override fun createEffect(baseAmplitude: Int, durationScale: Float): VibrationEffect {
            val amplitude = baseAmplitude.coerceIn(1, 255)
            return createAmplitudeEffect(
                longArrayOf(0, 32),
                intArrayOf(0, amplitude),
                durationMs,
                baseAmplitude,
                durationScale
            )
        }
    }

    data object SpaceThump : HapticSignature() {
        override val durationMs = 35L

        override fun createEffect(baseAmplitude: Int, durationScale: Float): VibrationEffect {
            val peakAmplitude = baseAmplitude.coerceIn(1, 255)
            val startAmplitude = (peakAmplitude * 0.5).toInt()
            return createAmplitudeEffect(
                longArrayOf(0, 15, 20),
                intArrayOf(0, startAmplitude, peakAmplitude),
                durationMs,
                baseAmplitude,
                durationScale
            )
        }
    }

    data object BackspaceChirp : HapticSignature() {
        override val durationMs = 30L

        override fun createEffect(baseAmplitude: Int, durationScale: Float): VibrationEffect {
            val startAmplitude = baseAmplitude.coerceIn(1, 255)
            val endAmplitude = (startAmplitude * 0.4).toInt()
            return createAmplitudeEffect(
                longArrayOf(0, 15, 15),
                intArrayOf(0, startAmplitude, endAmplitude),
                durationMs,
                baseAmplitude,
                durationScale
            )
        }
    }

    data object ShiftPulse : HapticSignature() {
        override val durationMs = 42L

        override fun createEffect(baseAmplitude: Int, durationScale: Float): VibrationEffect {
            val amplitude = (baseAmplitude * 0.9).toInt().coerceIn(1, 255)
            return createAmplitudeEffect(
                longArrayOf(0, 15, 12, 15),
                intArrayOf(0, amplitude, 0, amplitude),
                durationMs,
                baseAmplitude,
                durationScale
            )
        }
    }

    data object EnterCompletion : HapticSignature() {
        override val durationMs = 51L

        override fun createEffect(baseAmplitude: Int, durationScale: Float): VibrationEffect {
            val peakAmplitude = baseAmplitude.coerceIn(1, 255)
            val midAmplitude = (peakAmplitude * 0.7).toInt()
            return createAmplitudeEffect(
                longArrayOf(0, 12, 25, 14),
                intArrayOf(0, midAmplitude, peakAmplitude, midAmplitude),
                durationMs,
                baseAmplitude,
                durationScale
            )
        }
    }

    data object PunctuationTick : HapticSignature() {
        override val durationMs = 28L

        override fun createEffect(baseAmplitude: Int, durationScale: Float): VibrationEffect {
            val amplitude = (baseAmplitude * 0.7).toInt().coerceIn(1, 255)
            return createAmplitudeEffect(
                longArrayOf(0, 28),
                intArrayOf(0, amplitude),
                durationMs,
                baseAmplitude,
                durationScale
            )
        }
    }

    /** Mic-key press: a firm, clearly felt single pulse (recording is about to start/stop). */
    data object VoicePulse : HapticSignature() {
        override val durationMs = 60L

        override fun createEffect(baseAmplitude: Int, durationScale: Float): VibrationEffect {
            val amplitude = baseAmplitude.coerceIn(1, 255)
            return createAmplitudeEffect(
                longArrayOf(0, 60),
                intArrayOf(0, amplitude),
                durationMs,
                baseAmplitude,
                durationScale
            )
        }
    }

    /** Mic-key long-press: two short ticks — the voice language flipped. */
    data object VoiceFlipDouble : HapticSignature() {
        override val durationMs = 130L

        override fun createEffect(baseAmplitude: Int, durationScale: Float): VibrationEffect {
            // Always a waveform — the one-shot fallback would blur the double tick into one buzz.
            val amplitude =
                if (baseAmplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
                    VibrationEffect.DEFAULT_AMPLITUDE
                } else {
                    baseAmplitude.coerceIn(1, 255)
                }
            return VibrationEffect.createWaveform(
                scaledTimings(longArrayOf(0, 30, 70, 30), durationScale),
                intArrayOf(0, amplitude, 0, amplitude),
                -1
            )
        }
    }

    data object NumberClick : HapticSignature() {
        override val durationMs = 25L

        override fun createEffect(baseAmplitude: Int, durationScale: Float): VibrationEffect {
            val amplitude =
                if (baseAmplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
                    VibrationEffect.DEFAULT_AMPLITUDE
                } else {
                    (baseAmplitude * 0.85).toInt().coerceIn(1, 255)
                }
            return VibrationEffect.createOneShot(scaled(durationMs, durationScale), amplitude)
        }
    }

    companion object {
        /** The strength the original signature lengths were tuned at; 255 maps to twice that. */
        private const val REFERENCE_STRENGTH = 128f
        private const val MIN_DURATION_SCALE = 0.25f
        private const val MAX_DURATION_SCALE = 2f

        /** The pulse-length factor for a "Vibration strength" setting (1–255): 128 → 1.0, 255 → 2.0. */
        fun durationScaleFor(strength: Int): Float =
            (strength / REFERENCE_STRENGTH).coerceIn(MIN_DURATION_SCALE, MAX_DURATION_SCALE)

        internal fun scaledTimings(timings: LongArray, durationScale: Float): LongArray =
            LongArray(timings.size) { scaled(timings[it], durationScale) }

        /** A segment length under [durationScale]; a zero (the leading delay) stays zero, anything else ≥ 1 ms. */
        internal fun scaled(ms: Long, durationScale: Float): Long =
            if (ms == 0L) 0L else (ms * durationScale).roundToLong().coerceAtLeast(1L)
    }
}
