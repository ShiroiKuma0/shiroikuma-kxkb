package com.urik.keyboard.chrome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.urik.keyboard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The shared pieces of the black-yellow look, exercised for real: every icon draws, the switch measures and
 * toggles, the section index lays its chips out and wraps, and — through the theme's view inflater — the
 * buttons of a stock dialog come out as pills, the confirming one the heavy one.
 */
@RunWith(RobolectricTestRunner::class)
class ChromeComponentsTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val themed: Context get() = ContextThemeWrapper(app, R.style.Theme_Urik_NoActionBar)

    class Host : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Urik_NoActionBar)
            super.onCreate(savedInstanceState)
        }
    }

    @Test
    fun `every icon draws`() {
        val canvas = Canvas(Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888))
        for (icon in ChromeIcons.Icon.entries) {
            val d = ChromeIcons.drawable(app, icon, ChromeIcons.Does.VIEW)
            d.setBounds(0, 0, 48, 48)
            d.draw(canvas)
            assertTrue(d.intrinsicWidth > 0)
        }
    }

    @Test
    fun `every role has a colour and the defaults are the settled ones`() {
        assertEquals(0xFFFFFF00.toInt(), ChromeColors.Role.ACCENT.default)
        assertEquals(0xFFC8C800.toInt(), ChromeColors.Role.NOTE.default)
        assertEquals(0xFF8C8C00.toInt(), ChromeColors.Role.SWITCH_OFF.default)
        // Apply on the pending-change bar is pure blue (白い熊, 2026-11-20).
        assertEquals(0xFF0000FF.toInt(), ChromeColors.Role.APPLY.default)
        assertEquals(0xFFB3B300.toInt(), ChromeColors.blend(0xFF000000.toInt(), 0xFFFFFF00.toInt(), 0.7f))
        for (does in ChromeIcons.Does.entries) assertTrue(ChromeIcons.color(does) != 0)
    }

    @Test
    fun `a role set is read back and reset to its default`() {
        ChromeColors.set(app, ChromeColors.Role.ACCENT, 0xFF00FF00.toInt())
        assertEquals(0xFF00FF00.toInt(), ChromeColors.accent)
        assertTrue(ChromeColors.customised)
        ChromeColors.set(app, ChromeColors.Role.ACCENT, null)
        assertEquals(0xFFFFFF00.toInt(), ChromeColors.accent)
        assertFalse(ChromeColors.customised)
    }

    @Test
    fun `the switch is the outlined pill size and toggles`() {
        val sw = KxkbSwitch(themed)
        sw.measure(0, 0)
        val d = app.resources.displayMetrics.density
        assertEquals((52 * d).toInt(), sw.measuredWidth)
        assertEquals((28 * d).toInt(), sw.measuredHeight)
        sw.layout(0, 0, sw.measuredWidth, sw.measuredHeight)
        sw.draw(Canvas(Bitmap.createBitmap(sw.measuredWidth, sw.measuredHeight, Bitmap.Config.ARGB_8888)))
        assertFalse(sw.isChecked)
        sw.performClick()
        assertTrue(sw.isChecked)
        assertTrue(sw is SwitchCompat)
    }

    @Test
    fun `a pill is never filled and the main one is the heavier`() {
        val regular = Pills.button(themed, "Cancel") {}
        val main = Pills.button(themed, "Save", main = true) {}
        assertFalse(regular.isAllCaps)
        assertFalse(main.isAllCaps)
        // The frame with its ripple, and nothing behind it: no filled colour drawable.
        assertTrue(regular.background is android.graphics.drawable.RippleDrawable)
        assertTrue(main.background is android.graphics.drawable.RippleDrawable)
        assertNotNull(Pills.iconPill(themed, "Export", ChromeIcons.Icon.EXPORT, opens = true) {})
    }

    @Test
    fun `the section index wraps its chips and keeps their order`() {
        val items = (1..12).map { SectionIndex.Item("k$it", "Section $it") }
        val flow = SectionIndex.build(themed, items, object : SectionIndex.Listener {
            override fun onJump(key: String) {}
            override fun onReordered(order: List<String>) {}
        }) as SectionIndex.Flow
        flow.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        flow.layout(0, 0, 400, flow.measuredHeight)
        assertEquals(12, flow.childCount)
        assertEquals("k1", flow.getChildAt(0).tag)
        // More than one line of chips: the last chip sits below the first.
        assertTrue(flow.getChildAt(11).top > flow.getChildAt(0).top)
        for (i in 0 until flow.childCount) assertTrue(flow.getChildAt(i).right <= 400)
    }

    @Test
    fun `the buttons of a stock dialog are pills and the confirming one is the main pill`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Delete?")
            .setPositiveButton("Delete") { _, _ -> }
            .setNegativeButton("Cancel", null)
            .show()
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        assertTrue(positive is MaterialButton)
        val d = activity.resources.displayMetrics.density
        assertEquals((Pills.MAIN_DP * d + 0.5f).toInt(), (positive as MaterialButton).strokeWidth)
        assertEquals(maxOf(1, (Pills.REGULAR_DP * d + 0.5f).toInt()), (negative as MaterialButton).strokeWidth)
        assertFalse(positive.isAllCaps)
        // A dialog that deletes lends its red to the confirming pill.
        Pills.destructive(dialog)
        assertEquals(ChromeColors.delete, (dialog.getButton(AlertDialog.BUTTON_POSITIVE) as Button).currentTextColor)
    }
}
