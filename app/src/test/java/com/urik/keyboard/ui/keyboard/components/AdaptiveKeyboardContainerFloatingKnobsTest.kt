package com.urik.keyboard.ui.keyboard.components

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.urik.keyboard.model.KeyboardModeConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Guards the cold-start regression where the docked keyboard came up clipped ("half") on the first show:
 * the per-geometry look resolve calls [AdaptiveKeyboardContainer.applyFloatingKnobs] right after
 * [AdaptiveKeyboardContainer.applyLookKnobs], and that floating call used to fire a second, redundant
 * mode re-apply on the docked path that raced the window re-measure. The fix makes [applyFloatingKnobs]
 * inert (store-only, no re-apply) in any non-floating mode while still repositioning a live floating panel.
 *
 * The observable proxy for "did the floating recipe run" is the keyboard view's height layout param:
 * - docked (STANDARD/SPLIT) leaves it WRAP_CONTENT with topMargin 0,
 * - FLOATING sets an explicit pixel height + topMargin.
 */
@RunWith(RobolectricTestRunner::class)
class AdaptiveKeyboardContainerFloatingKnobsTest {

    /** A child that reports a fixed natural height, like the keyboard view at a resolved key-height scale. */
    private class StubKeyboardView(context: Context) : View(context) {
        override fun onMeasure(w: Int, hSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), 600)
        }
    }

    /** Attach the container under a real Activity window (1080 wide) so its GlobalLayout listener fires and
     *  `containerWidth` is established — only then does a mode apply actually run. */
    private fun attachedContainer(config: KeyboardModeConfig): Pair<AdaptiveKeyboardContainer, StubKeyboardView> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(activity)
        val container = AdaptiveKeyboardContainer(activity)
        host.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val child = StubKeyboardView(activity)
        container.setKeyboardView(child)
        container.setModeConfig(config)
        activity.setContentView(host)

        // A measure+layout pass with a real window dispatches the global layout → sets containerWidth.
        host.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        host.layout(0, 0, 1080, 1920)
        container.viewTreeObserver.dispatchOnGlobalLayout()
        shadowOf(Looper.getMainLooper()).idle()
        return container to child
    }

    @Test
    fun `applyFloatingKnobs is inert on the docked (standard) path`() {
        val (container, child) = attachedContainer(KeyboardModeConfig.standard())

        container.applyLookKnobs(1.0f, 0)
        container.applyFloatingKnobs(0.2f, 0.3f, 0.6f, 1.5f)

        val lp = child.layoutParams as FrameLayout.LayoutParams
        // Docked: the keyboard view keeps its natural (wrap-content) height and no top margin — i.e. the
        // floating recipe never ran, so it cannot perturb the docked measure / window size.
        assertEquals(FrameLayout.LayoutParams.WRAP_CONTENT, lp.height)
        assertEquals(0, lp.topMargin)
    }

    @Test
    fun `applyFloatingKnobs repositions the panel in floating mode`() {
        val (container, child) = attachedContainer(KeyboardModeConfig.floating())

        container.applyFloatingKnobs(0.2f, 0.3f, 0.6f, 1.5f)

        val lp = child.layoutParams as FrameLayout.LayoutParams
        // Floating: the panel gets an explicit pixel size + position (an EXPLICIT height, not WRAP_CONTENT)…
        assert(lp.height > 0) { "floating panel should have an explicit pixel height, was ${lp.height}" }
        // …and its width is the requested 60% fraction of the container's own width (so the floating recipe,
        // unlike the docked path, DID honour applyFloatingKnobs).
        assertEquals((container.width * 0.6f).toInt(), lp.width)
        assert(lp.width in 1 until container.width) {
            "floating width ${lp.width} should be a strict fraction of container ${container.width}"
        }
    }
}
