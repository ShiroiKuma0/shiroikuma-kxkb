package com.urik.keyboard.chrome

import android.annotation.SuppressLint
import android.content.Context
import android.view.Menu
import androidx.appcompat.view.menu.MenuBuilder
import com.urik.keyboard.R

/**
 * What each menu item does — a menu item carries no "opens something" or "deletes" flag of its own, so this
 * table supplies them, together with the icon a row shows and the colour that icon takes. Every item of
 * every menu in the app has a line here; an unknown id is a plain row with the list icon.
 */
object MenuPills {
    private data class Entry(
        val icon: ChromeIcons.Icon,
        val does: ChromeIcons.Does,
        val opens: Boolean = false,
        val destructive: Boolean = false
    )

    private val table: Map<Int, Entry> = mapOf(
        R.id.action_delete_all to Entry(ChromeIcons.Icon.DELETE, ChromeIcons.Does.DELETE, opens = true, destructive = true)
    )

    fun icon(id: Int): ChromeIcons.Icon = table[id]?.icon ?: ChromeIcons.Icon.LIST
    fun does(id: Int): ChromeIcons.Does = table[id]?.does ?: ChromeIcons.Does.VIEW
    fun opens(id: Int): Boolean = table[id]?.opens == true
    fun isDestructive(id: Int): Boolean = table[id]?.destructive == true
    fun isHeader(id: Int): Boolean = id == 0

    /**
     * Give every item of [menu] its icon and make the popup show icons at all (an overflow hides them by
     * default). Call when a menu is created.
     */
    @SuppressLint("RestrictedApi")
    fun decorate(context: Context, menu: Menu) {
        (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.icon == null && !item.isCheckable) {
                item.icon = ChromeIcons.drawable(context, icon(item.itemId), ChromeIcons.color(does(item.itemId)))
            }
            item.subMenu?.let { decorate(context, it) }
        }
    }
}
