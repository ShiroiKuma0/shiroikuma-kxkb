package com.urik.keyboard.service

/**
 * The geometry rules for one inline-autofill chip, in one place because the two halves of the path must
 * agree: the IME asks the autofill service to render the chip at a size, and the suggestion bar then has
 * to lay the returned view out at exactly the size that came back.
 *
 * A chip is an `InlineContentView` — a SurfaceView filled by the autofill service's own process — and it
 * does not override `onMeasure`. Measured UNSPECIFIED (which is what a `HorizontalScrollView` does to its
 * children), `WRAP_CONTENT` therefore resolves to the suggested minimum, zero, and the chip renders as
 * nothing at all while the rest of the bar draws normally. Only an exact pixel size works.
 */
object InlineChipSizing {
    /**
     * The height to inflate a chip at: the height we want (the live suggestion-bar height), clamped into
     * the presentation spec THIS suggestion was matched with — an action chip carries the narrow icon
     * spec, and a size outside the spec makes `InlineSuggestion.inflate` throw, losing the chip.
     */
    fun inflateHeight(desiredHeight: Int, specMinHeight: Int, specMaxHeight: Int): Int =
        if (specMinHeight > specMaxHeight) {
            specMaxHeight
        } else {
            desiredHeight.coerceIn(specMinHeight, specMaxHeight)
        }

    /**
     * One extent (width or height) to lay a returned chip out at: the pixel size the framework reported
     * as the view's layout params, or [fallback] when there is none to carry over.
     */
    fun layoutExtent(remoteExtent: Int?, fallback: Int): Int = remoteExtent?.takeIf { it > 0 } ?: fallback
}
