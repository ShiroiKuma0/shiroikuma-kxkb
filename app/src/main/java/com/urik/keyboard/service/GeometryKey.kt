package com.urik.keyboard.service

import android.content.res.Configuration

/**
 * The geometry buckets the keyboard tunes look/sizing against: the device **fold state × orientation**.
 *
 * 白い熊's Mate XT (tri-fold) genuinely has three fold states — **folded** (outer/cover screen),
 * **semi-folded** (hinge half-open), **unfolded** (inner display fully open) — each in portrait or
 * landscape. A non-foldable phone only ever resolves to the FOLDED_* buckets (its single screen).
 */
enum class GeometryBucket(val key: String) {
    FOLDED_PORT("folded_port"),
    FOLDED_LAND("folded_land"),
    SEMI_PORT("semi_port"),
    SEMI_LAND("semi_land"),
    UNFOLDED_PORT("unfolded_port"),
    UNFOLDED_LAND("unfolded_land");

    companion object {
        fun from(posture: PostureInfo): GeometryBucket {
            val landscape = posture.orientation == Configuration.ORIENTATION_LANDSCAPE
            // Fold state comes from the HALL sensor (or screen-area fallback); see PostureDetector.
            return when (posture.foldState) {
                FoldState.FOLDED -> if (landscape) FOLDED_LAND else FOLDED_PORT
                FoldState.SEMI_FOLDED -> if (landscape) SEMI_LAND else SEMI_PORT
                FoldState.UNFOLDED -> if (landscape) UNFOLDED_LAND else UNFOLDED_PORT
            }
        }

        fun fromKey(key: String): GeometryBucket? = entries.firstOrNull { it.key == key }

        /**
         * Best-effort bucket from a [Configuration] alone — the settings-UI fallback used only until the
         * live geometry from the running keyboard is known. A [Configuration] can't sense the hinge, so
         * it can only tell folded (small screen) from unfolded (large) — never semi-folded.
         */
        fun fromConfiguration(config: Configuration): GeometryBucket {
            val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
            val large = config.smallestScreenWidthDp >= 600 || config.screenWidthDp >= 840
            return when {
                large && landscape -> UNFOLDED_LAND
                large -> UNFOLDED_PORT
                landscape -> FOLDED_LAND
                else -> FOLDED_PORT
            }
        }
    }
}

/**
 * THE single geometry-key builder. The look store, the Keyboard UI screen and the live watcher must all
 * call this — never reconstruct the bucket string inline anywhere, or the three drift and edits silently
 * miss (futokxkb's hardest-won rule for this subsystem).
 */
fun geometryKey(posture: PostureInfo): String = GeometryBucket.from(posture).key
