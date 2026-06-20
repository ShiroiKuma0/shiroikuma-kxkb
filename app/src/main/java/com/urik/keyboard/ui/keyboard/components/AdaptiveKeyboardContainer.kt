package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardDisplayMode
import com.urik.keyboard.model.KeyboardModeConfig
import com.urik.keyboard.theme.ThemeColors
import com.urik.keyboard.theme.ThemeManager

/**
 * Container that applies adaptive layout transformations to the keyboard.
 *
 * Handles one-handed mode, split mode, and floating mode by:
 * - Scaling/positioning the keyboard view
 * - Showing mode toggle controls in the gap area
 * - Notifying listeners of coordinate transformations for gesture handling
 */
class AdaptiveKeyboardContainer
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private var keyboardView: View? = null
    private var currentConfig: KeyboardModeConfig = KeyboardModeConfig.standard()
    private var hingeBounds: Rect? = null
    private var themeManager: ThemeManager? = null

    private var onLayoutTransformListener: ((scaleFactor: Float, offsetX: Float) -> Unit)? = null
    private var onModeToggleListener: ((KeyboardDisplayMode) -> Unit)? = null

    // Live floating-rect change (drag/resize): fractions x/y in [0..1] of free travel, width in [0..1] of
    // container width, height a multiplier on the panel's natural (wrap-content) height. Host persists it.
    private var onFloatingRectChangeListener: ((Float, Float, Float, Float) -> Unit)? = null

    private var modeToggleBar: LinearLayout? = null
    private var leftButton: ImageView? = null
    private var centerButton: ImageView? = null
    private var rightButton: ImageView? = null

    // Floating-mode grip (drag) bar — a slim themed strip along the panel top that moves the whole panel.
    private var floatingGripBar: View? = null
    // Floating-mode resize handle — a small themed square at the panel's bottom-right corner (width+height).
    private var floatingResizeHandle: View? = null

    private var containerWidth = 0
    private var pendingModeApplication = false

    // Per-geometry look knobs that affect container layout (vs. per-key dims): width narrowing + bottom lift.
    private var lookWidthScale = 1f
    private var lookBottomLiftPx = 0

    // Floating panel rect knobs (FLOATING mode only). null width/height/x/y → the centred ~85% default.
    private var floatXFraction: Float? = null
    private var floatYFraction: Float? = null
    private var floatWidthFraction: Float? = null
    private var floatHeightScale: Float? = null
    // The panel's natural (un-scaled) wrap-content height in px, captured at layout — the 1:1 height ref.
    private var floatingNaturalHeightPx = 0

    /**
     * Apply the container-level look knobs: [widthScale] narrows the keyboard (centred) in standard/split
     * mode; [bottomLiftPx] lifts it off the bottom edge. Re-applies the current mode.
     */
    fun applyLookKnobs(widthScale: Float, bottomLiftPx: Int) {
        lookWidthScale = widthScale.coerceIn(0.3f, 1f)
        lookBottomLiftPx = bottomLiftPx.coerceAtLeast(0)
        requestModeApplication()
    }

    /**
     * Apply the persisted floating-panel rect (FLOATING mode only). Fractions are resolved to a concrete
     * px rect in [applyFloatingMode]; null fields fall back to the centred ~85%-width default. Re-applies
     * the current mode so a live edit (e.g. switching geometry) repositions the panel.
     *
     * In any NON-floating mode the rect is inert, so we only store the values and skip the re-apply — this
     * keeps the docked cold-start path (where the look resolve calls this right after [applyLookKnobs]) from
     * incurring a second, redundant `requestModeApplication()`/layout pass that raced the window re-measure
     * and could leave the first show clipped short. When the panel IS floating, a re-apply still runs so a
     * live rect edit repositions it.
     */
    fun applyFloatingKnobs(xFraction: Float?, yFraction: Float?, widthFraction: Float?, heightScale: Float?) {
        floatXFraction = xFraction
        floatYFraction = yFraction
        floatWidthFraction = widthFraction
        floatHeightScale = heightScale
        if (currentConfig.mode == KeyboardDisplayMode.FLOATING) {
            requestModeApplication()
        }
    }

    fun setOnFloatingRectChangeListener(listener: (Float, Float, Float, Float) -> Unit) {
        onFloatingRectChangeListener = listener
    }

    /**
     * In FLOATING mode, fill [outRect] with the panel's bounds (keyboard view + the grip strip above it),
     * in this container's own coordinate space, and return true. Returns false in any other mode (the caller
     * then uses the docked recipe). The host offsets this by the container's position to build the touchable
     * region for onComputeInsets, so taps outside the panel pass through to the app.
     */
    fun getFloatingPanelRect(outRect: Rect): Boolean {
        if (currentConfig.mode != KeyboardDisplayMode.FLOATING) return false
        val view = keyboardView ?: return false
        val params = view.layoutParams as? LayoutParams ?: return false
        // Include the grip strip drawn just above the keyboard view so its drag area is touchable too.
        val density = resources.displayMetrics.density
        val gripH = (FLOAT_GRIP_HEIGHT_DP * density).toInt()
        val left = params.marginStart
        val top = (params.topMargin - gripH).coerceAtLeast(0)
        outRect.set(left, top, left + params.width, params.topMargin + params.height)
        return true
    }

    private val layoutListener =
        ViewTreeObserver.OnGlobalLayoutListener {
            val newWidth = width
            if (newWidth > 0 && newWidth != containerWidth) {
                containerWidth = newWidth
                if (pendingModeApplication) {
                    pendingModeApplication = false
                    applyCurrentModeInternal()
                }
            }
        }

    init {
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    /**
     * FLOATING mode needs vertical room for the panel to float above the bottom edge, but the host root is
     * WRAP_CONTENT (sized to the docked keyboard). Force the container tall — to the available display height
     * — only when floating; in every other mode fall through to FrameLayout's normal wrap-content measure so
     * docked rendering is byte-identical.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (currentConfig.mode == KeyboardDisplayMode.FLOATING) {
            val targetH = floatingTargetContainerHeight()
            val forcedH = MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY)
            super.onMeasure(widthMeasureSpec, forcedH)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** The tall container height for floating: the usable display height (capped) so the panel has travel. */
    private fun floatingTargetContainerHeight(): Int {
        val metrics = resources.displayMetrics
        // ~92% of the display height — leaves a hair at the very top so the panel never overlaps the status bar.
        return (metrics.heightPixels * 0.92f).toInt().coerceAtLeast(1)
    }

    fun setThemeManager(manager: ThemeManager) {
        themeManager = manager
        updateThemeColors()
    }

    fun setOnLayoutTransformListener(listener: (Float, Float) -> Unit) {
        onLayoutTransformListener = listener
    }

    fun setOnModeToggleListener(listener: (KeyboardDisplayMode) -> Unit) {
        onModeToggleListener = listener
    }

    fun setKeyboardView(view: View) {
        keyboardView?.let { removeView(it) }
        keyboardView = view
        addView(
            view,
            0,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
        )
        requestModeApplication()
    }

    fun setModeConfig(config: KeyboardModeConfig, hingeBounds: Rect? = null) {
        this.currentConfig = config
        this.hingeBounds = hingeBounds
        requestModeApplication()
    }

    private fun requestModeApplication() {
        if (containerWidth > 0) {
            applyCurrentModeInternal()
        } else {
            pendingModeApplication = true
        }
    }

    private fun applyCurrentModeInternal() {
        val view = keyboardView ?: return
        if (containerWidth <= 0) return

        // Leaving FLOATING must fully restore docked rendering — the floating branch set an explicit pixel
        // height + a top margin on the keyboard view; reset those to WRAP_CONTENT / 0 here so the docked
        // apply methods (which only touch width / gravity / horizontal margins) start from a clean slate.
        if (currentConfig.mode != KeyboardDisplayMode.FLOATING) {
            (view.layoutParams as? LayoutParams)?.let { p ->
                if (p.height != LayoutParams.WRAP_CONTENT || p.topMargin != 0) {
                    p.height = LayoutParams.WRAP_CONTENT
                    p.topMargin = 0
                    view.layoutParams = p
                }
            }
        }

        when (currentConfig.mode) {
            KeyboardDisplayMode.STANDARD -> applyStandardMode(view)
            KeyboardDisplayMode.ONE_HANDED_LEFT -> applyOneHandedMode(view, anchorLeft = true)
            KeyboardDisplayMode.ONE_HANDED_RIGHT -> applyOneHandedMode(view, anchorLeft = false)
            KeyboardDisplayMode.SPLIT -> applySplitMode(view)
            KeyboardDisplayMode.FLOATING -> applyFloatingMode(view)
        }

        updateToggleBarState()
        updateFloatingGripState()
        notifyLayoutTransform()
    }

    private fun applyStandardMode(view: View) {
        val params =
            view.layoutParams as? LayoutParams
                ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

        if (lookWidthScale < 1f && containerWidth > 0) {
            params.width = (containerWidth * lookWidthScale).toInt()
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        } else {
            params.width = LayoutParams.MATCH_PARENT
            params.gravity = Gravity.BOTTOM
        }
        params.marginStart = 0
        params.marginEnd = 0
        params.bottomMargin = lookBottomLiftPx
        view.layoutParams = params
    }

    private fun applyOneHandedMode(view: View, anchorLeft: Boolean) {
        val params =
            view.layoutParams as? LayoutParams
                ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

        val keyboardWidth = (containerWidth * currentConfig.widthFactor).toInt()
        params.width = keyboardWidth
        params.gravity = Gravity.BOTTOM or if (anchorLeft) Gravity.START else Gravity.END
        params.marginStart = 0
        params.marginEnd = 0
        view.layoutParams = params
    }

    private fun applySplitMode(view: View) {
        val params =
            view.layoutParams as? LayoutParams
                ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

        params.width = LayoutParams.MATCH_PARENT
        params.gravity = Gravity.BOTTOM
        params.marginStart = 0
        params.marginEnd = 0
        params.bottomMargin = lookBottomLiftPx
        view.layoutParams = params
    }

    /**
     * Floating panel: size the keyboard view to the persisted (or default) rect — an explicit width and an
     * absolute (left, top) position via margins, anchored TOP|START so margins are screen-absolute. The
     * input view itself stays full-screen + transparent (the host reports only this rect as touchable in
     * onComputeInsets), so taps outside the panel pass through to the app behind.
     */
    private fun applyFloatingMode(view: View) {
        val params =
            view.layoutParams as? LayoutParams
                ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

        val cw = containerWidth.coerceAtLeast(1)
        // Use the FORCED tall container height (what onMeasure makes it) as the vertical reference, not the
        // possibly-stale current `height` — so the panel positions correctly even on the first floating apply
        // (before the tall measure pass has run).
        val ch = maxOf(height, floatingTargetContainerHeight()).coerceAtLeast(1)

        // Natural (wrap-content) height: measure the view at the target width if we don't have it yet.
        val widthFraction = (floatWidthFraction ?: DEFAULT_FLOAT_WIDTH_FRACTION).coerceIn(0.3f, 1f)
        val panelW = (cw * widthFraction).toInt().coerceAtLeast(1)
        val natural = measureNaturalHeight(view, panelW)
        floatingNaturalHeightPx = natural
        val heightScale = (floatHeightScale ?: 1f).coerceIn(0.5f, 3f)
        val panelH = (natural * heightScale).toInt().coerceAtLeast(1)

        // Resolve x/y fractions over the free travel; default = centred horizontally, ~12% up from the bottom.
        val maxX = (cw - panelW).coerceAtLeast(0)
        val maxY = (ch - panelH).coerceAtLeast(0)
        val xFrac = (floatXFraction ?: 0.5f).coerceIn(0f, 1f)
        val yFrac = (floatYFraction ?: DEFAULT_FLOAT_Y_FRACTION).coerceIn(0f, 1f)
        val left = (maxX * xFrac).toInt()
        val top = (maxY * yFrac).toInt()

        params.width = panelW
        params.height = panelH
        params.gravity = Gravity.TOP or Gravity.START
        params.marginStart = left
        params.topMargin = top
        params.marginEnd = 0
        params.bottomMargin = 0
        view.layoutParams = params
    }

    /** Measure the keyboard view's natural wrap-content height at [widthPx]; falls back to its current height. */
    private fun measureNaturalHeight(view: View, widthPx: Int): Int {
        return try {
            val wSpec = MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY)
            val hSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            view.measure(wSpec, hSpec)
            view.measuredHeight.takeIf { it > 0 } ?: view.height.coerceAtLeast(1)
        } catch (_: Exception) {
            view.height.coerceAtLeast(1)
        }
    }

    /** Show + position the floating grip + resize handle in FLOATING mode; hide them otherwise. */
    private fun updateFloatingGripState() {
        if (currentConfig.mode == KeyboardDisplayMode.FLOATING) {
            ensureFloatingHandlesCreated()
            positionFloatingGrip()
            floatingGripBar?.visibility = VISIBLE
            floatingResizeHandle?.visibility = VISIBLE
            // Keep the handles drawn above the keyboard view.
            floatingGripBar?.bringToFront()
            floatingResizeHandle?.bringToFront()
        } else {
            floatingGripBar?.visibility = GONE
            floatingResizeHandle?.visibility = GONE
        }
    }

    private fun ensureFloatingHandlesCreated() {
        if (floatingGripBar == null) {
            val grip = View(context)
            grip.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            grip.setOnTouchListener(FloatingGripTouchListener())
            floatingGripBar = grip
            addView(grip)
        }
        if (floatingResizeHandle == null) {
            val handle = View(context)
            handle.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            handle.setOnTouchListener(FloatingResizeTouchListener())
            floatingResizeHandle = handle
            addView(handle)
        }
        updateThemeColors()
    }

    /** The grip sits as a slim strip across the top of the panel; the resize handle at its bottom-right. */
    private fun positionFloatingGrip() {
        val view = keyboardView ?: return
        val kbParams = view.layoutParams as? LayoutParams ?: return
        val density = resources.displayMetrics.density

        floatingGripBar?.let { grip ->
            val gripH = (FLOAT_GRIP_HEIGHT_DP * density).toInt()
            val params = grip.layoutParams as? LayoutParams ?: LayoutParams(kbParams.width, gripH)
            params.width = kbParams.width
            params.height = gripH
            params.gravity = Gravity.TOP or Gravity.START
            params.marginStart = kbParams.marginStart
            // Sit the grip just ABOVE the keyboard view so it never covers the suggestion bar / top keys.
            params.topMargin = (kbParams.topMargin - gripH).coerceAtLeast(0)
            grip.layoutParams = params
        }

        floatingResizeHandle?.let { handle ->
            val size = (FLOAT_HANDLE_SIZE_DP * density).toInt()
            val params = handle.layoutParams as? LayoutParams ?: LayoutParams(size, size)
            params.width = size
            params.height = size
            params.gravity = Gravity.TOP or Gravity.START
            params.marginStart = (kbParams.marginStart + kbParams.width - size).coerceAtLeast(0)
            params.topMargin = (kbParams.topMargin + kbParams.height - size).coerceAtLeast(0)
            handle.layoutParams = params
        }
    }

    /**
     * Resizes the panel from the bottom-right corner: horizontal drag changes width, vertical drag changes
     * height; both clamp to sane min/max and re-publish the rect (host persists). The top-left stays put.
     */
    private inner class FloatingResizeTouchListener : android.view.View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startW = 0
        private var startH = 0

        override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
            val view = keyboardView ?: return false
            val params = view.layoutParams as? LayoutParams ?: return false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startW = params.width
                    startH = params.height
                    return true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    val cw = containerWidth.coerceAtLeast(1)
                    val ch = maxOf(height, floatingTargetContainerHeight()).coerceAtLeast(1)
                    val natural = floatingNaturalHeightPx.coerceAtLeast(1)
                    val minW = (cw * 0.3f).toInt().coerceAtLeast(1)
                    val maxW = (cw - params.marginStart).coerceAtLeast(minW)
                    val minH = (natural * 0.5f).toInt().coerceAtLeast(1)
                    val maxH = ((natural * 3f).toInt()).coerceAtMost((ch - params.topMargin).coerceAtLeast(minH))
                    params.width = (startW + (event.rawX - downRawX)).toInt().coerceIn(minW, maxW)
                    params.height = (startH + (event.rawY - downRawY)).toInt().coerceIn(minH, maxH.coerceAtLeast(minH))
                    view.layoutParams = params
                    positionFloatingGrip()
                    publishFloatingRect()
                    return true
                }

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    publishFloatingRect()
                    return true
                }
            }
            return false
        }
    }

    /**
     * Drags the whole panel: a finger on the grip moves the keyboard view's (left, top) margins, clamped to
     * screen bounds, and publishes the new x/y fractions live (the host persists them). A long-ish quick tap
     * still moves nothing — only ACTION_MOVE shifts it.
     */
    private inner class FloatingGripTouchListener : android.view.View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startLeft = 0
        private var startTop = 0

        override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
            val view = keyboardView ?: return false
            val params = view.layoutParams as? LayoutParams ?: return false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startLeft = params.marginStart
                    startTop = params.topMargin
                    return true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    val cw = containerWidth.coerceAtLeast(1)
                    val ch = maxOf(height, floatingTargetContainerHeight()).coerceAtLeast(1)
                    val maxX = (cw - params.width).coerceAtLeast(0)
                    val maxY = (ch - params.height).coerceAtLeast(0)
                    val newLeft = (startLeft + (event.rawX - downRawX)).toInt().coerceIn(0, maxX)
                    val newTop = (startTop + (event.rawY - downRawY)).toInt().coerceIn(0, maxY)
                    params.marginStart = newLeft
                    params.topMargin = newTop
                    view.layoutParams = params
                    positionFloatingGrip()
                    publishFloatingRect()
                    return true
                }

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    publishFloatingRect()
                    return true
                }
            }
            return false
        }
    }

    /** Compute the current panel rect's fractions and publish them to the host for live persistence. */
    private fun publishFloatingRect() {
        val view = keyboardView ?: return
        val params = view.layoutParams as? LayoutParams ?: return
        val cw = containerWidth.coerceAtLeast(1)
        // Use the SAME vertical reference applyFloatingMode does (the forced tall height), so the y-fraction
        // round-trips: publish here → re-apply there lands the panel back at the identical top.
        val ch = maxOf(height, floatingTargetContainerHeight()).coerceAtLeast(1)
        val maxX = (cw - params.width).coerceAtLeast(1)
        val maxY = (ch - params.height).coerceAtLeast(1)
        val xFrac = (params.marginStart.toFloat() / maxX).coerceIn(0f, 1f)
        val yFrac = (params.topMargin.toFloat() / maxY).coerceIn(0f, 1f)
        val wFrac = (params.width.toFloat() / cw).coerceIn(0.3f, 1f)
        val natural = floatingNaturalHeightPx.coerceAtLeast(1)
        val hScale = (params.height.toFloat() / natural).coerceIn(0.5f, 3f)
        // Keep the in-memory knobs in sync so a re-apply (e.g. theme change) doesn't snap the panel back.
        floatXFraction = xFrac
        floatYFraction = yFrac
        floatWidthFraction = wFrac
        floatHeightScale = hScale
        onFloatingRectChangeListener?.invoke(xFrac, yFrac, wFrac, hScale)
    }

    private fun updateToggleBarState() {
        val isOneHanded =
            currentConfig.mode == KeyboardDisplayMode.ONE_HANDED_LEFT ||
                currentConfig.mode == KeyboardDisplayMode.ONE_HANDED_RIGHT

        if (isOneHanded) {
            ensureToggleBarCreated()
            positionToggleBar()
            modeToggleBar?.visibility = VISIBLE
            updateToggleButtonStates()
        } else {
            modeToggleBar?.visibility = GONE
        }
    }

    private fun ensureToggleBarCreated() {
        if (modeToggleBar != null) return

        val density = resources.displayMetrics.density
        val buttonSize = (48 * density).toInt()
        val buttonMargin = (8 * density).toInt()

        modeToggleBar =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams =
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    }
            }

        leftButton =
            createToggleButton(R.drawable.arrow_back_48px, R.string.one_handed_mode_left) {
                onModeToggleListener?.invoke(KeyboardDisplayMode.ONE_HANDED_LEFT)
            }

        centerButton =
            createToggleButton(R.drawable.space_bar_48px, R.string.one_handed_mode_full) {
                onModeToggleListener?.invoke(KeyboardDisplayMode.STANDARD)
            }

        rightButton =
            createToggleButton(R.drawable.arrow_forward_48px, R.string.one_handed_mode_right) {
                onModeToggleListener?.invoke(KeyboardDisplayMode.ONE_HANDED_RIGHT)
            }

        val buttonParams =
            LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                setMargins(buttonMargin, buttonMargin, buttonMargin, buttonMargin)
            }

        modeToggleBar?.addView(leftButton, buttonParams)
        modeToggleBar?.addView(centerButton, buttonParams)
        modeToggleBar?.addView(rightButton, buttonParams)

        addView(modeToggleBar)
        updateThemeColors()
    }

    private fun createToggleButton(iconRes: Int, contentDescRes: Int, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(iconRes)
            contentDescription = context.getString(contentDescRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true

            val padding = (8 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)

            setOnClickListener { onClick() }

            ViewCompat.setAccessibilityDelegate(
                this,
                object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.roleDescription = context.getString(R.string.toggle_button_role)
                    }
                }
            )
        }

    private fun positionToggleBar() {
        val bar = modeToggleBar ?: return
        val params = bar.layoutParams as? LayoutParams ?: return

        val gapWidth = (containerWidth * (1f - currentConfig.widthFactor)).toInt()
        params.width = gapWidth

        when (currentConfig.mode) {
            KeyboardDisplayMode.ONE_HANDED_LEFT -> {
                params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                params.marginEnd = 0
                params.marginStart = 0
            }

            KeyboardDisplayMode.ONE_HANDED_RIGHT -> {
                params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                params.marginStart = 0
                params.marginEnd = 0
            }

            else -> {}
        }

        bar.layoutParams = params
    }

    private fun updateToggleButtonStates() {
        val colors = themeManager?.currentTheme?.value?.colors ?: return

        val activeColor = colors.stateActivated
        val inactiveColor = colors.keyBackgroundAction

        when (currentConfig.mode) {
            KeyboardDisplayMode.ONE_HANDED_LEFT -> {
                setButtonBackground(leftButton, activeColor, colors)
                setButtonBackground(centerButton, inactiveColor, colors)
                setButtonBackground(rightButton, inactiveColor, colors)
            }

            KeyboardDisplayMode.ONE_HANDED_RIGHT -> {
                setButtonBackground(leftButton, inactiveColor, colors)
                setButtonBackground(centerButton, inactiveColor, colors)
                setButtonBackground(rightButton, activeColor, colors)
            }

            else -> {
                setButtonBackground(leftButton, inactiveColor, colors)
                setButtonBackground(centerButton, inactiveColor, colors)
                setButtonBackground(rightButton, inactiveColor, colors)
            }
        }

        leftButton?.let {
            ViewCompat.setStateDescription(
                it,
                if (currentConfig.mode == KeyboardDisplayMode.ONE_HANDED_LEFT) {
                    context.getString(R.string.state_active)
                } else {
                    context.getString(R.string.state_inactive)
                }
            )
        }
        centerButton?.let {
            ViewCompat.setStateDescription(
                it,
                if (currentConfig.mode == KeyboardDisplayMode.STANDARD) {
                    context.getString(R.string.state_active)
                } else {
                    context.getString(R.string.state_inactive)
                }
            )
        }
        rightButton?.let {
            ViewCompat.setStateDescription(
                it,
                if (currentConfig.mode == KeyboardDisplayMode.ONE_HANDED_RIGHT) {
                    context.getString(R.string.state_active)
                } else {
                    context.getString(R.string.state_inactive)
                }
            )
        }
    }

    private fun setButtonBackground(button: ImageView?, backgroundColor: Int, colors: ThemeColors) {
        button ?: return

        val cornerRadius = 12 * resources.displayMetrics.density
        val backgroundDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(backgroundColor)
                this.cornerRadius = cornerRadius
            }

        val rippleColor = ColorStateList.valueOf(colors.statePressed)
        val rippleDrawable = RippleDrawable(rippleColor, backgroundDrawable, null)

        button.background = rippleDrawable
        button.setColorFilter(colors.keyTextAction)
    }

    private fun updateThemeColors() {
        val colors = themeManager?.currentTheme?.value?.colors ?: return

        // Transparent container so the bottom-lift gap + narrowed sides show the app through; the
        // keyboard view itself carries the opaque background. The one-handed toggle bar stays opaque.
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        modeToggleBar?.setBackgroundColor(colors.keyboardBackground)
        // Floating handles: black bar with a yellow grip pill / corner square (the signature look). The
        // grip is a rounded yellow pill centred on a black strip; the resize handle a yellow rounded square.
        val density = resources.displayMetrics.density
        floatingGripBar?.let { grip ->
            val pill =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4f * density
                    setColor(colors.keyTextCharacter)
                }
            // Black strip with a yellow grip pill inset vertically so a black margin frames it top + bottom.
            val inset = (5f * density).toInt()
            grip.background =
                android.graphics.drawable.LayerDrawable(
                    arrayOf(
                        android.graphics.drawable.ColorDrawable(colors.keyboardBackground),
                        pill
                    )
                ).apply {
                    setLayerInset(1, inset, inset, inset, inset)
                }
        }
        floatingResizeHandle?.background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4f * density
                setColor(colors.keyTextCharacter)
                setStroke((1.5f * density).toInt(), colors.keyboardBackground)
            }
        updateToggleButtonStates()
    }

    private fun notifyLayoutTransform() {
        onLayoutTransformListener?.invoke(1.0f, 0f)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
    }

    companion object {
        /** Floating panel sane first-run default: centred, ~85% width, ~12% up from the bottom edge. */
        private const val DEFAULT_FLOAT_WIDTH_FRACTION = 0.85f
        private const val DEFAULT_FLOAT_Y_FRACTION = 0.88f
        /** Heights (dp) of the floating drag-grip strip and the bottom-right resize handle square. */
        private const val FLOAT_GRIP_HEIGHT_DP = 22f
        private const val FLOAT_HANDLE_SIZE_DP = 28f
    }
}
