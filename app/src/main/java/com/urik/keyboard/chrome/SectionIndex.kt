package com.urik.keyboard.chrome

import android.content.ClipData
import android.content.Context
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup

/**
 * The jump index of a UI page: a wrapping grid of small outlined chips, one for every top-level section. A
 * TAP opens that section and scrolls to it; a LONG PRESS lifts the chip into a drag, the chips reorder live
 * under the finger, and the sections below take the new order when it is dropped.
 */
object SectionIndex {
    data class Item(val key: String, val label: CharSequence)

    interface Listener {
        fun onJump(key: String)
        fun onReordered(order: List<String>)
    }

    fun build(context: Context, items: List<Item>, listener: Listener): View {
        val flow = Flow(context, Pills.dp(context, 6f))
        flow.setPadding(0, Pills.dp(context, 2f), 0, Pills.dp(context, 6f))
        for (item in items) {
            val chip = Pills.chip(context, item.label)
            chip.tag = item.key
            chip.setOnClickListener { listener.onJump(it.tag as String) }
            chip.setOnLongClickListener { v ->
                v.alpha = 0.3f
                v.startDragAndDrop(ClipData.newPlainText("kxkb_section", v.tag as String), View.DragShadowBuilder(v), v, 0)
                true
            }
            flow.addView(chip)
        }
        flow.setOnDragListener { _, event -> onDrag(flow, event, listener) }
        return flow
    }

    private fun onDrag(flow: Flow, event: DragEvent, listener: Listener): Boolean {
        val dragged = event.localState as? View
        // Another page's drag (the walk-capture pills) is not ours to answer.
        if (dragged == null || dragged.parent !== flow) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                // The drag is driven by the shadow surface, so moving the chip in the tree does not cancel it.
                val to = insertionIndex(flow, event.x, event.y)
                val from = flow.indexOfChild(dragged)
                if (to >= 0 && to != from) {
                    try {
                        flow.removeView(dragged)
                        flow.addView(dragged, minOf(to, flow.childCount))
                    } catch (_: Exception) {
                        // The tree was mid-change; the next location event retries.
                    }
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                dragged.alpha = 1f
                listener.onReordered((0 until flow.childCount).map { flow.getChildAt(it).tag as String })
            }
        }
        return true
    }

    /** The child the point is over, or the nearest one by centre. */
    private fun insertionIndex(flow: Flow, x: Float, y: Float): Int {
        var best = -1
        var bestDist = Double.MAX_VALUE
        for (i in 0 until flow.childCount) {
            val ch = flow.getChildAt(i)
            if (x >= ch.left && x <= ch.right && y >= ch.top && y <= ch.bottom) return i
            val d = Math.hypot(x - (ch.left + ch.right) / 2.0, y - (ch.top + ch.bottom) / 2.0)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    /** A minimal wrapping row layout. */
    class Flow(context: Context, private val gap: Int) : ViewGroup(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            val inner = width - paddingLeft - paddingRight
            var x = 0
            var y = paddingTop
            var rowH = 0
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                if (ch.visibility == GONE) continue
                measureChild(ch, MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (x > 0 && x + ch.measuredWidth > inner) { x = 0; y += rowH + gap; rowH = 0 }
                x += ch.measuredWidth + gap
                rowH = maxOf(rowH, ch.measuredHeight)
            }
            setMeasuredDimension(width, y + rowH + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val inner = width - paddingLeft - paddingRight
            var x = paddingLeft
            var y = paddingTop
            var rowH = 0
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                if (ch.visibility == GONE) continue
                if (x > paddingLeft && x + ch.measuredWidth > paddingLeft + inner) { x = paddingLeft; y += rowH + gap; rowH = 0 }
                ch.layout(x, y, x + ch.measuredWidth, y + ch.measuredHeight)
                x += ch.measuredWidth + gap
                rowH = maxOf(rowH, ch.measuredHeight)
            }
        }
    }
}
