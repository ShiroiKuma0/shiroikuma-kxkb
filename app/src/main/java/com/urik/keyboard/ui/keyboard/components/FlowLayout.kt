package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Minimal flow layout: lays children left-to-right, wrapping to the next row when the current row fills.
 * Used by the expandable many-candidates pane to pack tens of cluster candidates compactly.
 */
class FlowLayout(context: Context) : ViewGroup(context) {
    var horizontalSpacing = 0
    var verticalSpacing = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = width - paddingLeft - paddingRight
        val childSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(childSpec, childSpec)
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x > 0 && x + cw > available) {
                x = 0
                y += rowHeight + verticalSpacing
                rowHeight = 0
            }
            x += cw + horizontalSpacing
            rowHeight = maxOf(rowHeight, ch)
        }
        val total = y + rowHeight + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(total, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (r - l) - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x > paddingLeft && x + cw > paddingLeft + available) {
                x = paddingLeft
                y += rowHeight + verticalSpacing
                rowHeight = 0
            }
            child.layout(x, y, x + cw, y + ch)
            x += cw + horizontalSpacing
            rowHeight = maxOf(rowHeight, ch)
        }
    }
}
