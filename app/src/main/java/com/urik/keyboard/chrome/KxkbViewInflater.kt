package com.urik.keyboard.chrome

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.annotation.Keep
import android.content.res.ColorStateList
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatCheckedTextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.theme.MaterialComponentsViewInflater

/**
 * The one place every inflated widget of the app passes through (the theme's `viewInflaterClass`), so the
 * button, switch and colour rules reach screens nobody restyled by hand — the stock dialog button bar
 * included: every `Button` becomes an outlined pill (a dialog's confirming button the main one), every
 * switch the outlined one, and text in the two theme yellows follows the settable text and note roles.
 */
@Keep
class KxkbViewInflater : MaterialComponentsViewInflater() {
    override fun createButton(context: Context, attrs: AttributeSet): AppCompatButton {
        val b = super.createButton(context, attrs)
        when (attrs.getAttributeResourceValue(ANDROID_NS, "id", 0)) {
            android.R.id.button1 -> Pills.styleDialogButton(b, main = true)
            android.R.id.button2, android.R.id.button3 -> Pills.styleDialogButton(b, main = false)
            else -> Pills.style(b, main = isMain(attrs))
        }
        return b
    }

    override fun createTextView(context: Context, attrs: AttributeSet?): AppCompatTextView {
        val t = super.createTextView(context, attrs)
        if (ChromeColors.customised) {
            when (t.currentTextColor) {
                DEFAULT_TEXT -> t.setTextColor(ChromeColors.text)
                DEFAULT_NOTE -> t.setTextColor(ChromeColors.note)
            }
        }
        return t
    }

    // The stock controls of the plain settings pages take the accent from the theme; with the roles changed
    // they are tinted here, so a slider, a text field's line and a tick follow the settable accent too.
    override fun createSeekBar(context: Context, attrs: AttributeSet): AppCompatSeekBar =
        super.createSeekBar(context, attrs).also {
            if (ChromeColors.customised) {
                it.progressTintList = ColorStateList.valueOf(ChromeColors.accent)
                it.thumbTintList = ColorStateList.valueOf(ChromeColors.accent)
                it.progressBackgroundTintList = ColorStateList.valueOf(ChromeColors.switchOff)
            }
        }

    override fun createEditText(context: Context, attrs: AttributeSet): AppCompatEditText =
        super.createEditText(context, attrs).also {
            if (ChromeColors.customised) {
                it.setTextColor(ChromeColors.text)
                it.setHintTextColor(ChromeColors.note)
                it.backgroundTintList = ColorStateList.valueOf(ChromeColors.accent)
            }
        }

    override fun createCheckBox(context: Context, attrs: AttributeSet): AppCompatCheckBox =
        super.createCheckBox(context, attrs).also {
            if (ChromeColors.customised) {
                it.buttonTintList = ColorStateList.valueOf(ChromeColors.accent)
                it.setTextColor(ChromeColors.text)
            }
        }

    override fun createRadioButton(context: Context, attrs: AttributeSet): AppCompatRadioButton =
        super.createRadioButton(context, attrs).also {
            if (ChromeColors.customised) {
                it.buttonTintList = ColorStateList.valueOf(ChromeColors.accent)
                it.setTextColor(ChromeColors.text)
            }
        }

    override fun createCheckedTextView(context: Context, attrs: AttributeSet): AppCompatCheckedTextView =
        super.createCheckedTextView(context, attrs).also {
            if (ChromeColors.customised) {
                it.checkMarkTintList = ColorStateList.valueOf(ChromeColors.accent)
                it.setTextColor(ChromeColors.text)
            }
        }

    override fun createView(context: Context, name: String, attrs: AttributeSet): View? = when (name) {
        "androidx.appcompat.widget.SwitchCompat",
        "com.google.android.material.switchmaterial.SwitchMaterial" -> KxkbSwitch(context, attrs)
        "com.google.android.material.button.MaterialButton" ->
            MaterialButton(context, attrs).also { Pills.style(it, main = isMain(attrs)) }
        else -> null
    }

    /** A layout marks the main action of its block with `android:tag="main"`. */
    private fun isMain(attrs: AttributeSet): Boolean = attrs.getAttributeValue(ANDROID_NS, "tag") == "main"

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val DEFAULT_TEXT = 0xFFFFFF00.toInt()
        const val DEFAULT_NOTE = 0xFFC8C800.toInt()
    }
}
