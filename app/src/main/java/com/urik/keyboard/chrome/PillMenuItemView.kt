package com.urik.keyboard.chrome

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.menu.ListMenuItemView
import androidx.appcompat.view.menu.MenuItemImpl

/**
 * One row of every popup menu (popup, long-press, submenu, the toolbar overflow): an action is an outlined
 * pill as wide as the menu, 1.25 dp, with its icon inside at the left and a › at its right when it opens
 * something; a row that deletes is red; a disabled one stands at 38 %. A checkable row stays plain, 36 dp
 * tall, and carries the outlined switch instead of a checkbox.
 *
 * Swapped in through the app's own copies of AppCompat's two menu-row layouts; what a row does comes from
 * [MenuPills].
 */
@SuppressLint("RestrictedApi")
class PillMenuItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.listMenuViewStyle
) : ListMenuItemView(context, attrs, defStyleAttr) {
    private var content: LinearLayout? = null
    private var title: TextView? = null
    private var arrow: ImageView? = null
    private var padStart = 0
    private var padTop = 0
    private var padEnd = 0
    private var padBottom = 0
    private var contentHeight = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        content = findViewById(androidx.appcompat.R.id.content)
        title = findViewById(androidx.appcompat.R.id.title)
        arrow = findViewById(androidx.appcompat.R.id.submenuarrow)
        content?.let {
            padStart = it.paddingStart
            padTop = it.paddingTop
            padEnd = it.paddingEnd
            padBottom = it.paddingBottom
            contentHeight = it.layoutParams.height
        }
        findViewById<View>(androidx.appcompat.R.id.group_divider)?.let { d ->
            d.setBackgroundColor(ChromeColors.divider)
            (d.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.leftMargin = dp(8)
                lp.rightMargin = dp(8)
                lp.height = dp(1)
                d.layoutParams = lp
            }
        }
    }

    override fun initialize(itemData: MenuItemImpl, menuType: Int) {
        super.initialize(itemData, menuType)
        try {
            style(itemData)
        } catch (_: Throwable) {
            // A row that cannot be styled is still a working row.
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? AbsListView)?.let { list ->
            if (list.selector !is ColorDrawable) list.selector = ColorDrawable(Color.TRANSPARENT)
            list.setBackgroundColor(ChromeColors.background)
        }
    }

    private fun style(item: MenuItemImpl) {
        val content = content ?: return
        val title = title ?: return
        val checkable = item.isCheckable
        val header = !item.isEnabled && !item.hasSubMenu() && item.icon == null && MenuPills.isHeader(item.itemId)
        val pill = !checkable && !header
        val destructive = pill && MenuPills.isDestructive(item.itemId)
        var color = if (destructive) ChromeColors.delete else ChromeColors.accent
        if (!item.isEnabled && !header) color = ChromeColors.disabled(color)

        (content.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val h = dp(8)
            val v = if (pill) dp(3) else 0
            val height = if (checkable) dp(36) else contentHeight
            if (lp.leftMargin != h || lp.topMargin != v || lp.height != height) {
                lp.height = height
                lp.setMargins(h, v, h, v)
                content.layoutParams = lp
            }
        }
        content.background = if (pill) Pills.background(context, color, main = false) else null
        content.setPaddingRelative(padStart, padTop, if (checkable) 0 else padEnd, padBottom)
        title.setTextColor(if (destructive) color else if (item.isEnabled || header) ChromeColors.text else color)

        arrow?.let { a ->
            val text = item.title?.toString()?.trim().orEmpty()
            val opens = pill && (item.hasSubMenu() || text.endsWith("…") || MenuPills.opens(item.itemId))
            if (opens) a.setImageDrawable(ChromeIcons.drawable(context, ChromeIcons.Icon.RIGHT, color, 16f))
            a.visibility = if (opens) View.VISIBLE else View.GONE
        }

        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child is CheckBox) {
                if (child.tag == null) {
                    child.buttonDrawable = null
                    child.background = null
                    child.tag = true
                }
                // The outlined switch, drawn as the box's own picture so the menu keeps driving its state.
                val sw = KxkbSwitch(context, null, compact = true).apply {
                    isChecked = item.isChecked
                    isEnabled = item.isEnabled
                    isClickable = false
                    isFocusable = false
                }
                sw.measure(0, 0)
                sw.layout(0, 0, sw.measuredWidth, sw.measuredHeight)
                child.foreground = ViewDrawable(sw)
                child.minimumWidth = sw.measuredWidth + dp(8)
                child.minWidth = sw.measuredWidth + dp(8)
            } else if (child.id == androidx.appcompat.R.id.icon && child is ImageView) {
                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    if (lp.marginStart != dp(14)) {
                        lp.marginStart = dp(14)
                        lp.marginEnd = -dp(6)
                        child.layoutParams = lp
                    }
                }
                val tint = ChromeIcons.color(MenuPills.does(item.itemId))
                child.imageTintList = null
                child.alpha = if (item.isEnabled) 1f else 0.38f
                if (item.icon == null) child.setImageDrawable(ChromeIcons.drawable(context, MenuPills.icon(item.itemId), tint))
            }
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun dp(v: Int): Int = Pills.dp(context, v.toFloat())

    /** Draws a laid-out view as a drawable, right-aligned and centred in its bounds. */
    private class ViewDrawable(private val view: View) : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            canvas.save()
            canvas.translate((b.right - view.width).toFloat(), b.top + (b.height() - view.height) / 2f)
            view.draw(canvas)
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
