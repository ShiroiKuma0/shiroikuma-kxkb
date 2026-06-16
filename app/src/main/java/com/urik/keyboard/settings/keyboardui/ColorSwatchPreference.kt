package com.urik.keyboard.settings.keyboardui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.urik.keyboard.R

/**
 * A Preference (title + hex summary) that also shows a bordered colour swatch on the right reflecting the
 * current [color], so the colour is visible at a glance.
 */
class ColorSwatchPreference(context: Context) : Preference(context) {
    var color: Int = 0xFF000000.toInt()
        set(value) {
            field = value
            notifyChanged()
        }

    init {
        layoutResource = R.layout.preference_item_kxkb
        widgetLayoutResource = R.layout.preference_color_swatch
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val swatch = holder.findViewById(R.id.color_swatch) ?: return
        val density = context.resources.displayMetrics.density
        val fill = color // capture before apply{}, where `color` would resolve to GradientDrawable.color
        swatch.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke((1.5f * density).toInt(), ContextCompat.getColor(context, R.color.kxkb_yellow))
            cornerRadius = 4f * density
        }
    }
}
