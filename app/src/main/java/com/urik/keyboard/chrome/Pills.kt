package com.urik.keyboard.chrome

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * The button look of every page: an action is a PILL — fully round, an accent frame, the ground showing
 * through, never filled. The main action of a block has a 3 dp frame and bold text; every other one a
 * 1.25 dp frame and normal text; something that deletes lends its red to frame and label. A disabled pill
 * keeps its shape at 38 %.
 */
object Pills {
    const val MAIN_DP = 3f
    const val REGULAR_DP = 1.25f

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun stateColors(color: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(ChromeColors.disabled(color), color)
    )

    /** The unfilled round frame of a pill with its ripple. [selected] is the one fill: a chosen segment. */
    fun background(context: Context, color: Int = ChromeColors.accent, main: Boolean = false, selected: Boolean = false): Drawable {
        val radius = dp(context, 100f).toFloat()
        val frame = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (selected) ChromeColors.withAlpha(color, 0.22f) else Color.TRANSPARENT)
            setStroke(maxOf(1, dp(context, if (main) MAIN_DP else REGULAR_DP)), stateColors(color))
        }
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(ChromeColors.ripple(color)), frame, mask)
    }

    /** Turn any text view or button into a pill. [small] is the dense variant for a row of controls. */
    fun style(view: TextView, main: Boolean = false, color: Int = ChromeColors.accent, small: Boolean = false) {
        if (view is MaterialButton) {
            styleMaterial(view, main, color)
            return
        }
        view.background = background(view.context, color, main)
        view.setTextColor(stateColors(color))
        view.setTypeface(null, if (main) Typeface.BOLD else Typeface.NORMAL)
        view.isAllCaps = false
        view.stateListAnimator = null
        view.minHeight = 0
        view.minimumHeight = 0
        view.minWidth = 0
        view.minimumWidth = 0
        view.gravity = Gravity.CENTER
        val c = view.context
        if (small) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            view.setPadding(dp(c, 10f), dp(c, 4f), dp(c, 10f), dp(c, 4f))
        } else {
            view.setPadding(dp(c, 16f), dp(c, 8f), dp(c, 16f), dp(c, 8f))
        }
    }

    private fun styleMaterial(b: MaterialButton, main: Boolean, color: Int) {
        val c = b.context
        b.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        b.strokeColor = stateColors(color)
        b.strokeWidth = maxOf(1, dp(c, if (main) MAIN_DP else REGULAR_DP))
        b.cornerRadius = dp(c, 100f)
        b.rippleColor = ColorStateList.valueOf(ChromeColors.ripple(color))
        b.setTextColor(stateColors(color))
        b.iconTint = stateColors(color)
        b.setTypeface(null, if (main) Typeface.BOLD else Typeface.NORMAL)
        b.isAllCaps = false
        b.stateListAnimator = null
        b.elevation = 0f
    }

    fun button(
        context: Context,
        text: CharSequence,
        main: Boolean = false,
        color: Int = ChromeColors.accent,
        small: Boolean = false,
        icon: ChromeIcons.Icon? = null,
        does: ChromeIcons.Does = ChromeIcons.Does.VIEW,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        this.text = text
        style(this, main, color, small)
        // The icon at the pill's left takes the colour of what the pill does; a red pill is red throughout.
        if (icon != null) {
            val tint = if (color == ChromeColors.delete) color else ChromeIcons.color(does)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                ChromeIcons.drawable(context, icon, tint, if (small) 14f else 18f), null, null, null
            )
            compoundDrawablePadding = dp(context, if (small) 5f else 8f)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    /**
     * A pill with an icon at its left, coloured by what the action does, and a › at its right when it opens
     * something — the row of a menu or an action list, and a page's navigation pill.
     */
    fun iconPill(
        context: Context,
        text: CharSequence,
        icon: ChromeIcons.Icon?,
        iconColor: Int = ChromeColors.accent,
        main: Boolean = false,
        color: Int = ChromeColors.accent,
        opens: Boolean = false,
        fullWidth: Boolean = false,
        selected: Boolean = false,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = background(context, color, main, selected)
        isClickable = true
        isFocusable = true
        setPadding(dp(context, 14f), dp(context, 8f), dp(context, 14f), dp(context, 8f))
        if (icon != null) {
            addView(View(context).apply { background = ChromeIcons.drawable(context, icon, iconColor) },
                LinearLayout.LayoutParams(dp(context, 20f), dp(context, 20f)).apply { marginEnd = dp(context, 10f) })
        }
        addView(TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, if (main) Typeface.BOLD else Typeface.NORMAL)
        }, LinearLayout.LayoutParams(if (fullWidth) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, if (fullWidth) 1f else 0f))
        if (opens) {
            addView(TextView(context).apply {
                this.text = "›"
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(context, 10f), 0, 0, 0)
            })
        }
        setOnClickListener { onClick() }
    }

    /** A small outlined chip (the section index of a UI page). */
    fun chip(context: Context, text: CharSequence, color: Int = ChromeColors.accent): TextView = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        isSingleLine = true
        gravity = Gravity.CENTER
        setPadding(dp(context, 12f), dp(context, 5f), dp(context, 12f), dp(context, 5f))
        background = background(context, color, main = false)
        isClickable = true
        isFocusable = true
    }

    /** The pill of a dialog button: buttons in a bar touch, so the frame sits inside a small inset. */
    fun styleDialogButton(b: Button, main: Boolean, color: Int = ChromeColors.accent) {
        if (b is MaterialButton) {
            styleMaterial(b, main, color)
            b.insetTop = dp(b.context, 5f)
            b.insetBottom = dp(b.context, 5f)
            b.minimumWidth = dp(b.context, 64f)
            b.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, bm: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                    val m = dp(v.context, 4f)
                    if (lp.marginStart != m || lp.marginEnd != m) {
                        lp.marginStart = m
                        lp.marginEnd = m
                        v.layoutParams = lp
                    }
                }
            })
        } else {
            style(b, main, color)
            b.background = InsetDrawable(background(b.context, color, main), dp(b.context, 4f), dp(b.context, 5f), dp(b.context, 4f), dp(b.context, 5f))
        }
    }

    /** A dialog that deletes: its confirming button turns red. Call after `show()`. */
    fun destructive(dialog: Dialog) {
        (dialog.findViewById<View>(android.R.id.button1) as? Button)?.let { styleDialogButton(it, main = true, color = ChromeColors.delete) }
    }
}
