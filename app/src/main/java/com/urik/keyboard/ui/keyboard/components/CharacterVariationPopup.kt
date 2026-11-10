package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.urik.keyboard.R
import com.urik.keyboard.theme.ThemeManager

/**
 * Character variation popup for long-press key menu — drawn exactly like the compass keys' long-press strip
 * ([ExtraKeyStripPopup], 白い熊 2026-11-09): one rounded panel, glyphs packed tightly with no per-glyph boxes
 * (the rolling highlight marks the active one), at the strip's text size, wrapping onto more rows when the
 * screen is too narrow so every glyph stays within reach of the sliding finger.
 */
class CharacterVariationPopup(private val context: Context, private val themeManager: ThemeManager) : PopupWindow() {
    private var onVariationSelected: ((String) -> Unit)? = null
    private val characterButtons = mutableListOf<Button>()
    private var highlightedButton: Button? = null
    private var baseCharButton: Button? = null
    private val density = context.resources.displayMetrics.density

    /** Glyph size in px; the host sets the compass strip's own size (14 × its font scale × density). */
    var textSizePx: Float = 14f * DEFAULT_STRIP_FONT_SCALE * density
    private val cachedCornerRadius = 6f * density
    private val panelPad = (8 * density).toInt()
    private val cellPadH = (7 * density).toInt()
    private val cellPadV = (4 * density).toInt()

    private val scrollView: HorizontalScrollView =
        HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            ViewCompat.setAccessibilityDelegate(
                this,
                object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.removeAction(
                            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_TEXT
                        )
                        info.isEditable = false
                    }
                }
            )
        }

    /** The panel: a vertical stack of glyph rows. */
    private val variationContainer: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(panelPad, panelPad, panelPad, panelPad)
            background = GradientDrawable().apply {
                setColor(themeManager.currentTheme.value.colors.keyBackgroundAction)
                cornerRadius = 6f * density
            }
            elevation = 8f * density
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /** Every glyph cell in order, whatever row it landed on. */
    internal val cells: List<Button> get() = characterButtons

    init {
        scrollView.addView(variationContainer)
        contentView = scrollView
        isOutsideTouchable = true
        isFocusable = false
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        inputMethodMode = INPUT_METHOD_NOT_NEEDED

        setOnDismissListener {
            onVariationSelected = null
            variationContainer.removeAllViews()
            characterButtons.clear()
            highlightedButton = null
            baseCharButton = null
        }
    }

    /**
     * Configures popup with character variations.
     *
     * @param baseChar Base character to highlight (e.g., "a")
     * @param variations Available variations (e.g., ["á", "à", "â"])
     * @param onSelected Callback when variation selected
     */
    fun setCharacterVariations(baseChar: String, variations: List<String>, onSelected: (String) -> Unit) {
        this.onVariationSelected = onSelected

        variationContainer.removeAllViews()
        characterButtons.clear()
        highlightedButton = null
        baseCharButton = null

        val totalCount = variations.size + if (baseChar.isNotEmpty()) 1 else 0
        val glyphs = (if (baseChar.isNotEmpty()) listOf(baseChar) else emptyList()) + variations

        val measure = Paint().apply { textSize = textSizePx }
        val fm = measure.fontMetrics
        val rowHeight = (fm.bottom - fm.top).toInt() + cellPadV * 2
        val screenWidth = context.resources.displayMetrics.widthPixels
        val avail = (screenWidth - (16 * density).toInt() - panelPad * 2).coerceAtLeast(1)

        var row: LinearLayout? = null
        var x = 0
        var widest = 0
        var rows = 0
        glyphs.forEachIndexed { index, glyph ->
            val w = measure.measureText(glyph).toInt() + cellPadH * 2
            if (row == null || (x > 0 && x + w > avail)) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                variationContainer.addView(row)
                rows++
                x = 0
            }
            addCharacterView(row!!, glyph, w, rowHeight, isBase = baseChar.isNotEmpty() && index == 0, index, totalCount)
            x += w
            widest = maxOf(widest, x)
        }

        if (variations.isNotEmpty()) {
            setHighlighted(variations[0])
        }

        width = (widest + panelPad * 2).coerceAtLeast(1)
        height = (rows * rowHeight + panelPad * 2).coerceAtLeast(1)
    }

    private fun addCharacterView(
        row: LinearLayout,
        char: String,
        cellWidth: Int,
        rowHeight: Int,
        isBase: Boolean,
        index: Int,
        totalCount: Int
    ) {
        val button =
            Button(context).apply {
                text = char
                isAllCaps = false
                stateListAnimator = null
                layoutParams = LinearLayout.LayoutParams(cellWidth, rowHeight)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                gravity = Gravity.CENTER
                setTextColor(themeManager.currentTheme.value.colors.keyTextCharacter)
                background =
                    GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = cachedCornerRadius
                    }

                minHeight = 0
                minimumHeight = 0
                minWidth = 0
                minimumWidth = 0
                includeFontPadding = false
                setPadding(0, 0, 0, 0)

                isClickable = true
                isFocusable = true

                setOnClickListener {
                    onVariationSelected?.invoke(char)
                    dismiss()
                }

                contentDescription =
                    context.getString(
                        R.string.character_variation_position,
                        index + 1,
                        totalCount,
                        char
                    )

                ViewCompat.setAccessibilityDelegate(
                    this,
                    object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.roleDescription =
                                context.getString(R.string.character_option_role)
                            info.removeAction(
                                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_TEXT
                            )
                            info.isEditable = false
                        }
                    }
                )
            }

        if (isBase) baseCharButton = button
        characterButtons.add(button)
        row.addView(button)
    }

    /**
     * Positioning strategy:
     * 1. Prefers showing above anchor (standard long-press behavior)
     * 2. Falls back to below if insufficient space above
     * 3. Centers horizontally on anchor, respects screen edges
     * 4. Ensures popup fully visible within screen bounds
     */
    fun showAboveAnchor(anchorView: View) {
        if (!anchorView.isAttachedToWindow || anchorView.windowToken == null) {
            return
        }

        val gap = (8 * density).toInt()

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val anchorLocation = IntArray(2)
        anchorView.getLocationOnScreen(anchorLocation)
        val anchorX = anchorLocation[0]
        val anchorY = anchorLocation[1]
        val anchorWidth = anchorView.width
        val anchorHeight = anchorView.height

        val spaceBelow = screenHeight - (anchorY + anchorHeight)

        val showAbove = anchorY >= height + gap || anchorY > spaceBelow

        val verticalOffset =
            if (showAbove) {
                -(anchorHeight + height + gap)
            } else {
                gap
            }

        var horizontalOffset = (anchorWidth - width) / 2

        val popupLeft = anchorX + horizontalOffset
        val popupRight = popupLeft + width

        if (popupLeft < 0) {
            horizontalOffset -= popupLeft
        } else if (popupRight > screenWidth) {
            horizontalOffset -= popupRight - screenWidth
        }

        super.showAsDropDown(anchorView, horizontalOffset, verticalOffset)
    }

    /**
     * Dismisses popup if showing.
     *
     * Safe to call multiple times.
     */
    fun cleanup() {
        if (isShowing) {
            dismiss()
        }
    }

    /** The cell under local (x, y) of the panel, else the nearest one. */
    internal fun findButtonAt(localX: Float, localY: Float): Button? {
        if (characterButtons.isEmpty()) return null
        fun bounds(button: Button): FloatArray {
            val row = button.parent as View
            val l = row.left + button.left.toFloat()
            val t = row.top + button.top.toFloat()
            return floatArrayOf(l, t, l + button.width, t + button.height)
        }
        for (button in characterButtons) {
            val (l, t, r, b) = bounds(button).toList()
            if (localX in l..r && localY in t..b) return button
        }
        return characterButtons.minByOrNull { button ->
            val (l, t, r, b) = bounds(button).toList()
            val dx = localX - (l + r) / 2f
            val dy = localY - (t + b) / 2f
            dx * dx + dy * dy
        }
    }

    fun getCharacterAt(rawX: Float, rawY: Float): String? {
        if (!isShowing) return null
        val location = IntArray(2)
        variationContainer.getLocationOnScreen(location)
        return findButtonAt(rawX - location[0], rawY - location[1])?.text?.toString()
    }

    fun setHighlighted(char: String?) {
        val theme = themeManager.currentTheme.value

        highlightedButton?.let { button ->
            (button.background as? GradientDrawable)?.setColor(Color.TRANSPARENT)
            button.invalidate()
        }

        highlightedButton = null

        if (char != null) {
            val button = characterButtons.find { it.text == char }
            if (button != null) {
                highlightedButton = button
                (button.background as? GradientDrawable)?.setColor(theme.colors.statePressed)
                button.invalidate()
            }
        }
    }

    fun getHighlightedCharacter(): String? = highlightedButton?.text?.toString()

    private companion object {
        /** The compass strip's fallback font scale, as [KeyboardLayoutManager] uses it. */
        const val DEFAULT_STRIP_FONT_SCALE = 2.5f
    }
}
