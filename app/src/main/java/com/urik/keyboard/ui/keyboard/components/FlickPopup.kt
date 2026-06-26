package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.theme.ThemeManager

/**
 * Directional cross popup for Japanese flick input feedback.
 *
 * Displays a 3×3 grid (compass rose) with the center kana and its four flick variants.
 * Call [show] when a FlickKey is pressed, [updateHighlight] on each direction change,
 * and [dismiss] on finger-up.
 */
class FlickPopup(
    private val context: Context,
    private val themeManager: ThemeManager,
    private val compassTextSp: Float = 36f // set from the look knob (18sp × compassFontScale); default = 2×
) : PopupWindow() {
    private val density = context.resources.displayMetrics.density
    private val cellSizePx = (compassTextSp * 1.55f * density).toInt() // cells grow with the font so glyphs fit

    private val grid = GridLayout(context).apply {
        rowCount = 3
        columnCount = 3
    }

    private val cells = mutableMapOf<FlickGestureDetector.FlickDirection, TextView?>()

    init {
        val colors = themeManager.currentTheme.value.colors
        val pad = (6 * density).toInt()
        grid.setPadding(pad, pad, pad, pad)
        contentView = grid
        isOutsideTouchable = true
        isFocusable = false
        // A full-black rounded panel with a yellow border + an elevation shadow, so the compass jumps off the
        // keyboard. The panel fills the empty cells too (instead of showing the keys behind).
        setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(colors.keyboardBackground)
                cornerRadius = 14f * density
                setStroke((2.5f * density).toInt(), colors.keyTextCharacter)
            }
        )
        elevation = 12f * density
        inputMethodMode = INPUT_METHOD_NOT_NEEDED
        width = cellSizePx * 3 + pad * 2
        height = cellSizePx * 3 + pad * 2
    }

    fun show(key: KeyboardKey.FlickKey, anchorView: View) {
        if (!anchorView.isAttachedToWindow) return
        buildGrid(key)

        val gap = (4 * density).toInt()
        val verticalOffset = -(anchorView.height + height + gap)

        val anchorLocation = IntArray(2)
        anchorView.getLocationOnScreen(anchorLocation)
        val screenWidth = context.resources.displayMetrics.widthPixels
        var horizontalOffset = (anchorView.width - width) / 2
        val popupLeft = anchorLocation[0] + horizontalOffset
        val popupRight = popupLeft + width
        if (popupLeft < 0) {
            horizontalOffset -= popupLeft
        } else if (popupRight > screenWidth) {
            horizontalOffset -= popupRight - screenWidth
        }

        showAsDropDown(anchorView, horizontalOffset, verticalOffset)
    }

    fun updateHighlight(direction: FlickGestureDetector.FlickDirection) {
        val theme = themeManager.currentTheme.value
        cells.forEach { (dir, cell) ->
            cell ?: return@forEach
            val bg = cell.background as? GradientDrawable ?: return@forEach
            bg.setColor(
                if (dir == direction) {
                    theme.colors.statePressed
                } else {
                    Color.TRANSPARENT
                }
            )
            cell.invalidate()
        }
    }

    private fun buildGrid(key: KeyboardKey.FlickKey) {
        grid.removeAllViews()
        cells.clear()

        val theme = themeManager.currentTheme.value
        val positions = mapOf(
            FlickGestureDetector.FlickDirection.UP_LEFT to Pair(0, 0),
            FlickGestureDetector.FlickDirection.UP to Pair(0, 1),
            FlickGestureDetector.FlickDirection.UP_RIGHT to Pair(0, 2),
            FlickGestureDetector.FlickDirection.LEFT to Pair(1, 0),
            FlickGestureDetector.FlickDirection.NONE to Pair(1, 1),
            FlickGestureDetector.FlickDirection.RIGHT to Pair(1, 2),
            FlickGestureDetector.FlickDirection.DOWN_LEFT to Pair(2, 0),
            FlickGestureDetector.FlickDirection.DOWN to Pair(2, 1),
            FlickGestureDetector.FlickDirection.DOWN_RIGHT to Pair(2, 2)
        )

        val charForDirection = mapOf(
            FlickGestureDetector.FlickDirection.UP to key.up,
            FlickGestureDetector.FlickDirection.LEFT to key.left,
            FlickGestureDetector.FlickDirection.NONE to key.center,
            FlickGestureDetector.FlickDirection.RIGHT to key.right,
            FlickGestureDetector.FlickDirection.DOWN to key.down,
            FlickGestureDetector.FlickDirection.UP_LEFT to key.upLeft,
            FlickGestureDetector.FlickDirection.UP_RIGHT to key.upRight,
            FlickGestureDetector.FlickDirection.DOWN_LEFT to key.downLeft,
            FlickGestureDetector.FlickDirection.DOWN_RIGHT to key.downRight
        )

        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val params = GridLayout.LayoutParams().apply {
                    width = cellSizePx
                    height = cellSizePx
                    rowSpec = GridLayout.spec(row)
                    columnSpec = GridLayout.spec(col)
                }
                val direction = positions.entries.firstOrNull { it.value == Pair(row, col) }?.key
                val char = direction?.let { charForDirection[it] }

                if (char != null && direction != null) {
                    val cell = TextView(context).apply {
                        text = char
                        gravity = Gravity.CENTER
                        textSize = compassTextSp
                        includeFontPadding = false
                        setPadding(0, 0, 0, 0)
                        setTextColor(theme.colors.keyTextCharacter)
                        background = GradientDrawable().apply {
                            setColor(Color.TRANSPARENT) // the panel provides the black; only the active cell lights up
                            cornerRadius = 8f * density
                        }
                        contentDescription = "$direction: $char"
                    }
                    cells[direction] = cell
                    grid.addView(cell, params)
                } else {
                    grid.addView(View(context), params)
                    if (direction != null) cells[direction] = null
                }
            }
        }

        updateHighlight(FlickGestureDetector.FlickDirection.NONE)
    }
}
