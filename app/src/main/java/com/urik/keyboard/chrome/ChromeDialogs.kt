package com.urik.keyboard.chrome

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.urik.keyboard.R

/**
 * A dialog's list of things to do, in the menu look: every row an outlined pill as wide as the dialog with
 * its icon at the left, coloured by what the row does; a › when the row opens something further; red when
 * it deletes. Replaces the plain text lines of `setItems`.
 */
object ChromeDialogs {
    data class Row(
        val label: CharSequence,
        val icon: ChromeIcons.Icon,
        val does: ChromeIcons.Does = ChromeIcons.Does.VIEW,
        val opens: Boolean = false,
        val destructive: Boolean = false
    )

    /** Show [rows] under [title]; [onPick] gets the index of the row tapped, after the dialog has closed. */
    fun actions(
        context: Context,
        title: CharSequence?,
        rows: List<Row>,
        cancelText: CharSequence? = context.getString(android.R.string.cancel),
        onPick: (Int) -> Unit
    ): AlertDialog {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Pills.dp(context, 8f), Pills.dp(context, 6f), Pills.dp(context, 8f), Pills.dp(context, 2f))
        }
        val builder = AlertDialog.Builder(context, R.style.Theme_Urik_Dialog)
        if (title != null) builder.setTitle(title)
        builder.setView(ScrollView(context).apply { addView(column) })
        if (cancelText != null) builder.setNegativeButton(cancelText, null)
        val dialog = builder.create()
        rows.forEachIndexed { i, row ->
            val color = if (row.destructive) ChromeColors.delete else ChromeColors.accent
            val iconColor = if (row.destructive) ChromeColors.delete else ChromeIcons.color(row.does)
            column.addView(
                Pills.iconPill(context, row.label, row.icon, iconColor, main = false, color = color, opens = row.opens, fullWidth = true) {
                    dialog.dismiss()
                    onPick(i)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Pills.dp(context, 3f)
                    bottomMargin = Pills.dp(context, 3f)
                }
            )
        }
        dialog.show()
        return dialog
    }

    /** The same list where every row is the same kind of thing (a list of places, presets, pages). */
    fun choices(
        context: Context,
        title: CharSequence?,
        labels: List<CharSequence>,
        icon: ChromeIcons.Icon,
        does: ChromeIcons.Does = ChromeIcons.Does.VIEW,
        opens: Boolean = false,
        onPick: (Int) -> Unit
    ): AlertDialog = actions(context, title, labels.map { Row(it, icon, does, opens) }, onPick = onPick)
}
