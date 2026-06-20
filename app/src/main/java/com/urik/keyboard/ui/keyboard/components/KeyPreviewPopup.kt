package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import com.urik.keyboard.theme.ThemeManager

/**
 * Magnified single-glyph preview bubble shown above a character key while it is held — the classic
 * "key preview popup". Mirrors [FlickPopup]'s window setup (a non-focusable [PopupWindow] that never
 * steals input or outside touches), but renders ONE big glyph in the black/yellow look instead of a rose.
 *
 * One instance is reused for the whole keyboard: [show] updates the glyph + colours + position and
 * [dismiss] hides it. Colours are passed in per show (resolved from the per-geometry look knobs, falling
 * back to the theme) so the bubble always matches the running keyboard.
 */
class KeyPreviewPopup(private val context: Context, private val themeManager: ThemeManager) : PopupWindow() {
    private val density = context.resources.displayMetrics.density

    private val label = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        // Generous internal padding so a tall/wide glyph never clips inside the bubble.
        val padH = (14 * density).toInt()
        val padV = (8 * density).toInt()
        setPadding(padH, padV, padH, padV)
    }

    init {
        contentView = label
        // Exactly like FlickPopup: never focusable, never grabs outside touches or the IME — so the
        // underlying key keeps receiving the ongoing press/slide/flick gesture uninterrupted.
        isFocusable = false
        isOutsideTouchable = false
        isTouchable = false
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        inputMethodMode = INPUT_METHOD_NOT_NEEDED
        width = ViewGroup.LayoutParams.WRAP_CONTENT
        height = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    /**
     * Show the bubble for [anchorView], displaying [glyph] magnified above the key's top edge and
     * horizontally centred over it (clamped to the screen; top-row keys may sit slightly lower).
     *
     * @param labelTextSizePx the key's own label size in px — the preview renders at [PREVIEW_SCALE]×.
     * @param textColor / @param bgColor / @param borderColor the resolved look/theme colours.
     */
    fun show(
        glyph: String,
        anchorView: View,
        labelTextSizePx: Float,
        textColor: Int,
        bgColor: Int,
        borderColor: Int,
        typeface: Typeface
    ) {
        if (glyph.isEmpty() || !anchorView.isAttachedToWindow || anchorView.windowToken == null) return

        label.apply {
            text = glyph
            setTextColor(textColor)
            this.typeface = typeface
            setTextSize(TypedValue.COMPLEX_UNIT_PX, labelTextSizePx * PREVIEW_SCALE)
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 8f * density
                setStroke((1.5f * density).toInt().coerceAtLeast(1), borderColor)
            }
        }
        // Measure so we can centre over the key and offset upward by the measured height.
        label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupWidth = label.measuredWidth
        val popupHeight = label.measuredHeight

        val gap = (6 * density).toInt()
        val anchorLocation = IntArray(2)
        anchorView.getLocationOnScreen(anchorLocation)
        val anchorX = anchorLocation[0]
        val anchorY = anchorLocation[1]

        val screenWidth = context.resources.displayMetrics.widthPixels

        // Horizontal: centre over the key, clamp inside the screen.
        var horizontalOffset = (anchorView.width - popupWidth) / 2
        val popupLeft = anchorX + horizontalOffset
        val popupRight = popupLeft + popupWidth
        if (popupLeft < 0) {
            horizontalOffset -= popupLeft
        } else if (popupRight > screenWidth) {
            horizontalOffset -= popupRight - screenWidth
        }

        // Vertical: float just above the key's top edge. If there isn't room above (a top-row key whose
        // top sits at/above the popup height), let it sit slightly lower — overlapping the key's top — so
        // it stays on screen rather than being clipped off the top.
        val gapAbove = -(anchorView.height + popupHeight + gap)
        val verticalOffset =
            if (anchorY >= popupHeight + gap) gapAbove else -(anchorView.height) + gap

        if (isShowing) {
            update(anchorView, horizontalOffset, verticalOffset, popupWidth, popupHeight)
        } else {
            width = popupWidth
            height = popupHeight
            showAsDropDown(anchorView, horizontalOffset, verticalOffset)
        }
    }

    /** Hide the bubble if showing. Safe to call repeatedly. */
    fun hide() {
        if (isShowing) dismiss()
    }

    companion object {
        /** Magnification of the preview glyph relative to the key's own label size. */
        const val PREVIEW_SCALE = 1.8f
    }
}
