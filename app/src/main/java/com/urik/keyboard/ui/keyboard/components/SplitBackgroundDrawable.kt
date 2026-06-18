package com.urik.keyboard.ui.keyboard.components

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Split-keyboard background: fills [bgColor] except a transparent vertical strip of width [gapPx] down the
 * horizontal centre — the see-through split gap (the IME window is transparent, so the app shows there).
 *
 * Used on BOTH the keyboard container (so its gap is see-through) and the outer [SwipeKeyboardView] (so its
 * own background doesn't paint black behind the gap). The suggestion bar paints its own opaque background on
 * top, so the centre strip never holes the candidate row.
 */
internal class SplitBackgroundDrawable(
    private val bgColor: Int,
    private val gapPx: Int
) : Drawable() {
    private val paint = Paint().apply { color = bgColor }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (gapPx <= 0) {
            canvas.drawColor(bgColor)
            return
        }
        val cx = b.exactCenterX()
        val half = gapPx / 2f
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), cx - half, b.bottom.toFloat(), paint)
        canvas.drawRect(cx + half, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
