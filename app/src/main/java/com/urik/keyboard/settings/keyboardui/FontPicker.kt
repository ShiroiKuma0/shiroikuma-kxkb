package com.urik.keyboard.settings.keyboardui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.urik.keyboard.R
import com.urik.keyboard.service.FontOption
import com.urik.keyboard.service.KeyboardFonts

/**
 * The keyboard-font picker: a black/yellow dialog listing System / Monospace + every imported font, EACH
 * rendered in its own glyphs (so you see what it looks like). A neutral button opens the document picker to
 * import a new `.ttf`/`.otf`.
 */
object FontPicker {
    fun show(context: Context, current: String, onPick: (String) -> Unit, onImport: () -> Unit) {
        val fonts = KeyboardFonts.availableFonts(context)
        val yellow = ContextCompat.getColor(context, R.color.kxkb_yellow)
        val density = context.resources.displayMetrics.density

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = fonts.size
            override fun getItem(position: Int): FontOption = fonts[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val option = fonts[position]
                val tv = (convertView as? TextView) ?: TextView(context)
                val selected = option.fileName == current
                tv.text = (if (selected) "✓  " else "") + option.displayName
                tv.typeface = KeyboardFonts.typeface(context, option.fileName)
                tv.setTextColor(yellow)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                val padH = (20 * density).toInt()
                val padV = (10 * density).toInt()
                tv.setPadding(padH, padV, padH, padV)
                return tv
            }
        }

        AlertDialog.Builder(context, R.style.Theme_Urik_Dialog)
            .setTitle(R.string.keyboard_ui_font_family)
            .setAdapter(adapter) { _, position -> onPick(fonts[position].fileName) }
            .setNeutralButton(R.string.keyboard_ui_font_import) { _, _ -> onImport() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
