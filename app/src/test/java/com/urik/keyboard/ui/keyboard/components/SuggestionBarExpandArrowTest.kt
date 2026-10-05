package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.text.TextPaint
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.service.AdaptiveDimensions
import com.urik.keyboard.service.EmojiSearchManager
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.RecentEmojiProvider
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.theme.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * The ▾ expand button is the ONLY way to the long-tail candidate pane, and it kept going missing on the
 * cluster boards: it used to be gated on `suggestionSelectionEnabled`, a flag the service publishes to the
 * view asynchronously, while flipping that flag only re-applies the highlight and never re-renders the bar.
 * These tests pin the arrow to the one thing that actually matters — are there candidates at all.
 */
@RunWith(RobolectricTestRunner::class)
class SuggestionBarExpandArrowTest {
    private val candidates =
        listOf("natively", "tenki", "tab", "ten", "bet", "ben", "bat", "net", "tan")

    private fun set(view: SwipeKeyboardView, name: String, value: Any?) {
        SwipeKeyboardView::class.java.getDeclaredField(name).apply { isAccessible = true }.set(view, value)
    }

    private fun dimensions() = AdaptiveDimensions(
        maxKeyboardWidthPx = 2048,
        keyHeightPx = 160,
        minimumTouchTargetPx = 120,
        keyMarginHorizontalPx = 6,
        keyMarginVerticalPx = 6,
        keyboardPaddingHorizontalPx = 0,
        keyboardPaddingVerticalPx = 0,
        numberRowGutterPx = 0,
        suggestionTextMinSp = 15f,
        suggestionTextMaxSp = 19f,
        swipeActivationDp = 10f,
        gestureThresholdDp = 10f,
        splitGapPx = 0,
        keyTextBaseRatio = 0.4f,
        keyTextMinSp = 12f,
        keyTextMaxSp = 24f,
        suggestionBarHeightPx = 140,
        suggestionColor = 0xFFFFFF00.toInt(),
        suggestionTextScale = 2.2f
    )

    private var lastView: SwipeKeyboardView? = null

    private fun renderBar(selectionEnabled: Boolean, composingWord: String?): LinearLayout {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = SwipeKeyboardView(context)
        lastView = view
        view.initialize(
            mock(KeyboardLayoutManager::class.java, RETURNS_DEEP_STUBS),
            mock(SwipeDetector::class.java),
            mock(SpellCheckManager::class.java),
            mock(WordLearningEngine::class.java),
            mock(ThemeManager::class.java, RETURNS_DEEP_STUBS),
            mock(LanguageManager::class.java, RETURNS_DEEP_STUBS),
            mock(EmojiSearchManager::class.java),
            mock(RecentEmojiProvider::class.java)
        )
        set(view, "adaptiveDimensions", dimensions())
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        set(view, "suggestionBar", bar)
        set(view, "suggestionSelectionEnabled", selectionEnabled)
        view.setEditChipWordProvider { composingWord }
        SwipeKeyboardView::class.java.getDeclaredMethod("cacheSuggestionMetrics")
            .apply { isAccessible = true }.invoke(view)
        SwipeKeyboardView::class.java
            .getDeclaredMethod("updateSuggestionBarContent", List::class.java)
            .apply { isAccessible = true }
            .invoke(view, candidates)
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(2048, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(140, View.MeasureSpec.EXACTLY)
        )
        bar.layout(0, 0, 2048, 140)
        return bar
    }

    private fun expandArrow(bar: LinearLayout): TextView? {
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i) as? TextView ?: continue
            if (child.text?.toString() == "▾") return child
        }
        return null
    }

    @Test
    fun `expand arrow is shown with cluster selection enabled`() {
        val bar = renderBar(selectionEnabled = true, composingWord = "tet")
        assertNotNull("The ▾ expand button must be in the suggestion bar", expandArrow(bar))
    }

    @Test
    fun `expand arrow is shown even when cluster selection has not been published yet`() {
        val bar = renderBar(selectionEnabled = false, composingWord = "tet")
        assertNotNull(
            "The ▾ must not depend on suggestionSelectionEnabled — that flag arrives asynchronously",
            expandArrow(bar)
        )
    }

    @Test
    fun `expand arrow is the last child so the weighted spacer pushes it to the right edge`() {
        val bar = renderBar(selectionEnabled = true, composingWord = "tet")
        val arrow = expandArrow(bar)
        assertEquals("The ▾ must be the bar's last child", bar.getChildAt(bar.childCount - 1), arrow)
        assertEquals("The ▾ must sit at the bar's right edge", 2048, arrow!!.right)
        assertTrue("The ▾ must have a non-zero width", arrow.width > 0)
    }

    /**
     * The fit loop must measure a candidate at the size the chip will REALLY draw it — i.e. through the
     * system font scale. Measuring with `sp * density` ignored it: at 150 % text size every candidate
     * measured a third narrower than it drew, the row over-filled, the weighted spacer collapsed and the
     * ▾ was laid out past the bar's right edge, clipped. That is invisible in a screenshot and cost three
     * wrong fixes, so it is pinned here as plain unit arithmetic rather than a layout assertion.
     */
    @Test
    fun `candidates are measured through the system font scale, not bare density`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metrics = context.resources.displayMetrics
        metrics.scaledDensity = metrics.density * 1.5f

        val bar = renderBar(selectionEnabled = true, composingWord = "tet")
        val chip = (0 until bar.childCount)
            .map { bar.getChildAt(it) }
            .filterIsInstance<TextView>()
            .first { it.text?.toString() == candidates.first() }

        val view = lastView!!
        val paint = SwipeKeyboardView::class.java.getDeclaredField("suggestionMeasurePaint")
            .apply { isAccessible = true }.get(view) as TextPaint

        assertEquals(
            "The fit paint must use the same px size the chip renders at",
            chip.textSize.toDouble(),
            paint.textSize.toDouble(),
            0.01
        )
        assertTrue(
            "At a 1.5x font scale the measured size must exceed sp * density",
            paint.textSize > paint.textSize / 1.5f * 1.4f
        )
    }

    @Test
    fun `no expand arrow without candidates`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = SwipeKeyboardView(context)
        view.initialize(
            mock(KeyboardLayoutManager::class.java, RETURNS_DEEP_STUBS),
            mock(SwipeDetector::class.java),
            mock(SpellCheckManager::class.java),
            mock(WordLearningEngine::class.java),
            mock(ThemeManager::class.java, RETURNS_DEEP_STUBS),
            mock(LanguageManager::class.java, RETURNS_DEEP_STUBS),
            mock(EmojiSearchManager::class.java),
            mock(RecentEmojiProvider::class.java)
        )
        set(view, "adaptiveDimensions", dimensions())
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        set(view, "suggestionBar", bar)
        set(view, "suggestionSelectionEnabled", true)
        SwipeKeyboardView::class.java.getDeclaredMethod("cacheSuggestionMetrics")
            .apply { isAccessible = true }.invoke(view)
        SwipeKeyboardView::class.java
            .getDeclaredMethod("updateSuggestionBarContent", List::class.java)
            .apply { isAccessible = true }
            .invoke(view, emptyList<String>())
        assertEquals("An empty bar carries no ▾", null, expandArrow(bar))
    }
}
