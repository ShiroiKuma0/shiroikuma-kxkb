package com.urik.keyboard.settings.keyboardui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.urik.keyboard.R
import com.urik.keyboard.chrome.ChromeColors
import com.urik.keyboard.chrome.ChromeIcons
import com.urik.keyboard.chrome.Pills

/**
 * A settings row that DOES something when tapped — opens a page or acts in place — drawn as an outlined pill
 * as wide as its label, with its explanation beneath (the black-yellow-theme settings-page rule, 白い熊
 * 2026-11-07/09): a row that acts must never look like a heading or a note. [main] is the section's main
 * action (3 dp frame, bold label), every other one a 1.25 dp frame; [opens] puts a › inside the pill; the
 * [icon] at its left is coloured by what the row [does], and a row that deletes is red throughout.
 *
 * Only the pill is the touch target — the row itself is made inert so a tap on the explanation does nothing.
 */
class KxkbActionPreference(
    context: Context,
    private val main: Boolean,
    private val opens: Boolean,
    private val icon: ChromeIcons.Icon = ChromeIcons.Icon.RIGHT,
    private val does: ChromeIcons.Does = ChromeIcons.Does.VIEW,
    private val destructive: Boolean = false
) : Preference(context) {
    init {
        layoutResource = R.layout.preference_action_kxkb
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.apply {
            isClickable = false
            isFocusable = false
            background = null
        }
        val color = if (destructive) ChromeColors.delete else ChromeColors.accent
        (holder.findViewById(android.R.id.title) as? TextView)?.apply {
            setTypeface(null, if (main) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (destructive) color else ChromeColors.text)
        }
        (holder.findViewById(android.R.id.summary) as? TextView)?.setTextColor(ChromeColors.note)
        (holder.findViewById(R.id.kxkb_action_chevron) as? TextView)?.apply {
            visibility = if (opens) View.VISIBLE else View.GONE
            setTextColor(color)
        }
        holder.findViewById(R.id.kxkb_icon)?.background =
            ChromeIcons.drawable(context, icon, if (destructive) color else ChromeIcons.color(does))
        holder.findViewById(R.id.kxkb_action_pill)?.apply {
            background = Pills.background(context, color, main)
            setOnClickListener { onPreferenceClickListener?.onPreferenceClick(this@KxkbActionPreference) }
        }
    }
}
