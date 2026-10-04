package com.urik.keyboard.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The inline-autofill chip geometry. A chip is a surface-backed `InlineContentView` with no `onMeasure`
 * of its own, so the bar must lay it out at the exact pixel size the framework reported — a WRAP_CONTENT
 * chip inside the bar's scroller measures to ZERO and renders nothing (0.23.1+335 and earlier).
 */
class InlineChipSizingTest {
    @Test
    fun `inflate height takes the wanted bar height when the spec allows it`() {
        assertEquals(150, InlineChipSizing.inflateHeight(desiredHeight = 150, specMinHeight = 60, specMaxHeight = 200))
    }

    @Test
    fun `inflate height is clamped up to the spec minimum`() {
        assertEquals(97, InlineChipSizing.inflateHeight(desiredHeight = 40, specMinHeight = 97, specMaxHeight = 200))
    }

    @Test
    fun `inflate height is clamped down to the spec maximum`() {
        assertEquals(97, InlineChipSizing.inflateHeight(desiredHeight = 300, specMinHeight = 78, specMaxHeight = 97))
    }

    @Test
    fun `inflate height survives a spec whose minimum exceeds its maximum`() {
        assertEquals(60, InlineChipSizing.inflateHeight(desiredHeight = 150, specMinHeight = 100, specMaxHeight = 60))
    }

    @Test
    fun `layout extent carries the reported remote size over`() {
        assertEquals(365, InlineChipSizing.layoutExtent(remoteExtent = 365, fallback = -2))
    }

    @Test
    fun `layout extent falls back when the framework reported nothing`() {
        assertEquals(-2, InlineChipSizing.layoutExtent(remoteExtent = null, fallback = -2))
    }

    @Test
    fun `layout extent falls back on a zero or negative remote size`() {
        assertEquals(-1, InlineChipSizing.layoutExtent(remoteExtent = 0, fallback = -1))
        assertEquals(-1, InlineChipSizing.layoutExtent(remoteExtent = -2, fallback = -1))
    }
}
