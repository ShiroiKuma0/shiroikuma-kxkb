package com.urik.keyboard.settings.keyboardui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.urik.keyboard.R

/**
 * A settings row that DOES something when tapped — opens a page or acts in place — drawn as an outlined pill
 * as wide as its label, with its explanation beneath (the black-yellow-theme settings-page rule, 白い熊
 * 2026-11-07/09): a row that acts must never look like a heading or a note. [main] is the section's main
 * action (3 dp frame, bold label), every other one a 1.25 dp frame; [opens] puts a › inside the pill.
 *
 * Only the pill is the touch target — the row itself is made inert so a tap on the explanation does nothing.
 */
class KxkbActionPreference(
    context: Context,
    private val main: Boolean,
    private val opens: Boolean
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
        (holder.findViewById(android.R.id.title) as? TextView)?.setTypeface(
            null,
            if (main) Typeface.BOLD else Typeface.NORMAL
        )
        holder.findViewById(R.id.kxkb_action_chevron)?.visibility = if (opens) View.VISIBLE else View.GONE
        holder.findViewById(R.id.kxkb_action_pill)?.apply {
            setBackgroundResource(
                if (main) R.drawable.kxkb_action_pill_main else R.drawable.kxkb_action_pill_plain
            )
            setOnClickListener { onPreferenceClickListener?.onPreferenceClick(this@KxkbActionPreference) }
        }
    }
}
