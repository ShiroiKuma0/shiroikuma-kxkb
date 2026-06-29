package com.urik.keyboard.utils

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.Toast
import android.widget.TextView
import android.graphics.drawable.GradientDrawable

/**
 * House-style transient flash: black background, yellow text, yellow rounded frame — replacing the system's
 * default white toast pill. Every Toast in the app routes through here so the look is consistent.
 *
 * Implemented with a custom Toast view. On API 31+ a custom-view toast is shown for the FOREGROUND app
 * (every settings screen here) and suppressed only when shown from the background; that is acceptable for
 * our use (all flashes accompany a user action on a visible screen).
 */
object KxkbToast {
    private const val YELLOW = 0xFFFFFF00.toInt()
    private const val BLACK = 0xFF000000.toInt()

    fun show(context: Context, message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val tv =
            TextView(context).apply {
                text = message
                setTextColor(YELLOW)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(12), dp(20), dp(12))
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(18).toFloat()
                        setColor(BLACK)
                        setStroke(dp(2), YELLOW)
                    }
            }
        @Suppress("DEPRECATION")
        Toast(context.applicationContext).apply {
            this.duration = duration
            @Suppress("DEPRECATION")
            view = tv
        }.show()
    }

    fun show(context: Context, resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        show(context, context.getString(resId), duration)
    }
}
