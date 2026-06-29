package com.urik.keyboard.settings.userdictionary

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import com.urik.keyboard.utils.KxkbToast
import androidx.appcompat.app.AlertDialog
import com.urik.keyboard.R
import com.urik.keyboard.data.database.UserDictionaryKind
import com.urik.keyboard.utils.LanguageDisplayNames

/**
 * The add/edit dialog for a user-dictionary entry, in the black/yellow house style. One dialog covers all
 * three kinds — a plain word, a shortcut→expansion, and a Japanese reading→kanji — with the visible fields
 * switching as the kind is selected. On edit the kind is fixed (only the fields change).
 *
 * Word/Shortcut entries pick a real, prediction-capable language shown by its native name (日本語 and the
 * no-prediction "gnu" pseudo-language are excluded — Japanese has its own kind, gnu offers no predictions).
 */
object UserDictionaryEntryDialog {
    // Languages that never appear in the Word/Shortcut picker: ja has its own kind; gnu is no-prediction.
    private val EXCLUDED_LANGUAGES = setOf("ja", "gnu")

    fun show(
        context: Context,
        languages: List<String>,
        defaultLanguage: String,
        existing: UserDictionaryRow?,
        onSubmit: (id: Long?, languageTag: String, kind: UserDictionaryKind, matchKey: String, value: String) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        fun label(textRes: Int, topGap: Int = 10): TextView = TextView(context).apply {
            setText(textRes)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(topGap) }
        }

        // Kind selector — manual mutual exclusion (not a RadioGroup) so 日本語 can sit on its own second row.
        val wordRadio = RadioButton(context).apply { setText(R.string.user_dictionary_kind_word) }
        val shortcutRadio = RadioButton(context).apply {
            setText(R.string.user_dictionary_kind_shortcut)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(16) }
        }
        val japaneseRadio = RadioButton(context).apply { setText(R.string.user_dictionary_kind_japanese) }
        val kindButtons = listOf(wordRadio, shortcutRadio, japaneseRadio)

        val kindRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(wordRadio)
            addView(shortcutRadio)
        }
        root.addView(kindRow1)
        root.addView(japaneseRadio)

        val languageLabel = label(R.string.user_dictionary_field_language)
        root.addView(languageLabel)
        // Word/Shortcut languages: active languages minus the excluded ones, plus the entry's own language
        // when editing (so an entry in a since-deactivated language still shows). Displayed by native name.
        val languageCodes = (languages + defaultLanguage + listOfNotNull(existing?.languageTag))
            .distinct()
            .filter { it !in EXCLUDED_LANGUAGES }
            .ifEmpty { listOf("en") }
        val languageNames = languageCodes.map { LanguageDisplayNames.nativeName(it) }
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, languageNames)
            val defIdx = languageCodes.indexOf(defaultLanguage).let { if (it >= 0) it else 0 }
            setSelection(defIdx)
        }
        root.addView(spinner)

        val field1Label = label(R.string.user_dictionary_field_word)
        root.addView(field1Label)
        val field1 = EditText(context).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(field1)

        val field2Label = label(R.string.user_dictionary_field_expansion)
        root.addView(field2Label)
        val field2 = EditText(context).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(field2)

        fun applyKind(kind: UserDictionaryKind) {
            when (kind) {
                UserDictionaryKind.WORD -> {
                    field1Label.setText(R.string.user_dictionary_field_word)
                    field2Label.visibility = View.GONE
                    field2.visibility = View.GONE
                    languageLabel.visibility = View.VISIBLE
                    spinner.visibility = View.VISIBLE
                }
                UserDictionaryKind.SHORTCUT -> {
                    field1Label.setText(R.string.user_dictionary_field_shortcut)
                    field2Label.setText(R.string.user_dictionary_field_expansion)
                    field2Label.visibility = View.VISIBLE
                    field2.visibility = View.VISIBLE
                    languageLabel.visibility = View.VISIBLE
                    spinner.visibility = View.VISIBLE
                }
                UserDictionaryKind.JAPANESE -> {
                    field1Label.setText(R.string.user_dictionary_field_reading)
                    field2Label.setText(R.string.user_dictionary_field_kanji)
                    field2Label.visibility = View.VISIBLE
                    field2.visibility = View.VISIBLE
                    // Japanese entries are always scoped to "ja"; the picker is irrelevant.
                    languageLabel.visibility = View.GONE
                    spinner.visibility = View.GONE
                }
            }
        }

        fun selectKind(kind: UserDictionaryKind) {
            wordRadio.isChecked = kind == UserDictionaryKind.WORD
            shortcutRadio.isChecked = kind == UserDictionaryKind.SHORTCUT
            japaneseRadio.isChecked = kind == UserDictionaryKind.JAPANESE
            applyKind(kind)
        }

        wordRadio.setOnClickListener { selectKind(UserDictionaryKind.WORD) }
        shortcutRadio.setOnClickListener { selectKind(UserDictionaryKind.SHORTCUT) }
        japaneseRadio.setOnClickListener { selectKind(UserDictionaryKind.JAPANESE) }

        val editing = existing != null
        val initialKind = existing?.kind ?: UserDictionaryKind.WORD
        selectKind(initialKind)
        if (existing != null) {
            kindButtons.forEach { it.isEnabled = false }
            if (initialKind == UserDictionaryKind.WORD) {
                field1.setText(existing.value)
            } else {
                field1.setText(existing.matchKey)
                field2.setText(existing.value)
            }
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_Urik_Dialog)
            .setTitle(if (editing) R.string.user_dictionary_edit_title else R.string.user_dictionary_add_title)
            .setView(root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        // Positive listener is wired AFTER show() so an invalid entry can keep the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val kind = when {
                shortcutRadio.isChecked -> UserDictionaryKind.SHORTCUT
                japaneseRadio.isChecked -> UserDictionaryKind.JAPANESE
                else -> UserDictionaryKind.WORD
            }
            val f1 = field1.text?.toString()?.trim().orEmpty()
            val f2 = field2.text?.toString()?.trim().orEmpty()
            val matchKey = f1
            val value = if (kind == UserDictionaryKind.WORD) f1 else f2
            val valid =
                if (kind == UserDictionaryKind.WORD) matchKey.isNotEmpty() else matchKey.isNotEmpty() && value.isNotEmpty()
            if (!valid) {
                KxkbToast.show(context, R.string.user_dictionary_incomplete)
                return@setOnClickListener
            }
            val languageTag = when (kind) {
                UserDictionaryKind.JAPANESE -> "ja"
                else -> languageCodes.getOrElse(spinner.selectedItemPosition) { defaultLanguage }
            }
            onSubmit(existing?.id, languageTag, kind, matchKey, value)
            dialog.dismiss()
        }
    }
}
