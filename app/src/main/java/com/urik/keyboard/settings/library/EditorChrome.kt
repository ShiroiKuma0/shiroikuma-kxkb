package com.urik.keyboard.settings.library

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.urik.keyboard.chrome.ChromeColors
import com.urik.keyboard.chrome.ChromeIcons
import com.urik.keyboard.chrome.Pills
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the two editor screens share: the "a change is waiting" bar pinned under the toolbar, and the
 * order-insensitive JSON comparison that decides whether one is.
 *
 * The bar holds a blue Apply and a red Cancel and is GONE until a change has been recorded, so its
 * appearing is itself the signal. Both are unfilled, fully round pills (the house rule); Apply is the main
 * one (3 dp frame, bold).
 */
internal object EditorChrome {
    /** Apply is blue and Cancel red on the pending-change bar (白い熊, 2026-11-20), from the settable roles. */
    val BLUE: Int get() = ChromeColors[ChromeColors.Role.APPLY]
    val RED: Int get() = ChromeColors.delete

    /** The tag of the value text inside a [valueRow]. */
    const val VALUE_TAG = "kxkb_value"

    /**
     * [v] with every object's keys sorted, so two trees compare equal whatever order their keys were put
     * in. A field removed and typed back moves to the end of an `org.json` object; that must not read as a
     * change.
     */
    fun canonical(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            is JSONObject -> {
                sb.append('{')
                for (k in v.keys().asSequence().toList().sorted()) {
                    sb.append(JSONObject.quote(k)).append(':')
                    write(sb, v.opt(k))
                    sb.append(',')
                }
                sb.append('}')
            }
            is JSONArray -> {
                sb.append('[')
                for (i in 0 until v.length()) {
                    write(sb, v.opt(i))
                    sb.append(',')
                }
                sb.append(']')
            }
            is String -> sb.append(JSONObject.quote(v))
            // 2 and 2.0 are one number: a width read back from its field must not read as a change.
            is Number -> {
                val d = v.toDouble()
                if (d == Math.rint(d) && !d.isInfinite()) sb.append(d.toLong()) else sb.append(d)
            }
            null -> sb.append("null")
            else -> sb.append(v.toString())
        }
    }

    /**
     * [layout] in a form two layouts can be compared by: [canonical], after every key the editor has merely
     * OPENED is put back into its short spelling. Opening a key stores a bare `"a"` as
     * `{type:character,char:"a"}` and a slide's `{action:…}` as `{type:action,action:…}` — the same key to the
     * keyboard, so not a change.
     */
    fun comparable(layout: JSONObject): String = canonical(shorten(JSONObject(layout.toString()), inSlide = false))

    private fun shorten(v: Any?, inSlide: Boolean): Any? = when (v) {
        is JSONObject -> {
            for (k in v.keys().asSequence().toList()) {
                val child = v.opt(k)
                if (k == "flick" && child is JSONObject) {
                    for (dir in child.keys().asSequence().toList()) child.put(dir, shorten(child.opt(dir), inSlide = true))
                } else {
                    v.put(k, shorten(child, inSlide = false))
                }
            }
            val type = v.optString("type")
            val extra = v.length() - 1 - (if (v.has("label")) 1 else 0)
            when {
                type == "character" && v.length() == 2 && v.has("char") -> v.optString("char")
                inSlide && type == "action" && v.has("action") && extra == 1 -> v.apply { remove("type") }
                inSlide && type == "layer" && v.has("layer") && extra == 1 -> v.apply { remove("type") }
                inSlide && type == "chord" && v.has("keys") && extra == 1 ->
                    JSONObject().put("chord", v.optString("keys")).apply { if (v.has("label")) put("label", v.opt("label")) }
                else -> v
            }
        }
        is JSONArray -> {
            for (i in 0 until v.length()) v.put(i, shorten(v.opt(i), inSlide = false))
            v
        }
        else -> v
    }

    private fun dp(context: Context, v: Float): Int = Pills.dp(context, v)

    /**
     * The bar that appears under the toolbar once a change has been recorded: a red Cancel and, right
     * beside it, a blue Apply (the main pill). GONE until then, so its appearing is itself the signal.
     */
    fun changeBar(
        context: Context,
        applyText: String,
        cancelText: String,
        onApply: () -> Unit,
        onCancel: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 14f), dp(context, 4f), dp(context, 14f), dp(context, 6f))
        // The two stay together at the left, Apply the rightmost of them (白い熊, 2026-11-20).
        addView(cancelPill(context, cancelText, onCancel))
        addView(
            applyPill(context, applyText, onApply),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(context, 10f) }
        )
        visibility = View.GONE
    }

    /**
     * Apply: the preferred button of the pair, shown the way a dialog shows its preferred one — the heavy
     * 3 dp frame and bold text, unfilled like every pill.
     */
    fun applyPill(context: Context, text: String, onClick: () -> Unit): LinearLayout =
        Pills.iconPill(context, text, ChromeIcons.Icon.CHECK, BLUE, main = true, color = BLUE, onClick = onClick)

    fun cancelPill(context: Context, text: String, onClick: () -> Unit): LinearLayout =
        Pills.iconPill(context, text, ChromeIcons.Icon.CLOSE, RED, main = false, color = RED, onClick = onClick)

    /** A section heading: bold accent text with a rule beneath it exactly as wide as the words. */
    fun heading(context: Context, text: CharSequence): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(context, 16f), 0, dp(context, 4f))
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                this.text = text
                setTextColor(ChromeColors.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(null, Typeface.BOLD)
            })
            addView(
                View(context).apply { setBackgroundColor(ChromeColors.accent) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 2f))
                    .apply { topMargin = dp(context, 1f) }
            )
        }
        addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * A control row whose value opens a picker: the label on the left, the value and a ▾ on the right, the
     * whole row the touch target. The value text carries [VALUE_TAG].
     */
    fun valueRow(context: Context, label: CharSequence, value: CharSequence, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 6f), 0, dp(context, 6f))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(ChromeColors.ripple(ChromeColors.accent)), null, ColorDrawable(Color.WHITE))
            addView(TextView(context).apply {
                text = label
                setTextColor(ChromeColors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                tag = VALUE_TAG
                text = value
                setTextColor(ChromeColors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "▾"
                setTextColor(ChromeColors.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(context, 8f), 0, dp(context, 2f), 0)
            })
            setOnClickListener { onClick() }
        }
}
