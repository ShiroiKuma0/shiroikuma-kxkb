package com.urik.keyboard.chrome

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The icons of menus, lists and settings rows, drawn in code on a 24-unit grid as plain outlines — so each
 * takes whatever role colour its row has, at any size, with no dependence on which symbols the phone's font
 * happens to carry.
 */
object ChromeIcons {
    enum class Icon {
        EDIT, DELETE, COPY, EXPORT, IMPORT, ADD, CLOSE, CHECK, UP, DOWN, LEFT, RIGHT, SLIDERS, PALETTE, TEXT,
        SIZE, LIST, KEYBOARD, MIC, LOCK, GLOBE, DEVICE, INFO, RESET, PLAY, EYE, LAYERS, BRANCH, BOOK, BLOCK,
        TOGGLE, KEYCAP, MOVE, CODE, DIRECTORY, HISTORY, STAR, PRIVACY, SPARK, POINTER, GAUGE
    }

    /** What a row does, and so which colour its icon takes. */
    enum class Does { VIEW, CONNECT, MESSAGE, CONTACT, SHARE, BLOCK, DELETE }

    fun color(does: Does): Int = when (does) {
        Does.VIEW -> ChromeColors.accent
        Does.CONNECT -> ChromeColors[ChromeColors.Role.CONNECT]
        Does.MESSAGE -> ChromeColors[ChromeColors.Role.MESSAGE]
        Does.CONTACT -> ChromeColors[ChromeColors.Role.CONTACT]
        Does.SHARE -> ChromeColors[ChromeColors.Role.SHARE]
        Does.BLOCK -> ChromeColors[ChromeColors.Role.BLOCK]
        Does.DELETE -> ChromeColors.delete
    }

    fun drawable(context: Context, icon: Icon, color: Int, sizeDp: Float = 20f): Drawable =
        IconDrawable(icon, color, Pills.dp(context, sizeDp))

    fun drawable(context: Context, icon: Icon, does: Does, sizeDp: Float = 20f): Drawable =
        drawable(context, icon, color(does), sizeDp)

    private class IconDrawable(private val icon: Icon, color: Int, private val size: Int) : Drawable() {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        private val path = Path()
        private val box = RectF()

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun setAlpha(alpha: Int) { line.alpha = alpha; solid.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { line.colorFilter = colorFilter; solid.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()) / 24f
            canvas.save()
            canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
            canvas.scale(s, s)
            line.strokeWidth = 1.8f
            paint(canvas)
            canvas.restore()
        }

        private fun Canvas.l(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(x1, y1, x2, y2, line)
        private fun Canvas.c(x: Float, y: Float, r: Float) = drawCircle(x, y, r, line)
        private fun Canvas.dot(x: Float, y: Float, r: Float = 1.3f) = drawCircle(x, y, r, solid)
        private fun Canvas.r(l: Float, t: Float, r: Float, b: Float, rad: Float = 2f) {
            box.set(l, t, r, b); drawRoundRect(box, rad, rad, line)
        }
        private fun Canvas.arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float) {
            box.set(l, t, r, b); drawArc(box, start, sweep, false, line)
        }
        private fun Canvas.poly(vararg p: Float, close: Boolean = false) {
            path.reset()
            path.moveTo(p[0], p[1])
            var i = 2
            while (i < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
            if (close) path.close()
            drawPath(path, line)
        }

        private fun paint(c: Canvas) {
            when (icon) {
                Icon.EDIT -> { c.poly(4f, 20f, 5f, 15.5f, 16f, 4.5f, 19.5f, 8f, 8.5f, 19f, close = true); c.l(13.5f, 7f, 17f, 10.5f) }
                Icon.DELETE -> { c.l(4f, 7f, 20f, 7f); c.poly(9f, 7f, 9.5f, 4f, 14.5f, 4f, 15f, 7f); c.poly(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f); c.l(10f, 10.5f, 10.3f, 16.5f); c.l(14f, 10.5f, 13.7f, 16.5f) }
                Icon.COPY -> { c.r(8.5f, 8.5f, 20f, 20f); c.poly(15.5f, 8.5f, 15.5f, 4f, 4f, 4f, 4f, 15.5f, 8.5f, 15.5f) }
                Icon.EXPORT -> { c.l(12f, 15f, 12f, 4f); c.poly(7.5f, 8.5f, 12f, 4f, 16.5f, 8.5f); c.poly(4f, 14f, 4f, 20f, 20f, 20f, 20f, 14f) }
                Icon.IMPORT -> { c.l(12f, 4f, 12f, 15f); c.poly(7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f); c.poly(4f, 14f, 4f, 20f, 20f, 20f, 20f, 14f) }
                Icon.ADD -> { c.l(12f, 5f, 12f, 19f); c.l(5f, 12f, 19f, 12f) }
                Icon.CLOSE -> { c.l(6f, 6f, 18f, 18f); c.l(18f, 6f, 6f, 18f) }
                Icon.CHECK -> c.poly(4.5f, 12.5f, 9.5f, 17.5f, 19.5f, 6.5f)
                Icon.UP -> { c.l(12f, 19f, 12f, 5f); c.poly(6f, 11f, 12f, 5f, 18f, 11f) }
                Icon.DOWN -> { c.l(12f, 5f, 12f, 19f); c.poly(6f, 13f, 12f, 19f, 18f, 13f) }
                Icon.LEFT -> { c.l(19f, 12f, 5f, 12f); c.poly(11f, 6f, 5f, 12f, 11f, 18f) }
                Icon.RIGHT -> { c.l(5f, 12f, 19f, 12f); c.poly(13f, 6f, 19f, 12f, 13f, 18f) }
                Icon.SLIDERS -> {
                    c.l(4f, 7f, 20f, 7f); c.l(4f, 12f, 20f, 12f); c.l(4f, 17f, 20f, 17f)
                    c.dot(9f, 7f, 2.3f); c.dot(15f, 12f, 2.3f); c.dot(8f, 17f, 2.3f)
                }
                Icon.PALETTE -> {
                    path.reset(); path.moveTo(12f, 3.5f); path.cubicTo(8f, 9f, 5.5f, 11.5f, 5.5f, 14.5f)
                    path.cubicTo(5.5f, 18f, 8.5f, 20.5f, 12f, 20.5f); path.cubicTo(15.5f, 20.5f, 18.5f, 18f, 18.5f, 14.5f)
                    path.cubicTo(18.5f, 11.5f, 16f, 9f, 12f, 3.5f); path.close(); c.drawPath(path, line)
                    c.arc(8.5f, 11f, 15.5f, 18f, 100f, 70f)
                }
                Icon.TEXT -> { c.poly(5f, 20f, 12f, 4f, 19f, 20f); c.l(7.8f, 14f, 16.2f, 14f) }
                Icon.SIZE -> { c.l(5f, 19f, 19f, 5f); c.poly(12.5f, 5f, 19f, 5f, 19f, 11.5f); c.poly(5f, 12.5f, 5f, 19f, 11.5f, 19f) }
                Icon.LIST -> {
                    c.dot(5f, 7f); c.dot(5f, 12f); c.dot(5f, 17f)
                    c.l(9f, 7f, 20f, 7f); c.l(9f, 12f, 20f, 12f); c.l(9f, 17f, 20f, 17f)
                }
                Icon.KEYBOARD -> {
                    c.r(3f, 6f, 21f, 18f, 2.5f)
                    c.dot(7f, 10f, 1f); c.dot(10.3f, 10f, 1f); c.dot(13.7f, 10f, 1f); c.dot(17f, 10f, 1f)
                    c.l(8f, 14.5f, 16f, 14.5f)
                }
                Icon.MIC -> { c.r(9f, 3.5f, 15f, 14f, 3f); c.arc(5.5f, 5f, 18.5f, 17.5f, 0f, 180f); c.l(12f, 17.5f, 12f, 20.5f); c.l(8.5f, 20.5f, 15.5f, 20.5f) }
                Icon.LOCK -> { c.r(5.5f, 10.5f, 18.5f, 20f); c.arc(8f, 4f, 16f, 14f, 180f, 180f); c.dot(12f, 15f, 1.4f) }
                Icon.GLOBE -> { c.c(12f, 12f, 8.5f); c.l(3.5f, 12f, 20.5f, 12f); c.arc(8f, 3.5f, 16f, 20.5f, 0f, 360f) }
                Icon.DEVICE -> { c.r(7f, 3.5f, 17f, 20.5f, 2.5f); c.l(10.5f, 17.5f, 13.5f, 17.5f) }
                Icon.INFO -> { c.c(12f, 12f, 8.5f); c.l(12f, 11f, 12f, 16.5f); c.dot(12f, 7.8f, 1.2f) }
                Icon.RESET -> { c.arc(4.5f, 4.5f, 19.5f, 19.5f, -60f, 300f); c.poly(11.5f, 2f, 15.8f, 5.5f, 11.5f, 9f) }
                Icon.PLAY -> c.poly(8f, 5f, 19f, 12f, 8f, 19f, close = true)
                Icon.EYE -> {
                    path.reset(); path.moveTo(2.5f, 12f); path.quadTo(12f, 2.5f, 21.5f, 12f); path.quadTo(12f, 21.5f, 2.5f, 12f)
                    c.drawPath(path, line); c.c(12f, 12f, 3f)
                }
                Icon.LAYERS -> { c.poly(12f, 4f, 21f, 9f, 12f, 14f, 3f, 9f, close = true); c.poly(3f, 13.5f, 12f, 18.5f, 21f, 13.5f) }
                Icon.BRANCH -> { c.c(7f, 6f, 2.2f); c.c(7f, 18f, 2.2f); c.c(17f, 8f, 2.2f); c.l(7f, 8.2f, 7f, 15.8f); c.arc(-3f, 0f, 17f, 13.5f, 0f, 90f) }
                Icon.BOOK -> { c.r(5f, 4f, 19f, 20f, 1.5f); c.l(9f, 4f, 9f, 20f); c.l(12f, 9f, 16f, 9f); c.l(12f, 12.5f, 16f, 12.5f) }
                Icon.BLOCK -> { c.c(12f, 12f, 8.5f); c.l(6f, 6f, 18f, 18f) }
                Icon.TOGGLE -> { c.r(3f, 7.5f, 21f, 16.5f, 4.5f); c.dot(16.5f, 12f, 2.4f) }
                Icon.KEYCAP -> { c.r(4.5f, 4.5f, 19.5f, 19.5f, 3f); c.poly(9f, 16f, 12f, 8f, 15f, 16f); c.l(10.2f, 13.2f, 13.8f, 13.2f) }
                Icon.MOVE -> {
                    c.l(12f, 3.5f, 12f, 20.5f); c.l(3.5f, 12f, 20.5f, 12f)
                    c.poly(9.5f, 6f, 12f, 3.5f, 14.5f, 6f); c.poly(9.5f, 18f, 12f, 20.5f, 14.5f, 18f)
                    c.poly(6f, 9.5f, 3.5f, 12f, 6f, 14.5f); c.poly(18f, 9.5f, 20.5f, 12f, 18f, 14.5f)
                }
                Icon.CODE -> { c.poly(8.5f, 7f, 3.5f, 12f, 8.5f, 17f); c.poly(15.5f, 7f, 20.5f, 12f, 15.5f, 17f); c.l(13.5f, 5f, 10.5f, 19f) }
                Icon.DIRECTORY -> c.poly(3.5f, 6f, 9.5f, 6f, 11.5f, 8.5f, 20.5f, 8.5f, 20.5f, 19f, 3.5f, 19f, close = true)
                Icon.HISTORY -> { c.c(12f, 12f, 8.5f); c.poly(12f, 7f, 12f, 12f, 15.5f, 14f) }
                Icon.STAR -> c.poly(12f, 3.5f, 14.6f, 9.2f, 20.8f, 9.8f, 16.1f, 14f, 17.5f, 20.2f, 12f, 17f, 6.5f, 20.2f, 7.9f, 14f, 3.2f, 9.8f, 9.4f, 9.2f, close = true)
                Icon.PRIVACY -> { c.poly(12f, 3.5f, 19f, 6.5f, 19f, 12f, 12f, 20.5f, 5f, 12f, 5f, 6.5f, close = true); c.poly(9f, 11.5f, 11.3f, 14f, 15.2f, 9.5f) }
                Icon.SPARK -> { c.poly(12f, 3.5f, 13.8f, 10.2f, 20.5f, 12f, 13.8f, 13.8f, 12f, 20.5f, 10.2f, 13.8f, 3.5f, 12f, 10.2f, 10.2f, close = true) }
                Icon.POINTER -> { c.poly(6f, 4f, 18f, 12.5f, 12.5f, 13.5f, 9.5f, 19f, close = true) }
                Icon.GAUGE -> { c.arc(3.5f, 5.5f, 20.5f, 22.5f, 180f, 180f); c.l(12f, 14f, 16f, 9f); c.dot(12f, 14f, 1.5f) }
            }
        }
    }
}
