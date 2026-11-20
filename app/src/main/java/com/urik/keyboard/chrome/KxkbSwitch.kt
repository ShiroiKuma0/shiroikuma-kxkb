package com.urik.keyboard.chrome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.SwitchCompat

/**
 * The switch of every page: an outlined pill with the ground showing through and a solid dot inside it —
 * right and accent when on, left and an opaque dim yellow when off. Never filled, never a white ring; one
 * shape for both states, so a column of switches reads as one column.
 *
 * A [SwitchCompat] by type, so a switch preference binds it and code that holds one keeps working; it draws
 * itself and ignores the platform's thumb and track.
 */
class KxkbSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val compact: Boolean = false
) : SwitchCompat(context, attrs) {
    private val density = resources.displayMetrics.density
    private val trackW get() = (if (compact) 44f else 52f) * density
    private val trackH get() = (if (compact) 24f else 28f) * density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private var position = 0f
    private var animator: ValueAnimator? = null
    private var ready = false

    init {
        thumbDrawable = null
        trackDrawable = null
        background = null
        minHeight = 0
        minimumHeight = 0
        position = if (isChecked) 1f else 0f
        ready = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            (trackW + paddingLeft + paddingRight).toInt(),
            (trackH + paddingTop + paddingBottom).toInt()
        )
    }

    override fun setChecked(checked: Boolean) {
        val was = isChecked
        super.setChecked(checked)
        if (!ready) return
        val target = if (checked) 1f else 0f
        animator?.cancel()
        if (was != checked && isAttachedToWindow && isLaidOut) {
            animator = ValueAnimator.ofFloat(position, target).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener { position = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            position = target
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val left = paddingLeft.toFloat()
        val top = (height - trackH) / 2f
        val on = isChecked
        var color = if (on) ChromeColors.accent else ChromeColors.switchOff
        if (!isEnabled) color = ChromeColors.disabled(color)
        val inset = stroke.strokeWidth / 2f
        rect.set(left + inset, top + inset, left + trackW - inset, top + trackH - inset)
        stroke.color = color
        c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, stroke)
        fill.color = color
        val from = left + trackH / 2f
        val to = left + trackW - trackH / 2f
        c.drawCircle(from + (to - from) * position, top + trackH / 2f, trackH * 0.23f, fill)
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        animator?.end()
        position = if (isChecked) 1f else 0f
    }
}
