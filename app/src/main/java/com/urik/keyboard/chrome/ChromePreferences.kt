package com.urik.keyboard.chrome

import android.view.View
import android.view.ViewGroup
import androidx.preference.EditTextPreference
import androidx.recyclerview.widget.RecyclerView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference
import com.urik.keyboard.chrome.ChromeIcons.Does
import com.urik.keyboard.chrome.ChromeIcons.Icon

/**
 * Gives every row of a plain settings page an icon coloured by what the row does — one icon column, one
 * label edge — without each page having to name them: the icon is told from the row's key, and failing that
 * from the kind of row it is. A row that already carries an icon keeps it.
 */
object ChromePreferences {
    private val byKeyword: List<Pair<Regex, Pair<Icon, Does>>> = listOf(
        Regex("delete|clear|reset|erase|forget|remove") to (Icon.DELETE to Does.DELETE),
        Regex("export|import|backup|restore|share") to (Icon.EXPORT to Does.SHARE),
        Regex("clipboard|copy|paste") to (Icon.COPY to Does.SHARE),
        Regex("privacy|incognito|block|blacklist|password|secure|lock") to (Icon.PRIVACY to Does.BLOCK),
        Regex("voice|dictat|mic") to (Icon.MIC to Does.MESSAGE),
        Regex("language|locale") to (Icon.GLOBE to Does.CONTACT),
        Regex("layout|mapper|number_row|one_handed|split|mode|library|editor") to (Icon.KEYBOARD to Does.CONTACT),
        Regex("theme|colou?r|appearance|\\bui\\b|_ui") to (Icon.PALETTE to Does.VIEW),
        Regex("dictionar|learned|word") to (Icon.BOOK to Does.MESSAGE),
        Regex("swipe|gesture|cursor|flick|slide") to (Icon.POINTER to Does.VIEW),
        Regex("haptic|vibrat|sound|duration|delay|speed|sensitiv|strength") to (Icon.GAUGE to Does.VIEW),
        Regex("spell|correct|suggest|predict|capital|punctuat|space|period|typing") to (Icon.TEXT to Does.MESSAGE),
        Regex("size|height|width|scale") to (Icon.SIZE to Does.VIEW),
        Regex("about|licen|version|attribution|donat|privacy_policy|website|github") to (Icon.INFO to Does.VIEW),
        Regex("hint|popup|preview|show") to (Icon.EYE to Does.VIEW)
    )

    fun decorate(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceGroup) {
                decorate(pref)
                if (pref is PreferenceCategory) continue
            }
            if (pref.icon != null) continue
            val (icon, does) = iconOf(pref)
            pref.icon = ChromeIcons.drawable(pref.context, icon, does, 22f)
        }
    }

    /**
     * Every row of a plain settings page only as tall as its content: no 48 dp minimum, 2 dp above and below.
     * Only rows in the library's own layout are touched (they have its icon frame); a row with a layout of
     * ours is already right.
     */
    fun tighten(list: RecyclerView) {
        list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val iconFrame = view.findViewById<View>(androidx.preference.R.id.icon_frame) ?: return
                val pad = Pills.dp(view.context, 2f)
                view.minimumHeight = 0
                (iconFrame as? ViewGroup)?.let {
                    it.minimumHeight = 0
                    it.setPadding(it.paddingLeft, pad, it.paddingRight, pad)
                }
                (view.findViewById<View>(android.R.id.title)?.parent as? View)?.let {
                    it.setPadding(it.paddingLeft, pad, it.paddingRight, pad)
                }
                view.findViewById<View>(android.R.id.widget_frame)?.let { it.minimumHeight = 0 }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    private fun iconOf(pref: Preference): Pair<Icon, Does> {
        val key = pref.key.orEmpty().lowercase()
        byKeyword.firstOrNull { it.first.containsMatchIn(key) }?.let { return it.second }
        return when (pref) {
            is TwoStatePreference -> Icon.TOGGLE to Does.VIEW
            is SeekBarPreference -> Icon.SLIDERS to Does.VIEW
            is EditTextPreference -> Icon.EDIT to Does.MESSAGE
            is ListPreference -> Icon.LIST to Does.VIEW
            else -> Icon.RIGHT to Does.VIEW
        }
    }
}
