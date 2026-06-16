package com.urik.keyboard.settings.keyboardui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.urik.keyboard.R

/**
 * Black/yellow ARGB colour picker: a row of one-click recent-colour swatches, a live preview/hex line, and
 * four A/R/G/B sliders. Applies LIVE on every change (so the keyboard previews immediately); Cancel reverts
 * to the colour it opened with, OK keeps it and remembers it in the recent swatches.
 */
object ColorPicker {
    private const val PREFS = "kxkb_color_picker"
    private const val KEY_RECENT = "recent"
    private const val MAX_RECENT = 8

    fun show(context: Context, initial: Int, onColor: (Int) -> Unit) {
        val density = context.resources.displayMetrics.density
        val yellow = ContextCompat.getColor(context, R.color.kxkb_yellow)

        var a = Color.alpha(initial)
        var r = Color.red(initial)
        var g = Color.green(initial)
        var b = Color.blue(initial)

        val sliders = mutableListOf<SeekBar>()
        val preview = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            minHeight = (52 * density).toInt()
        }

        fun current() = Color.argb(a, r, g, b)

        fun refresh(apply: Boolean) {
            val color = current()
            preview.setBackgroundColor(color)
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
            preview.setTextColor(if (luminance < 128 || a < 128) Color.WHITE else Color.BLACK)
            preview.text = String.format("#%02X%02X%02X%02X", a, r, g, b)
            if (apply) onColor(color)
        }

        fun setFrom(color: Int) {
            a = Color.alpha(color); r = Color.red(color); g = Color.green(color); b = Color.blue(color)
            sliders.getOrNull(0)?.progress = a
            sliders.getOrNull(1)?.progress = r
            sliders.getOrNull(2)?.progress = g
            sliders.getOrNull(3)?.progress = b
            refresh(apply = true)
        }

        fun channelRow(label: String, value: Int, onChange: (Int) -> Unit): View {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply {
                text = label
                setTextColor(yellow)
                width = (22 * density).toInt()
            })
            val seek = SeekBar(context).apply {
                max = 255
                progress = value
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        onChange(p); refresh(apply = true)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}

                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            sliders.add(seek)
            row.addView(seek)
            return row
        }

        // Recent-colour swatches (one-click prefill).
        val swatchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sizePx = (32 * density).toInt()
        val gap = (6 * density).toInt()
        recent(context).forEach { sw ->
            swatchRow.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply { marginEnd = gap }
                background = GradientDrawable().apply {
                    setColor(sw)
                    setStroke((1.5f * density).toInt(), yellow)
                    cornerRadius = 4f * density
                }
                setOnClickListener { setFrom(sw) }
            })
        }

        val pad = (20 * density).toInt()
        fun spaced(view: View, bottomDp: Int): View {
            (view.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )).also {
                it.bottomMargin = (bottomDp * density).toInt()
                view.layoutParams = it
            }
            return view
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            if (swatchRow.childCount > 0) addView(spaced(swatchRow, 18))
            addView(spaced(preview, 18))
            addView(spaced(channelRow("A", a) { a = it }, 8))
            addView(spaced(channelRow("R", r) { r = it }, 8))
            addView(spaced(channelRow("G", g) { g = it }, 8))
            addView(channelRow("B", b) { b = it })
        }
        refresh(apply = false)

        AlertDialog.Builder(context, R.style.Theme_Urik_Dialog)
            .setTitle(R.string.keyboard_ui_pick_colour)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val color = current()
                onColor(color)
                remember(context, color)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onColor(initial) }
            .setOnCancelListener { onColor(initial) }
            .show()
    }

    private fun recent(context: Context): List<Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_RECENT, null)
            ?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()
        // Seed with the black/yellow staples so there's always something to one-click.
        val seeds = listOf(0xFF000000.toInt(), 0xFFFFFF00.toInt(), 0xFFFFFFFF.toInt(), 0xFFC8C800.toInt())
        return (stored + seeds).distinct().take(MAX_RECENT)
    }

    private fun remember(context: Context, color: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = prefs.getString(KEY_RECENT, null)?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        val updated = (listOf(color) + cur).distinct().take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, updated.joinToString(",")).apply()
    }
}
