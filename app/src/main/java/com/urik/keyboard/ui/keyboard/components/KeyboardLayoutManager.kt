package com.urik.keyboard.ui.keyboard.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.TextViewCompat
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.AdaptiveDimensions
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.settings.KeyLabelSize
import com.urik.keyboard.settings.KeySize
import com.urik.keyboard.settings.LongPressDuration
import com.urik.keyboard.settings.LongPressPunctuationMode
import com.urik.keyboard.settings.SpaceBarSize
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PendingCallbacks(val handler: Handler, val runnable: Runnable)

class KeyboardLayoutManager(
    private val context: Context,
    private val onKeyClick: (KeyboardKey) -> Unit,
    private val onAcceleratedDeletionChanged: (Boolean) -> Unit,
    private val onSymbolsLongPress: () -> Unit,
    private val onLanguageSwitch: (String) -> Unit = {},
    private val onSwitchToLayout: (String, String) -> Unit = { _, _ -> },
    private val onMenuAction: (String) -> Unit = {},
    private val onShowInputMethodPicker: () -> Unit = {},
    private val onFlickBinding: (KeyboardKey.FlickBinding) -> Unit = {},
    private val characterVariationService: CharacterVariationService,
    private val languageManager: LanguageManager,
    private val themeManager: ThemeManager,
    cacheMemoryManager: CacheMemoryManager
) {
    private var clipboardEnabled = false
    private var activeLanguages: List<String> = emptyList()
    private var showLanguageSwitchKey = false
    private var showNumberHints = false
    private var hasMultipleImes = false
    private var pressHighlightEnabled = true

    var effectiveLayout: KeyboardLayout? = null
        private set

    @Volatile
    private var customKeyMappings: Map<String, List<String>> = emptyMap()
    private val keyHintRenderer = KeyHintRenderer(context)

    private val vibrator =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    private val supportsAmplitudeControl = vibrator?.hasAmplitudeControl() == true
    private val vibrationAttributes =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH)
        } else {
            null
        }
    private var hapticEnabled = true
    private var hapticAmplitude = 170

    var onDeleteWord: (() -> Unit)? = null

    @VisibleForTesting
    internal var onHapticFired: ((KeyboardKey?) -> Unit)? = null

    private val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    private var currentLongPressDuration = LongPressDuration.MEDIUM
    private var currentKeySize = KeySize.MEDIUM
    private var currentSpaceBarSize = SpaceBarSize.STANDARD
    private var currentKeyLabelSize = KeyLabelSize.MEDIUM
    private var longPressPunctuationMode = LongPressPunctuationMode.PERIOD
    private var splitGapPx = 0
    private var adaptiveDimensions: AdaptiveDimensions? = null

    private var activePunctuationPopup: CharacterVariationPopup? = null
    private var popupSelectionMode = false
    private var swipeKeyboardView: SwipeKeyboardView? = null
    private val flickGestureDetector = FlickGestureDetector().also { detector ->
        detector.updateDisplayMetrics(context.resources.displayMetrics.density)
        detector.setFlickListener(object : FlickGestureDetector.FlickListener {
            override fun onFlickStart(key: KeyboardKey.FlickKey, anchorX: Float, anchorY: Float) {}
            override fun onFlickDirectionChanged(
                key: KeyboardKey.FlickKey,
                direction: FlickGestureDetector.FlickDirection
            ) {
                flickPopup?.updateHighlight(direction)
            }
            override fun onFlickCommit(key: KeyboardKey.FlickKey, direction: FlickGestureDetector.FlickDirection) {
                flickPopup?.dismiss()
                flickPopup = null
                val pos = effectiveFlickPosition(key, direction)
                val binding = key.bindings[pos]
                if (binding != null) {
                    handleFlickBinding(binding)
                    return
                }
                // Commit the shifted face (uppercase / katakana) when shift or caps-lock is engaged.
                val face = flickFace(key, lastKeyboardState)
                val char = flickCharAt(face, pos) ?: face.center
                onKeyClick(KeyboardKey.Character(char, key.type))
            }
            override fun onFlickCancel() {
                flickPopup?.dismiss()
                flickPopup = null
            }
            override fun onActionTap(key: KeyboardKey.Action) {
                onKeyClick(key)
            }
        })
    }
    private var flickPopup: FlickPopup? = null

    private fun flickCharAt(key: KeyboardKey.FlickKey, pos: String): String? = when (pos) {
        "center" -> key.center
        "up" -> key.up
        "down" -> key.down
        "left" -> key.left
        "right" -> key.right
        "upLeft" -> key.upLeft
        "upRight" -> key.upRight
        "downLeft" -> key.downLeft
        "downRight" -> key.downRight
        else -> null
    }

    private fun flickHasContent(key: KeyboardKey.FlickKey, pos: String): Boolean =
        key.bindings.containsKey(pos) || flickCharAt(key, pos) != null

    /** First position with content, used so an empty diagonal falls back to the nearest cardinal. */
    private fun firstFlickPosition(key: KeyboardKey.FlickKey, vararg candidates: String): String =
        candidates.firstOrNull { flickHasContent(key, it) } ?: "center"

    private fun effectiveFlickPosition(
        key: KeyboardKey.FlickKey,
        direction: FlickGestureDetector.FlickDirection
    ): String = when (direction) {
        FlickGestureDetector.FlickDirection.NONE -> "center"
        FlickGestureDetector.FlickDirection.UP -> "up"
        FlickGestureDetector.FlickDirection.DOWN -> "down"
        FlickGestureDetector.FlickDirection.LEFT -> "left"
        FlickGestureDetector.FlickDirection.RIGHT -> "right"
        FlickGestureDetector.FlickDirection.UP_LEFT -> firstFlickPosition(key, "upLeft", "up", "left")
        FlickGestureDetector.FlickDirection.UP_RIGHT -> firstFlickPosition(key, "upRight", "up", "right")
        FlickGestureDetector.FlickDirection.DOWN_LEFT -> firstFlickPosition(key, "downLeft", "down", "left")
        FlickGestureDetector.FlickDirection.DOWN_RIGHT -> firstFlickPosition(key, "downRight", "down", "right")
    }

    private fun handleFlickBinding(binding: KeyboardKey.FlickBinding) {
        onFlickBinding(binding)
    }

    private fun flickHintLabels(key: KeyboardKey.FlickKey): Map<String, String> = buildMap {
        key.up?.let { put("up", it) }
        key.down?.let { put("down", it) }
        key.left?.let { put("left", it) }
        key.right?.let { put("right", it) }
        key.upLeft?.let { put("upLeft", it) }
        key.upRight?.let { put("upRight", it) }
        key.downLeft?.let { put("downLeft", it) }
        key.downRight?.let { put("downRight", it) }
    }

    /**
     * Overlays a compass/cluster key's secondary characters at rest (when the layout sets showFlickHints),
     * grouped into a **top row** (up / up-diagonals), a **bottom row** (down / down-diagonals) and the
     * **side** chars (left / right) on the centre line. Each row paints with its own colour, size and font
     * (from the per-geometry look knobs); the side chars share the top row's style.
     */
    private fun createFlickHintsBackground(keyBackground: Drawable, key: KeyboardKey.FlickKey): Drawable {
        val hints = buildFlickHintsDrawable(key, flickHintLabels(key)) ?: return keyBackground
        return LayerDrawable(arrayOf(keyBackground, hints))
    }

    /** The secondary-character (top/bottom/side) hints drawable for [labels], or null if none. */
    private fun buildFlickHintsDrawable(key: KeyboardKey.FlickKey, labels: Map<String, String>): Drawable? {
        if (labels.isEmpty()) return null
        val dims = adaptiveDimensions
        val density = context.resources.displayMetrics.density
        val baseHintPx = 9f * density * (dims?.hintScale ?: 1f)
        // Default hint colour: the key text colour, dimmed to ~70% so hints read as secondary.
        val dimmed = (getKeyTextColor(key) and 0x00FFFFFF) or 0xB4000000.toInt()
        // Resolution per row: row override -> general secondary -> renderer default.
        val generalColor = dims?.hintColor
        val generalFont = dims?.hintFont
        val generalWeight = dims?.hintWeight

        fun rowPaint(color: Int?, scale: Float, font: String?) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color ?: generalColor ?: dimmed
            textAlign = Paint.Align.CENTER
            textSize = baseHintPx * scale
            val family = font ?: generalFont ?: dims?.fontFamily ?: ""
            typeface =
                if (generalWeight != null && generalWeight > 0) {
                    com.urik.keyboard.service.KeyboardFonts.weightedTypeface(context, family, generalWeight)
                } else {
                    com.urik.keyboard.service.KeyboardFonts.typeface(context, family)
                }
        }

        val pad = (4f * density).toInt()
        return FlickHintsDrawable(
            labels,
            topPaint = rowPaint(dims?.hintTopColor, dims?.hintTopScale ?: 1f, dims?.hintTopFont),
            bottomPaint = rowPaint(dims?.hintBottomColor, dims?.hintBottomScale ?: 1f, dims?.hintBottomFont),
            sidePaint = rowPaint(dims?.hintTopColor, dims?.hintTopScale ?: 1f, dims?.hintTopFont),
            topMarginPx = dims?.hintTopMarginPx ?: pad,
            bottomMarginPx = dims?.hintBottomMarginPx ?: pad,
            leftMarginPx = dims?.hintLeftMarginPx ?: pad,
            rightMarginPx = dims?.hintRightMarginPx ?: pad
        )
    }

    /**
     * A cluster key: its band of main characters drawn as PRIMARY glyphs across the centre (e.g. "mwk"),
     * plus the up/down/diagonal secondary hints — but NOT left/right (those ARE the neighbour mains).
     */
    private fun createClusterBackground(
        keyBackground: Drawable,
        key: KeyboardKey.FlickKey,
        primaryTextSp: Float
    ): Drawable {
        val mainsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = getKeyTextColor(key)
            textAlign = Paint.Align.CENTER
            typeface = keyLabelTypeface()
        }
        // SP -> px exactly as the button does (includes the system font scale), so a cluster main is the
        // SAME size as a single compass label — never proportionally smaller.
        val basePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, primaryTextSp, context.resources.displayMetrics
        )
        val layers = mutableListOf(keyBackground, ClusterMainsDrawable(key.clusterMains, mainsPaint, basePx))
        val hintLabels = flickHintLabels(key).filterKeys { it != "left" && it != "right" }
        buildFlickHintsDrawable(key, hintLabels)?.let { layers.add(it) }
        return LayerDrawable(layers.toTypedArray())
    }

    /** The typeface used for key labels (family + weight/bold from the look knobs). */
    private fun keyLabelTypeface(): android.graphics.Typeface {
        val family = adaptiveDimensions?.fontFamily ?: ""
        val weight = adaptiveDimensions?.labelWeight
        return if (weight != null && weight > 0) {
            com.urik.keyboard.service.KeyboardFonts.weightedTypeface(context, family, weight)
        } else {
            com.urik.keyboard.service.KeyboardFonts.typeface(context, family, adaptiveDimensions?.boldKeyLabels == true)
        }
    }

    /** Draws each character of a cluster band centred in its own horizontal slot at (fit-to-width) primary size. */
    private class ClusterMainsDrawable(
        private val mains: String,
        private val paint: Paint,
        private val basePx: Float
    ) : Drawable() {
        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty || mains.isEmpty()) return
            // Always the PRIMARY size — never shrunk to fit (like FUTO): the band is packed by natural
            // advance, centred on the key, and allowed to overflow into the neighbouring keys.
            paint.textSize = basePx
            paint.textAlign = Paint.Align.LEFT
            val advances = FloatArray(mains.length) { paint.measureText(mains[it].toString()) }
            val total = advances.sum()
            var x = b.exactCenterX() - total / 2f
            val fm = paint.fontMetrics
            val cy = b.exactCenterY() - (fm.ascent + fm.descent) / 2f
            for (i in mains.indices) {
                canvas.drawText(mains[i].toString(), x, cy, paint)
                x += advances[i]
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class FlickHintsDrawable(
        private val labels: Map<String, String>,
        private val topPaint: Paint,
        private val bottomPaint: Paint,
        private val sidePaint: Paint,
        private val topMarginPx: Int,
        private val bottomMarginPx: Int,
        private val leftMarginPx: Int,
        private val rightMarginPx: Int
    ) : Drawable() {
        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val cx = b.exactCenterX()
            val topY = b.top + topMarginPx + topPaint.textSize
            val botY = (b.bottom - bottomMarginPx).toFloat()
            val midY = b.exactCenterY() + sidePaint.textSize * 0.35f
            fun leftX(p: Paint) = b.left + leftMarginPx + p.textSize * 0.55f
            fun rightX(p: Paint) = b.right - rightMarginPx - p.textSize * 0.55f
            fun t(k: String, x: Float, y: Float, p: Paint) {
                labels[k]?.let { canvas.drawText(it, x, y, p) }
            }
            // Top row.
            t("up", cx, topY, topPaint)
            t("upLeft", leftX(topPaint), topY, topPaint)
            t("upRight", rightX(topPaint), topY, topPaint)
            // Bottom row.
            t("down", cx, botY, bottomPaint)
            t("downLeft", leftX(bottomPaint), botY, bottomPaint)
            t("downRight", rightX(bottomPaint), botY, bottomPaint)
            // Sides (centre line).
            t("left", leftX(sidePaint), midY, sidePaint)
            t("right", rightX(sidePaint), midY, sidePaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private var backgroundJob = SupervisorJob()
    private var backgroundScope = CoroutineScope(Dispatchers.IO + backgroundJob)

    private val buttonPool = mutableListOf<Button>()
    private val activeButtons = mutableSetOf<Button>()
    private val buttonPendingCallbacks = ConcurrentHashMap<Button, PendingCallbacks>()
    private val buttonLongPressRunnables = HashMap<Button, Runnable>()
    private val symbolsLongPressFired = ConcurrentHashMap.newKeySet<Button>()
    private val customMappingLongPressFired = ConcurrentHashMap.newKeySet<Button>()
    private val characterLongPressFired = ConcurrentHashMap.newKeySet<Button>()
    private val longPressConsumedButtons = ConcurrentHashMap.newKeySet<Button>()
    private val hapticDownFiredButtons = ConcurrentHashMap.newKeySet<Button>()

    @VisibleForTesting
    internal val pressStartTimes = ConcurrentHashMap<Button, Long>()

    private val cachedTextSizes = mutableMapOf<Int, Float>()
    private val cachedDimensions = mutableMapOf<String, Int>()
    private var cacheValid = false

    private var cachedCornerRadius = 0f
    private var cachedStrokeWidth = 0
    private var cachedStrokeWidthThick = 0

    private val backspaceController = BackspaceController(
        onBackspaceKey = { onKeyClick(KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)) },
        onAcceleratedDeletionChanged = onAcceleratedDeletionChanged,
        vibrateEffect = ::vibrateEffect,
        cancelVibration = { vibrator?.cancel() },
        getHapticEnabled = { hapticEnabled },
        getHapticAmplitude = { hapticAmplitude },
        getSupportsAmplitudeControl = { supportsAmplitudeControl },
        getBackgroundScope = { backgroundScope }
    )

    private val touchDispatcher = KeyTouchDispatcher(
        onKeyClick = onKeyClick,
        performHaptic = ::performContextualHaptic,
        getLongPressDuration = { currentLongPressDuration },
        getLongPressPunctuationMode = { longPressPunctuationMode },
        getActiveLanguages = { activeLanguages },
        getShowLanguageSwitchKey = { showLanguageSwitchKey },
        getPopupSelectionMode = { popupSelectionMode },
        setPopupSelectionMode = { popupSelectionMode = it },
        getVariationPopup = { variationPopup },
        setVariationPopup = { variationPopup = it },
        getActivePunctuationPopup = { activePunctuationPopup },
        setActivePunctuationPopup = { activePunctuationPopup = it },
        getLongPressConsumedButtons = { longPressConsumedButtons },
        getHapticDownFiredButtons = { hapticDownFiredButtons },
        getCharacterLongPressFired = { characterLongPressFired },
        getSymbolsLongPressFired = { symbolsLongPressFired },
        getCustomMappingLongPressFired = { customMappingLongPressFired },
        getButtonPendingCallbacks = { buttonPendingCallbacks },
        getButtonLongPressRunnables = { buttonLongPressRunnables },
        getPressStartTimes = { pressStartTimes },
        backspaceController = backspaceController,
        setSwipePopupActive = { active -> swipeKeyboardView?.setPopupActive(active) },
        getCurrentVariationKeyType = { currentVariationKeyType },
        accessibilityManager = accessibilityManager
    )

    private var variationPopup: CharacterVariationPopup? = null
    private var currentVariationKeyType: KeyboardKey.KeyType? = null
    private var languagePickerPopup: LanguagePickerPopup? = null
    private var lastKeyboardState: KeyboardState = KeyboardState()

    private val characterVariationCallback: (String) -> Unit = { selectedChar ->
        popupSelectionMode = false
        swipeKeyboardView?.setPopupActive(false)

        val keyType = currentVariationKeyType ?: KeyboardKey.KeyType.LETTER
        val selectedKey = KeyboardKey.Character(selectedChar, keyType)
        performContextualHaptic(selectedKey)
        onKeyClick(selectedKey)
    }

    private val punctuationVariationCallback: (String) -> Unit = { selectedPunctuation ->
        activePunctuationPopup = null
        popupSelectionMode = false
        swipeKeyboardView?.setPopupActive(false)

        val punctuationKey = KeyboardKey.Character(selectedPunctuation, KeyboardKey.KeyType.PUNCTUATION)
        performContextualHaptic(punctuationKey)
        onKeyClick(punctuationKey)
    }

    @VisibleForTesting internal val keyClickListener get() = touchDispatcher.keyClickListener

    @VisibleForTesting
    internal val characterLongPressTouchListener get() = touchDispatcher.characterLongPressTouchListener

    @VisibleForTesting
    internal val punctuationLongPressTouchListener get() = touchDispatcher.punctuationLongPressTouchListener

    @VisibleForTesting internal val backspaceLongClickListener get() = touchDispatcher.backspaceLongClickListener

    @VisibleForTesting internal val backspaceTouchListener get() = touchDispatcher.backspaceTouchListener

    @VisibleForTesting internal val commaLongPressTouchListener get() = touchDispatcher.commaLongPressTouchListener

    @VisibleForTesting
    internal val punctuationTapHapticTouchListener get() = touchDispatcher.punctuationTapHapticTouchListener

    private val punctuationLoader = PunctuationLoader(context, cacheMemoryManager)

    fun updateAdaptiveDimensions(dimensions: AdaptiveDimensions) {
        adaptiveDimensions = dimensions
        // splitGapPx is driven solely by the keyboard mode (updateSplitGapPx), not the foldable hinge —
        // otherwise the hinge gap leaked into standard mode and split the keyboard regardless of settings.
        invalidateCalculationCache()
    }

    fun updateScriptContext() {
        invalidateCalculationCache()
    }

    fun triggerHapticFeedback() {
        performContextualHaptic(null)
    }

    fun triggerBackspaceHaptic() {
        if (!hapticEnabled || hapticAmplitude == 0) return
        val amplitude = if (supportsAmplitudeControl) hapticAmplitude else android.os.VibrationEffect.DEFAULT_AMPLITUDE
        val effect = HapticSignature.BackspaceChirp.createEffect(amplitude)
        vibrateEffect(effect)
    }

    fun stopAcceleratedBackspace() {
        backspaceController.stop()
    }

    fun cancelAllPendingCallbacks() {
        buttonPendingCallbacks.values.forEach { pending ->
            pending.handler.removeCallbacks(pending.runnable)
        }
        buttonPendingCallbacks.clear()
    }

    fun dismissVariationPopup() {
        variationPopup?.dismiss()
        variationPopup = null
    }

    fun updateLongPressDuration(duration: LongPressDuration) {
        currentLongPressDuration = duration
    }

    fun updateLongPressPunctuationMode(mode: LongPressPunctuationMode) {
        longPressPunctuationMode = mode
    }

    fun setSwipeKeyboardView(view: SwipeKeyboardView) {
        swipeKeyboardView = view
    }

    fun updateKeySize(keySize: KeySize) {
        if (currentKeySize != keySize) {
            currentKeySize = keySize
            invalidateCalculationCache()
        }
    }

    fun updateSpaceBarSize(spaceBarSize: SpaceBarSize) {
        currentSpaceBarSize = spaceBarSize
    }

    fun updateSplitGapPx(gapPx: Int) {
        splitGapPx = gapPx
    }

    fun updateKeyLabelSize(keyLabelSize: KeyLabelSize) {
        if (currentKeyLabelSize != keyLabelSize) {
            currentKeyLabelSize = keyLabelSize
            invalidateCalculationCache()
        }
    }

    fun onDensityChanged() {
        invalidateCalculationCache()
    }

    fun updateHapticSettings(enabled: Boolean, amplitude: Int) {
        hapticEnabled = enabled
        hapticAmplitude = amplitude
    }

    fun updateClipboardEnabled(enabled: Boolean) {
        clipboardEnabled = enabled
    }

    fun updateHasMultipleImes(hasMultiple: Boolean) {
        hasMultipleImes = hasMultiple
    }

    fun updateActiveLanguages(languages: List<String>) {
        activeLanguages = languages
    }

    fun updateShowLanguageSwitchKey(enabled: Boolean) {
        showLanguageSwitchKey = enabled
    }

    fun updateNumberHints(enabled: Boolean) {
        showNumberHints = enabled
    }

    fun updatePressHighlight(enabled: Boolean) {
        pressHighlightEnabled = enabled
    }

    fun updateCustomKeyMappings(mappings: Map<String, List<String>>) {
        customKeyMappings = mappings
    }

    private fun vibrateEffect(effect: android.os.VibrationEffect) {
        val v = vibrator ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                vibrationAttributes != null
            ) {
                v.vibrate(effect, vibrationAttributes)
            } else {
                v.vibrate(effect)
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "KeyboardLayoutManager",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "vibrateEffect")
            )
        }
    }

    private fun performContextualHaptic(key: KeyboardKey?) {
        onHapticFired?.invoke(key)
        if (!hapticEnabled || hapticAmplitude == 0) return

        try {
            val signature =
                when (key) {
                    is KeyboardKey.Character -> {
                        when (key.type) {
                            KeyboardKey.KeyType.LETTER -> HapticSignature.LetterClick
                            KeyboardKey.KeyType.PUNCTUATION -> HapticSignature.PunctuationTick
                            KeyboardKey.KeyType.NUMBER -> HapticSignature.NumberClick
                            KeyboardKey.KeyType.SYMBOL -> HapticSignature.PunctuationTick
                        }
                    }

                    is KeyboardKey.Action -> {
                        when (key.action) {
                            KeyboardKey.ActionType.SPACE -> HapticSignature.SpaceThump
                            KeyboardKey.ActionType.BACKSPACE -> HapticSignature.BackspaceChirp
                            KeyboardKey.ActionType.SHIFT -> HapticSignature.ShiftPulse
                            KeyboardKey.ActionType.ENTER -> HapticSignature.EnterCompletion
                            else -> HapticSignature.LetterClick
                        }
                    }

                    is KeyboardKey.FlickKey -> HapticSignature.LetterClick

                    KeyboardKey.Spacer -> {
                        return
                    }

                    null -> {
                        HapticSignature.LetterClick
                    }
                }

            val amplitude = if (supportsAmplitudeControl) {
                hapticAmplitude
            } else {
                android.os.VibrationEffect.DEFAULT_AMPLITUDE
            }
            val effect = signature.createEffect(amplitude)
            vibrateEffect(effect)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "KeyboardLayoutManager",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "performContextualHaptic")
            )
        }
    }

    private fun invalidateCalculationCache() {
        cachedTextSizes.clear()
        cachedDimensions.clear()
        cacheValid = false
    }

    private fun ensureCacheValid() {
        if (cacheValid) return

        val dims = adaptiveDimensions
        if (dims != null) {
            cachedDimensions["minTarget"] = dims.minimumTouchTargetPx
            cachedDimensions["keyHeight"] = dims.keyHeightPx
            cachedDimensions["horizontalPadding"] = dims.keyMarginHorizontalPx
            cachedDimensions["verticalPadding"] =
                (dims.keyMarginHorizontalPx * 0.5f * currentKeySize.scaleFactor).toInt()
            cachedDimensions["horizontalMargin"] = dims.keyMarginHorizontalPx
            cachedDimensions["rowVerticalMargin"] = dims.keyMarginVerticalPx
            cachedDimensions["numberRowGutter"] = dims.numberRowGutterPx
        } else {
            val basePadding = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
            val baseMinTouchTarget = context.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
            val baseKeyHeight = context.resources.getDimensionPixelSize(R.dimen.key_height)
            val baseHorizontalMargin = context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)

            val keySizeMultiplier = currentKeySize.scaleFactor

            cachedDimensions["minTarget"] = (baseMinTouchTarget * keySizeMultiplier).toInt()
            cachedDimensions["keyHeight"] = (baseKeyHeight * keySizeMultiplier).toInt()
            cachedDimensions["horizontalPadding"] = basePadding
            cachedDimensions["verticalPadding"] = (basePadding * 0.5f * keySizeMultiplier).toInt()
            cachedDimensions["horizontalMargin"] = baseHorizontalMargin
            cachedDimensions["rowVerticalMargin"] =
                context.resources.getDimensionPixelSize(R.dimen.key_margin_vertical)
            cachedDimensions["numberRowGutter"] = context.resources.getDimensionPixelSize(R.dimen.number_row_gutter)
        }

        val density = context.resources.displayMetrics.density
        cachedCornerRadius = dims?.cornerRadiusPx?.toFloat() ?: (8f * density)
        cachedStrokeWidth = dims?.keyBorderWidthPx ?: (1 * density).toInt()
        cachedStrokeWidthThick = dims?.keyBorderWidthPx?.let { it * 2 } ?: (2 * density).toInt()
        cacheValid = true
    }

    /**
     * Returns a cached dimension, re-running [ensureCacheValid] if the key is absent, then
     * falling back to the equivalent resource default so consumers never receive null.
     */
    private fun requireDim(key: String): Int {
        val cached = cachedDimensions[key]
        if (cached != null) return cached
        ensureCacheValid()
        cachedDimensions[key]?.let { return it }
        return when (key) {
            "minTarget" -> context.resources.getDimensionPixelSize(R.dimen.minimum_touch_target)
            "keyHeight" -> context.resources.getDimensionPixelSize(R.dimen.key_height)
            "horizontalPadding" -> context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
            "verticalPadding" -> (context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal) * 0.5f).toInt()
            "horizontalMargin" -> context.resources.getDimensionPixelSize(R.dimen.key_margin_horizontal)
            "rowVerticalMargin" -> context.resources.getDimensionPixelSize(R.dimen.key_margin_vertical)
            "numberRowGutter" -> context.resources.getDimensionPixelSize(R.dimen.number_row_gutter)
            else -> 0
        }
    }

    private fun getCachedTextSize(keyHeight: Int): Float = cachedTextSizes.getOrPut(keyHeight) {
        val dims = adaptiveDimensions
        val ratio = dims?.keyTextBaseRatio ?: 0.38f
        val minSize = dims?.keyTextMinSp ?: 12f
        val maxSize = dims?.keyTextMaxSp ?: when (currentKeySize) {
            KeySize.EXTRA_LARGE -> 16f
            else -> 24f
        }
        val baseTextSize = keyHeight * ratio / context.resources.displayMetrics.density
        val adjusted = baseTextSize.coerceIn(minSize, maxSize)
        adjusted * currentKeyLabelSize.scaleFactor * (dims?.keyFontScale ?: 1f)
    }

    fun createKeyboardView(layout: KeyboardLayout, state: KeyboardState): View {
        lastKeyboardState = state
        returnActiveButtonsToPool()

        val processedRows =
            layout.rows.map { row ->
                if (shouldInjectGlobeButton(row)) {
                    injectGlobeButton(row)
                } else {
                    row
                }
            }

        effectiveLayout =
            KeyboardLayout(
                mode = layout.mode,
                rows = processedRows,
                isRTL = layout.isRTL,
                script = layout.script,
                showFlickHints = layout.showFlickHints
            )

        val keyboardContainer =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )

                val dims = adaptiveDimensions
                val horizontalPadding = dims?.keyboardPaddingHorizontalPx
                    ?: context.resources.getDimensionPixelSize(R.dimen.keyboard_padding)
                val verticalPadding = dims?.keyboardPaddingVerticalPx
                    ?: context.resources.getDimensionPixelSize(R.dimen.keyboard_padding_vertical)

                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                setBackgroundColor(themeManager.currentTheme.value.colors.keyboardBackground)

                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = context.getString(R.string.keyboard_description)
            }

        processedRows.forEachIndexed { index, row ->
            val hasNumberRowGutter = index == 0 && isTopNumberRow(row) && processedRows.size > 1
            val rowView = createRowView(row, state, hasNumberRowGutter)
            keyboardContainer.addView(rowView)
        }

        return keyboardContainer
    }

    private fun shouldInjectGlobeButton(row: List<KeyboardKey>): Boolean = showLanguageSwitchKey &&
        activeLanguages.size > 1 &&
        row.any { it is KeyboardKey.Action && it.action == KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS }

    private fun injectGlobeButton(row: List<KeyboardKey>): List<KeyboardKey> = row.flatMap { key ->
        if (key is KeyboardKey.Action && key.action == KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS) {
            listOf(key, KeyboardKey.Action(KeyboardKey.ActionType.LANGUAGE_SWITCH))
        } else {
            listOf(key)
        }
    }

    private fun createRowView(
        keys: List<KeyboardKey>,
        state: KeyboardState,
        hasNumberRowGutter: Boolean = false
    ): LinearLayout {
        val is9LetterRow = is9CharacterLetterRow(keys)
        val shouldSplit = splitGapPx > 0 && !containsSpacebar(keys)

        val rowLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            ensureCacheValid()
                            val verticalMargin = requireDim("rowVerticalMargin")
                            val gutterMargin =
                                if (hasNumberRowGutter) {
                                    requireDim("numberRowGutter")
                                } else {
                                    0
                                }
                            setMargins(0, 0, 0, verticalMargin + gutterMargin)
                        }

                isBaselineAligned = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

        if (shouldSplit) {
            val midpoint = keys.size / 2
            val shouldDuplicateMiddle = keys.size % 2 == 1

            val leftKeys =
                if (shouldDuplicateMiddle) {
                    keys.subList(0, midpoint + 1)
                } else {
                    keys.subList(0, midpoint)
                }
            val rightKeys = keys.subList(midpoint, keys.size)

            val leftContainer = createHalfRowContainer(leftKeys, state)
            val gapSpacer =
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(splitGapPx, LinearLayout.LayoutParams.MATCH_PARENT)
                }
            val rightContainer = createHalfRowContainer(rightKeys, state)

            rowLayout.addView(leftContainer)
            rowLayout.addView(gapSpacer)
            rowLayout.addView(rightContainer)
        } else {
            if (is9LetterRow && splitGapPx == 0) {
                val spacer =
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 0.5f)
                    }
                rowLayout.addView(spacer)
            }

            keys.forEach { key ->
                if (key is KeyboardKey.Spacer) {
                    val spacer =
                        View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 0, STANDARD_KEY_WEIGHT)
                        }
                    rowLayout.addView(spacer)
                } else {
                    val keyButton = getOrCreateKeyButton(key, state, keys)
                    rowLayout.addView(keyButton)
                }
            }

            if (is9LetterRow && splitGapPx == 0) {
                val spacer =
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 0.5f)
                    }
                rowLayout.addView(spacer)
            }
        }

        return rowLayout
    }

    private fun createHalfRowContainer(keys: List<KeyboardKey>, state: KeyboardState): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isBaselineAligned = false

            keys.forEach { key ->
                if (key is KeyboardKey.Spacer) {
                    val spacer =
                        View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 0, STANDARD_KEY_WEIGHT)
                        }
                    addView(spacer)
                } else {
                    val keyButton = getOrCreateKeyButton(key, state, keys)
                    addView(keyButton)
                }
            }
        }

    private fun containsSpacebar(keys: List<KeyboardKey>): Boolean =
        keys.any { it is KeyboardKey.Action && it.action == KeyboardKey.ActionType.SPACE }

    private fun is9CharacterLetterRow(rowKeys: List<KeyboardKey>): Boolean {
        val nonSpacerKeys = rowKeys.filter { it !is KeyboardKey.Spacer }
        if (nonSpacerKeys.size != 9) return false

        return nonSpacerKeys.all { key ->
            key is KeyboardKey.Character && key.type == KeyboardKey.KeyType.LETTER
        }
    }

    private fun getOrCreateKeyButton(key: KeyboardKey, state: KeyboardState, rowKeys: List<KeyboardKey>): Button {
        val button =
            if (buttonPool.isNotEmpty()) {
                buttonPool.removeAt(buttonPool.size - 1).apply {
                    isPressed = false
                }
            } else {
                Button(context)
            }

        configureButton(button, key, state, rowKeys)
        activeButtons.add(button)

        return button
    }

    private fun preallocateLongPressRunnable(button: Button, key: KeyboardKey) {
        when (key) {
            is KeyboardKey.Character if
            key.type == KeyboardKey.KeyType.LETTER ||
                key.type == KeyboardKey.KeyType.NUMBER ||
                key.type == KeyboardKey.KeyType.SYMBOL
            -> buttonLongPressRunnables[button] = Runnable {
                characterLongPressFired.add(button)
                longPressConsumedButtons.add(button)
                performContextualHaptic(key)
                handleCharacterLongPress(key, button, button)
            }

            is KeyboardKey.Character if
            key.type == KeyboardKey.KeyType.PUNCTUATION &&
                longPressPunctuationMode == LongPressPunctuationMode.PERIOD &&
                key.value == "." ->
                buttonLongPressRunnables[button] = Runnable {
                    longPressConsumedButtons.add(button)
                    performContextualHaptic(key)
                    handlePunctuationLongPress(key, button)
                }

            is KeyboardKey.Character if key.value == "," ->
                buttonLongPressRunnables[button] = Runnable {
                    touchDispatcher.commaLongPressFired = true
                    longPressConsumedButtons.add(button)
                    button.isPressed = false
                    performContextualHaptic(KeyboardKey.Character(",", KeyboardKey.KeyType.PUNCTUATION))
                    onShowInputMethodPicker()
                }

            is KeyboardKey.Action if
            key.action == KeyboardKey.ActionType.SPACE &&
                longPressPunctuationMode == LongPressPunctuationMode.SPACEBAR ->
                buttonLongPressRunnables[button] = Runnable {
                    longPressConsumedButtons.add(button)
                    performContextualHaptic(KeyboardKey.Action(KeyboardKey.ActionType.SPACE))
                    handleSpaceLongPress(button)
                }

            is KeyboardKey.Action if
            key.action == KeyboardKey.ActionType.SHIFT &&
                !showLanguageSwitchKey &&
                activeLanguages.size > 1 ->
                buttonLongPressRunnables[button] = Runnable {
                    touchDispatcher.shiftLongPressFired = true
                    longPressConsumedButtons.add(button)
                    performContextualHaptic(KeyboardKey.Action(KeyboardKey.ActionType.SHIFT))
                    handleShiftLongPress(button)
                }

            is KeyboardKey.Action if key.action == KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS ->
                buttonLongPressRunnables[button] = Runnable {
                    symbolsLongPressFired.add(button)
                    longPressConsumedButtons.add(button)
                    button.isPressed = false
                    performContextualHaptic(null)
                    onSymbolsLongPress()
                }

            else -> {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Suppress("CyclomaticComplexMethod")
    private fun configureButton(button: Button, key: KeyboardKey, state: KeyboardState, rowKeys: List<KeyboardKey>) {
        ensureCacheValid()

        button.apply {
            setOnClickListener(null)
            setOnLongClickListener(null)
            setOnTouchListener(null)

            val minTarget = requireDim("minTarget")
            val keyHeight = requireDim("keyHeight")
            val gutterReduction = if (isTopNumberRow(rowKeys)) requireDim("numberRowGutter") else 0
            val adjustedKeyHeight = (keyHeight - gutterReduction).coerceAtLeast(keyHeight / 2)
            val adjustedMinTarget = (minTarget - gutterReduction).coerceAtLeast(minTarget / 2)
            val visualHeight = adjustedKeyHeight + 2
            val verticalMargin = ((adjustedMinTarget - visualHeight) / 2).coerceAtLeast(0)

            layoutParams =
                LinearLayout
                    .LayoutParams(
                        0,
                        visualHeight,
                        getKeyWeight(key, rowKeys)
                    ).apply {
                        val horizontalMargin = requireDim("horizontalMargin")
                        setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin)
                    }

            text = getKeyLabel(key, state)

            val finalTextSize = getCachedTextSize(adjustedKeyHeight)

            TextViewCompat.setAutoSizeTextTypeWithDefaults(this, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)

            setTextAppearance(
                when (key) {
                    is KeyboardKey.Action -> R.style.KeyTextAppearance_Action
                    else -> R.style.KeyTextAppearance
                }
            )

            setTextSize(TypedValue.COMPLEX_UNIT_SP, finalTextSize)
            maxLines = 1
            gravity = Gravity.CENTER

            typeface = run {
                val family = adaptiveDimensions?.fontFamily ?: ""
                val weight = adaptiveDimensions?.labelWeight
                if (weight != null && weight > 0) {
                    com.urik.keyboard.service.KeyboardFonts.weightedTypeface(context, family, weight)
                } else {
                    com.urik.keyboard.service.KeyboardFonts.typeface(
                        context,
                        family,
                        adaptiveDimensions?.boldKeyLabels == true
                    )
                }
            }
            // Buttons default to textAllCaps=true, which forces uppercase labels regardless of the cased
            // string from getKeyLabel — so letters showed upper even unshifted. Honour the actual casing
            // (lower at rest, upper only when shift/caps-lock is engaged).
            isAllCaps = false

            minHeight = 0
            minimumHeight = 0

            val horizontalPadding = requireDim("horizontalPadding")
            val verticalPadding = requireDim("verticalPadding")
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

            if (key is KeyboardKey.Action &&
                key.action in
                setOf(
                    KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS,
                    KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY,
                    KeyboardKey.ActionType.MODE_SWITCH_LETTERS,
                    KeyboardKey.ActionType.MODE_SWITCH_NUMBERS,
                    KeyboardKey.ActionType.SPACE
                )
            ) {
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    8,
                    finalTextSize.toInt(),
                    1,
                    TypedValue.COMPLEX_UNIT_SP
                )
            }

            val keyBackground = getKeyBackground(key)
            val supportsCustomMapping =
                key is KeyboardKey.Character &&
                    (key.type == KeyboardKey.KeyType.LETTER || key.type == KeyboardKey.KeyType.NUMBER)

            val isCommaWithMultipleImes =
                key is KeyboardKey.Character &&
                    key.value == "," &&
                    hasMultipleImes &&
                    rowKeys.any { it is KeyboardKey.Action && it.action == KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS }

            background =
                if (isCommaWithMultipleImes) {
                    val keyboardIcon = ContextCompat.getDrawable(context, R.drawable.ic_keyboard)
                    keyboardIcon?.setTint(getKeyTextColor(key))
                    addBadgeOverlay(keyBackground, keyboardIcon)
                } else if (supportsCustomMapping && !customKeyMappings[key.value.lowercase()].isNullOrEmpty()) {
                    val customSymbol = customKeyMappings[key.value.lowercase()]!!.first()
                    keyHintRenderer.createKeyWithHint(
                        keyBackground,
                        customSymbol,
                        themeManager.currentTheme.value.colors
                    )
                } else if (isNumberHintRow(rowKeys)) {
                    val keyIndex = rowKeys.indexOf(key)
                    keyHintRenderer.createKeyWithHint(
                        keyBackground,
                        ((keyIndex + 1) % 10).toString(),
                        themeManager.currentTheme.value.colors
                    )
                } else if (key is KeyboardKey.FlickKey && key.clusterMains.isNotEmpty() &&
                    effectiveLayout?.showFlickHints == true
                ) {
                    createClusterBackground(keyBackground, flickFace(key, state), finalTextSize)
                } else if (key is KeyboardKey.FlickKey && effectiveLayout?.showFlickHints == true) {
                    createFlickHintsBackground(keyBackground, flickFace(key, state))
                } else {
                    keyBackground
                }
            setTextColor(getKeyTextColor(key))

            isActivated = getKeyActivatedState(key, state)
            isClickable = true
            isFocusable = true

            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = getKeyContentDescription(key, state)

            ViewCompat.setAccessibilityDelegate(
                this,
                object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        when (key) {
                            is KeyboardKey.Character -> {
                                info.roleDescription = context.getString(R.string.key_role)
                            }

                            is KeyboardKey.Action -> {
                                info.roleDescription = context.getString(R.string.action_key_role)
                                when (key.action) {
                                    KeyboardKey.ActionType.SHIFT -> {
                                        info.stateDescription = when {
                                            state.isCapsLockOn -> context.getString(R.string.state_on)
                                            state.isShiftPressed -> context.getString(R.string.state_active)
                                            else -> context.getString(R.string.state_inactive)
                                        }
                                    }

                                    KeyboardKey.ActionType.BACKSPACE -> {
                                        info.addAction(
                                            AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                                R.id.action_delete_word,
                                                context.getString(R.string.backspace_delete_word_action)
                                            )
                                        )
                                    }

                                    else -> {}
                                }
                            }

                            is KeyboardKey.FlickKey -> {
                                info.roleDescription = context.getString(R.string.key_role)
                            }

                            KeyboardKey.Spacer -> {}
                        }
                    }

                    override fun performAccessibilityAction(
                        host: View,
                        action: Int,
                        args: android.os.Bundle?
                    ): Boolean {
                        if (action == R.id.action_delete_word) {
                            onDeleteWord?.invoke()
                            return true
                        }
                        return super.performAccessibilityAction(host, action, args)
                    }
                }
            )

            setTag(R.id.key_data, key)
            isHapticFeedbackEnabled = false

            if (key is KeyboardKey.Action) {
                val iconRes =
                    when (key.action) {
                        KeyboardKey.ActionType.SHIFT -> if (state.isCapsLockOn) {
                            R.drawable.shift_lock_48px
                        } else {
                            R.drawable.shift_48px
                        }

                        // SPACE shows the layout language's native name (set in getKeyLabel), not an icon.

                        KeyboardKey.ActionType.BACKSPACE -> R.drawable.backspace_48px

                        KeyboardKey.ActionType.EMOJI -> R.drawable.ic_emoji

                        KeyboardKey.ActionType.ENTER -> R.drawable.keyboard_return_48px

                        KeyboardKey.ActionType.SEARCH -> R.drawable.search_48px

                        KeyboardKey.ActionType.SEND -> R.drawable.send_48px

                        KeyboardKey.ActionType.DONE -> R.drawable.done_48px

                        KeyboardKey.ActionType.GO -> R.drawable.arrow_forward_48px

                        KeyboardKey.ActionType.NEXT -> R.drawable.arrow_forward_48px

                        KeyboardKey.ActionType.PREVIOUS -> R.drawable.arrow_back_48px

                        else -> 0
                    }

                if (iconRes != 0) {
                    val keyBackground = getKeyBackground(key)
                    val iconDrawable = ContextCompat.getDrawable(context, iconRes)

                    val iconTint =
                        if (key.action == KeyboardKey.ActionType.SHIFT && state.isCapsLockOn) {
                            adaptiveDimensions?.capsLockShiftColor ?: getKeyTextColor(key)
                        } else {
                            getKeyTextColor(key)
                        }
                    iconDrawable?.setTint(iconTint)

                    val baseLayer = LayerDrawable(arrayOf(keyBackground, iconDrawable)).apply {
                        setLayerInset(1, 12, 12, 12, 12)
                        setLayerGravity(1, Gravity.CENTER)
                    }

                    background = if (key.action == KeyboardKey.ActionType.SHIFT &&
                        activeLanguages.size >= 2 &&
                        !showLanguageSwitchKey
                    ) {
                        val shortcode =
                            languageManager.currentLayoutLanguage.value
                                .take(2)
                                .uppercase(java.util.Locale.ROOT)
                        val langBadge = createLangBadgeDrawable(shortcode, getKeyTextColor(key))
                        addBadgeOverlay(baseLayer, langBadge)
                    } else {
                        baseLayer
                    }
                    text = ""
                } else if (key.action == KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS &&
                    clipboardEnabled &&
                    effectiveLayout?.mode == KeyboardMode.LETTERS
                ) {
                    val keyBackground = getKeyBackground(key)
                    val clipboardIcon = ContextCompat.getDrawable(context, R.drawable.ic_clipboard)
                    clipboardIcon?.setTint(getKeyTextColor(key))
                    background = addBadgeOverlay(keyBackground, clipboardIcon)
                } else {
                    background = getKeyBackground(key)
                }
            }

            preallocateLongPressRunnable(button, key)
            touchDispatcher.attachListeners(button, key)

            if (key is KeyboardKey.Action && key.action == KeyboardKey.ActionType.LANGUAGE_SWITCH) {
                setOnClickListener { cycleToNextLanguage() }
                setOnLongClickListener {
                    performContextualHaptic(key)
                    if (activeLanguages.size > 1) {
                        showLanguagePickerPopup(this, activeLanguages)
                    }
                    true
                }
            }


            if (key is KeyboardKey.FlickKey) {
                setOnClickListener(null)
                setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        // Flick keys own their whole gesture. Stop the parent keyboard view from
                        // intercepting and cancelling the flick mid-swipe — without this, certain
                        // keys (e.g. さ) had their flick cancelled on longer swipes, dropping input.
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        val popup = FlickPopup(context, themeManager)
                        flickPopup?.dismiss()
                        flickPopup = popup
                        popup.show(key, view)
                    }
                    flickGestureDetector.handleTouchEvent(event) { _, _ ->
                        view.getTag(R.id.key_data) as? KeyboardKey
                    }
                }
            }
        }
    }

    @VisibleForTesting
    @SuppressLint("ClickableViewAccessibility")
    internal fun cleanupButton(button: Button) {
        buttonLongPressRunnables.remove(button)
        buttonPendingCallbacks.remove(button)?.let { pending ->
            pending.handler.removeCallbacks(pending.runnable)
        }
        symbolsLongPressFired.remove(button)
        customMappingLongPressFired.remove(button)
        characterLongPressFired.remove(button)
        hapticDownFiredButtons.remove(button)
        pressStartTimes.remove(button)

        button.isHapticFeedbackEnabled = true
        button.isPressed = false
        button.setOnClickListener(null)
        button.setOnLongClickListener(null)
        button.setOnTouchListener(null)

        (button.parent as? ViewGroup)?.removeView(button)
    }

    private fun returnActiveButtonsToPool() {
        variationPopup?.dismiss()
        languagePickerPopup?.dismiss()

        activeButtons.forEach { button ->
            cleanupButton(button)

            if (buttonPool.size < MAX_BUTTON_POOL_SIZE) {
                buttonPool.add(button)
            }
        }
        activeButtons.clear()
    }

    private fun isBicameralScript(script: String): Boolean = when (script) {
        "Latn", "Cyrl", "Grek" -> true
        else -> false
    }

    @VisibleForTesting
    internal fun isNumberHintRow(row: List<KeyboardKey>): Boolean {
        if (!isNumberHintEnabled()) return false
        return effectiveLayout?.rows?.get(0)
            ?.let { firstRow ->
                !isTopNumberRow(firstRow) && row == firstRow
            } ?: false
    }

    @VisibleForTesting
    internal fun isNumberHintEnabled(): Boolean {
        if (!showNumberHints) return false
        return effectiveLayout?.rows?.get(0)
            ?.let { row ->
                row.count { key ->
                    key is KeyboardKey.Character && key.type == KeyboardKey.KeyType.LETTER
                } == 10
            }
            ?: false
    }

    private fun getCurrentLocale(): java.util.Locale {
        val lang =
            languageManager.currentLayoutLanguage.value
                .split("-")
                .first()
        return java.util.Locale.forLanguageTag(lang)
    }

    /**
     * The current layout language's name in its OWN language, for the space bar — e.g. ja → "日本語",
     * ru → "Русский", en → "English". "gnu" is a pseudo-language (code mode) → "GNU".
     */
    private fun layoutLanguageDisplayName(): String =
        nativeLanguageName(languageManager.currentLayoutLanguage.value)

    /** A language code's name in its own language (ja -> "日本語", ru -> "Русский", gnu -> "GNU"). */
    private fun nativeLanguageName(code: String): String {
        if (code == "gnu") return "GNU"
        return try {
            val loc = android.icu.util.ULocale.forLanguageTag(code)
            val name = loc.getDisplayName(loc)
            if (name.isNullOrEmpty() || name.length <= 2) {
                code.uppercase(java.util.Locale.ROOT)
            } else {
                name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
            }
        } catch (_: Exception) {
            code.uppercase(java.util.Locale.ROOT)
        }
    }

    /**
     * The space-slide menu model: a Languages column (the OTHER active languages → switch language) and a
     * Layouts column (the current language's registry layouts → switch the variant). Built fresh on open.
     */
    fun buildSpaceMenu(): List<SpaceMenuColumn> {
        val currentLang = languageManager.currentLayoutLanguage.value
        val actions = SpaceMenuColumn(
            header = context.getString(R.string.space_menu_actions),
            items = listOf(
                SpaceMenuItem(context.getString(R.string.space_menu_kxkb_ui), false) { onMenuAction("kxkb_ui") },
                SpaceMenuItem(context.getString(R.string.space_menu_languages_action), false) { onMenuAction("languages") },
                SpaceMenuItem(context.getString(R.string.space_menu_all_settings), false) { onMenuAction("settings") }
            )
        )
        // Middle column: the system IME chooser at the top, then the other active languages.
        val languages = SpaceMenuColumn(
            header = context.getString(R.string.space_menu_languages),
            items = listOf(
                SpaceMenuItem(context.getString(R.string.space_menu_keyboard), false) { onMenuAction("ime_picker") }
            ) + activeLanguages.filter { it != currentLang }.map { lang ->
                SpaceMenuItem(nativeLanguageName(lang), current = false) { onLanguageSwitch(lang) }
            }
        )
        val registry = com.urik.keyboard.data.LayoutRegistry.load(context)
        val layouts = SpaceMenuColumn(
            header = context.getString(R.string.space_menu_layouts),
            items = registry.forLanguage(currentLang).map { entry ->
                SpaceMenuItem(entry.name, current = false) { onSwitchToLayout(currentLang, entry.id) }
            }
        )
        return listOf(actions, languages, layouts).filter { it.items.isNotEmpty() }
    }

    /**
     * The face a flick/cluster key shows and commits given the shift state: the explicit [FlickKey.shifted]
     * variant if present (e.g. katakana), else an uppercased copy for bicameral scripts, else the key itself.
     */
    private fun flickFace(key: KeyboardKey.FlickKey, state: KeyboardState): KeyboardKey.FlickKey {
        if (!shouldCapitalize(state)) return key
        key.shifted?.let { return it }
        if (!isBicameralScript(effectiveLayout?.script ?: "Latn")) return key
        val loc = getCurrentLocale()
        fun up(s: String?) = s?.uppercase(loc)
        return key.copy(
            center = key.center.uppercase(loc),
            up = up(key.up), right = up(key.right), down = up(key.down), left = up(key.left),
            upLeft = up(key.upLeft), upRight = up(key.upRight), downLeft = up(key.downLeft), downRight = up(key.downRight),
            clusterMains = key.clusterMains.uppercase(loc)
        )
    }

    /** Cycle to the next active layout language (globe-key tap + interim space long-press). */
    fun cycleToNextLanguage() {
        val nextLang = languageManager.getNextLayoutLanguage()
        onLanguageSwitch(nextLang)
        val displayName =
            com.urik.keyboard.settings.KeyboardSettings.getLanguageDisplayNames()[nextLang] ?: nextLang
        android.widget.Toast.makeText(context, displayName, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun getKeyLabel(key: KeyboardKey, state: KeyboardState): String = when (key) {
        is KeyboardKey.Character -> {
            val script = effectiveLayout?.script ?: "Latn"
            when {
                key.type == KeyboardKey.KeyType.LETTER &&
                    isBicameralScript(script) &&
                    shouldCapitalize(state) -> {
                    if (key.value == "ß") {
                        "ẞ"
                    } else {
                        key.value.uppercase(getCurrentLocale())
                    }
                }

                else -> {
                    key.value
                }
            }
        }

        is KeyboardKey.Action -> {
            when (key.action) {
                KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> {
                    context.getString(R.string.letters_mode_label)
                }

                KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> {
                    context.getString(R.string.numbers_mode_label)
                }

                KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> {
                    context.getString(R.string.symbols_mode_label)
                }

                KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY -> {
                    context.getString(R.string.symbols_secondary_mode_label)
                }

                KeyboardKey.ActionType.LANGUAGE_SWITCH -> {
                    languageManager.currentLayoutLanguage.value
                        .take(
                            2
                        ).uppercase(java.util.Locale.ROOT)
                }

                KeyboardKey.ActionType.DAKUTEN -> "゛"

                KeyboardKey.ActionType.SMALL_KANA -> "小"

                KeyboardKey.ActionType.NEXT_CANDIDATE -> "次候補"

                KeyboardKey.ActionType.COMMIT_CANDIDATE -> "確定"

                KeyboardKey.ActionType.HANDAKUTEN -> "゜"

                KeyboardKey.ActionType.EMOJI -> ""

                KeyboardKey.ActionType.TAB -> "⇥"

                KeyboardKey.ActionType.SPACE -> layoutLanguageDisplayName()

                else -> {
                    "?"
                }
            }
        }

        // Cluster keys draw their whole band themselves (ClusterMainsDrawable), so the single label is empty.
        is KeyboardKey.FlickKey -> flickFace(key, state).let { if (it.clusterMains.isNotEmpty()) "" else it.center }

        KeyboardKey.Spacer -> {
            ""
        }
    }

    private fun getKeyContentDescription(key: KeyboardKey, state: KeyboardState): String = when (key) {
        is KeyboardKey.Character -> {
            val script = effectiveLayout?.script ?: "Latn"
            val char =
                when {
                    key.type == KeyboardKey.KeyType.LETTER &&
                        isBicameralScript(script) &&
                        shouldCapitalize(state) -> {
                        if (key.value == "ß") {
                            "ẞ"
                        } else {
                            key.value.uppercase(getCurrentLocale())
                        }
                    }

                    else -> {
                        key.value
                    }
                }
            context.getString(R.string.key_character_description, char)
        }

        is KeyboardKey.Action -> {
            when (key.action) {
                KeyboardKey.ActionType.SHIFT -> {
                    when {
                        state.isCapsLockOn -> context.getString(R.string.caps_lock_on_description)
                        state.isShiftPressed -> context.getString(R.string.shift_active_description)
                        else -> context.getString(R.string.shift_key_description)
                    }
                }

                KeyboardKey.ActionType.BACKSPACE -> {
                    context.getString(R.string.backspace_key_description)
                }

                KeyboardKey.ActionType.SPACE -> {
                    context.getString(R.string.space_key_description)
                }

                KeyboardKey.ActionType.ENTER -> {
                    context.getString(R.string.action_enter_description)
                }

                KeyboardKey.ActionType.SEARCH -> {
                    context.getString(R.string.action_search_description)
                }

                KeyboardKey.ActionType.SEND -> {
                    context.getString(R.string.action_send_description)
                }

                KeyboardKey.ActionType.DONE -> {
                    context.getString(R.string.action_done_description)
                }

                KeyboardKey.ActionType.GO -> {
                    context.getString(R.string.action_go_description)
                }

                KeyboardKey.ActionType.NEXT -> {
                    context.getString(R.string.action_next_description)
                }

                KeyboardKey.ActionType.PREVIOUS -> {
                    context.getString(R.string.action_previous_description)
                }

                KeyboardKey.ActionType.MODE_SWITCH_LETTERS -> {
                    context.getString(R.string.letters_mode_description)
                }

                KeyboardKey.ActionType.MODE_SWITCH_NUMBERS -> {
                    context.getString(R.string.numbers_mode_description)
                }

                KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> {
                    context.getString(R.string.symbols_mode_description)
                }

                KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY -> {
                    context.getString(R.string.symbols_secondary_mode_description)
                }

                KeyboardKey.ActionType.CAPS_LOCK -> {
                    context.getString(R.string.caps_lock_description)
                }

                KeyboardKey.ActionType.LANGUAGE_SWITCH -> {
                    context.getString(R.string.language_switch_description)
                }

                KeyboardKey.ActionType.DAKUTEN -> {
                    context.getString(R.string.action_dakuten)
                }

                KeyboardKey.ActionType.SMALL_KANA -> {
                    context.getString(R.string.action_small_kana)
                }

                KeyboardKey.ActionType.NEXT_CANDIDATE -> context.getString(R.string.action_next_candidate)

                KeyboardKey.ActionType.COMMIT_CANDIDATE -> context.getString(R.string.action_commit_candidate)

                KeyboardKey.ActionType.HANDAKUTEN -> context.getString(R.string.action_handakuten)

                KeyboardKey.ActionType.EMOJI -> context.getString(R.string.action_emoji)

                KeyboardKey.ActionType.TAB -> context.getString(R.string.action_tab)
            }
        }

        is KeyboardKey.FlickKey -> buildFlickContentDescription(key)

        KeyboardKey.Spacer -> {
            ""
        }
    }

    private fun handleSpaceLongPress(view: View) {
        if (longPressPunctuationMode != LongPressPunctuationMode.SPACEBAR) {
            return
        }

        performContextualHaptic(KeyboardKey.Action(KeyboardKey.ActionType.SPACE))

        val currentLayoutLang = languageManager.currentLayoutLanguage.value
        val languageCode = currentLayoutLang.split("-").first()

        backgroundScope.launch {
            try {
                val punctuation = punctuationLoader.loadPunctuation(languageCode)
                withContext(Dispatchers.Main) {
                    showPunctuationPopup(view, punctuation)
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "KeyboardLayoutManager",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "handleSpaceLongPress_loadPunctuation")
                )
                withContext(Dispatchers.Main) {
                    showPunctuationPopup(view, PunctuationLoader.DEFAULT_PUNCTUATION)
                }
            }
        }
    }

    private fun handlePunctuationLongPress(key: KeyboardKey.Character, view: View) {
        performContextualHaptic(key)

        val currentLayoutLang = languageManager.currentLayoutLanguage.value
        val languageCode = currentLayoutLang.split("-").first()

        backgroundScope.launch {
            try {
                val punctuation = punctuationLoader.loadPunctuation(languageCode)
                withContext(Dispatchers.Main) {
                    showPunctuationPopup(view, punctuation)
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "KeyboardLayoutManager",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "handlePunctuationLongPress_loadPunctuation")
                )
                withContext(Dispatchers.Main) {
                    showPunctuationPopup(view, PunctuationLoader.DEFAULT_PUNCTUATION)
                }
            }
        }
    }

    private fun handleShiftLongPress(view: View) {
        if (activeLanguages.size <= 1) {
            return
        }
        if (showLanguageSwitchKey) {
            return
        }
        showLanguagePickerPopup(view, activeLanguages)
    }

    private fun showLanguagePickerPopup(anchorView: View, languages: List<String>) {
        languagePickerPopup?.dismiss()

        val popup = LanguagePickerPopup(context, themeManager)
        popup.setLanguages(
            languages = languages,
            currentLanguage = languageManager.currentLayoutLanguage.value,
            anchorView = anchorView,
            onSelected = { selectedLang ->
                popup.dismiss()
                onLanguageSwitch(selectedLang)

                val displayNames =
                    com.urik.keyboard.settings.KeyboardSettings
                        .getLanguageDisplayNames()
                val displayName = displayNames[selectedLang] ?: selectedLang
                android.widget.Toast
                    .makeText(context, displayName, android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
        )
        popup.showAboveAnchor()
        languagePickerPopup = popup
    }

    private fun showPunctuationPopup(anchorView: View, punctuationList: List<String>) {
        if (!anchorView.isAttachedToWindow || anchorView.windowToken == null) {
            return
        }

        variationPopup?.dismiss()
        languagePickerPopup?.dismiss()

        anchorView.isPressed = false

        val popup =
            CharacterVariationPopup(context, themeManager).apply {
                setCharacterVariations("", punctuationList, punctuationVariationCallback)
                showAboveAnchor(anchorView)
            }

        variationPopup = popup
        activePunctuationPopup = popup
        popupSelectionMode = true
        swipeKeyboardView?.setPopupActive(true)
    }

    private fun handleCharacterLongPress(key: KeyboardKey.Character, view: View, button: Button) {
        val customSymbols = customKeyMappings[key.value.lowercase()].orEmpty()
        val currentLayoutLang = languageManager.currentLayoutLanguage.value

        backgroundScope.launch {
            try {
                val firstRowLetter = if (isNumberHintEnabled() && key.type == KeyboardKey.KeyType.LETTER) {
                    val firstRow = effectiveLayout?.rows?.get(0)
                    if (firstRow?.contains(key) == true) {
                        firstRow
                    } else {
                        null
                    }
                } else {
                    null
                }

                val builtInVariations = characterVariationService.getVariations(key.value, currentLayoutLang)

                val numberPrefix = if (firstRowLetter != null) {
                    val number = (firstRowLetter.indexOf(key) + 1) % 10
                    listOf(number.toString())
                } else {
                    emptyList()
                }

                val merged = mergeVariations(customSymbols, numberPrefix + builtInVariations)

                if (merged.isNotEmpty()) {
                    val casedVariations = applyCasingToVariations(merged)
                    withContext(Dispatchers.Main) {
                        if (customSymbols.isNotEmpty()) {
                            customMappingLongPressFired.add(button)
                        }
                        showCharacterVariationPopup(key, view, casedVariations)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        view.isPressed = false
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "KeyboardLayoutManager",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "handleCharacterVariationLongPress")
                )
                withContext(Dispatchers.Main) {
                    view.isPressed = false
                }
            }
        }
    }

    @VisibleForTesting internal fun mergeVariations(custom: List<String>, builtIn: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (s in custom) seen.add(Normalizer.normalize(s, Normalizer.Form.NFC))
        val result = ArrayList<String>(seen)
        for (s in builtIn) {
            val nfc = Normalizer.normalize(s, Normalizer.Form.NFC)
            if (seen.add(nfc)) result.add(nfc)
        }
        return result
    }

    private fun showCharacterVariationPopup(key: KeyboardKey.Character, anchorView: View, variations: List<String>) {
        if (!anchorView.isAttachedToWindow || anchorView.windowToken == null) {
            return
        }

        variationPopup?.dismiss()
        languagePickerPopup?.dismiss()
        performContextualHaptic(key)

        anchorView.isPressed = false

        currentVariationKeyType = key.type

        variationPopup =
            CharacterVariationPopup(context, themeManager).apply {
                setCharacterVariations("", variations, characterVariationCallback)
                showAboveAnchor(anchorView)
            }

        popupSelectionMode = true
        swipeKeyboardView?.setPopupActive(true)
    }

    private fun getKeyWeight(key: KeyboardKey, rowKeys: List<KeyboardKey>): Float {
        // An explicit per-key column width (cells) wins over the heuristics — used by imported layouts so
        // the bottom bar aligns to the grid (shift = 1, space = 3, each key = 1, ...).
        if (key.width > 0f) return key.width * currentKeySize.scaleFactor

        val isNumberModeRow = isNumberModeRow(rowKeys)
        val characterKeyCount = rowKeys.count { it is KeyboardKey.Character }
        val isSplitMode = splitGapPx > 0 && !containsSpacebar(rowKeys)

        val baseWeight =
            if (isNumberModeRow || isSplitMode) {
                STANDARD_KEY_WEIGHT
            } else {
                when (key) {
                    is KeyboardKey.Character -> {
                        STANDARD_KEY_WEIGHT
                    }

                    is KeyboardKey.Action -> {
                        when (key.action) {
                            KeyboardKey.ActionType.SPACE -> {
                                return currentSpaceBarSize.widthMultiplier
                            }

                            KeyboardKey.ActionType.SHIFT,
                            KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS_SECONDARY
                            -> {
                                if (characterKeyCount >= 10) STANDARD_KEY_WEIGHT else SHIFT_KEY_WEIGHT
                            }

                            KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS -> {
                                if (effectiveLayout?.mode == KeyboardMode.SYMBOLS_SECONDARY && characterKeyCount < 10) {
                                    SHIFT_KEY_WEIGHT
                                } else {
                                    STANDARD_KEY_WEIGHT
                                }
                            }

                            KeyboardKey.ActionType.BACKSPACE -> {
                                val hasFlickKeys = rowKeys.any { it is KeyboardKey.FlickKey }
                                if (characterKeyCount >= 10 ||
                                    hasFlickKeys
                                ) {
                                    STANDARD_KEY_WEIGHT
                                } else {
                                    BACKSPACE_KEY_WEIGHT
                                }
                            }

                            else -> {
                                STANDARD_KEY_WEIGHT
                            }
                        }
                    }

                    is KeyboardKey.FlickKey -> STANDARD_KEY_WEIGHT

                    KeyboardKey.Spacer -> {
                        STANDARD_KEY_WEIGHT
                    }
                }
            }

        return baseWeight * currentKeySize.scaleFactor
    }

    private fun isNumberModeRow(rowKeys: List<KeyboardKey>): Boolean {
        if (rowKeys.size != 3) return false

        return rowKeys.all { key ->
            when (key) {
                is KeyboardKey.Character ->
                    key.type == KeyboardKey.KeyType.NUMBER ||
                        key.type == KeyboardKey.KeyType.PUNCTUATION

                is KeyboardKey.Action -> key.action == KeyboardKey.ActionType.BACKSPACE

                is KeyboardKey.FlickKey -> false

                KeyboardKey.Spacer -> false
            }
        }
    }

    private fun isTopNumberRow(rowKeys: List<KeyboardKey>): Boolean {
        val characterKeys = rowKeys.filterIsInstance<KeyboardKey.Character>()
        return characterKeys.size == 10 && characterKeys.all { it.type == KeyboardKey.KeyType.NUMBER }
    }

    private fun buildFlickContentDescription(key: KeyboardKey.FlickKey): String {
        val parts = buildList {
            key.up?.let { add("up $it") }
            key.right?.let { add("right $it") }
            key.down?.let { add("down $it") }
            key.left?.let { add("left $it") }
        }
        return if (parts.isEmpty()) {
            key.center
        } else {
            "${key.center}. Flick: ${parts.joinToString(", ")}"
        }
    }

    private fun shouldCapitalize(state: KeyboardState): Boolean = state.isShiftPressed || state.isCapsLockOn

    private fun applyCasingToVariations(variations: List<String>): List<String> {
        val script = effectiveLayout?.script ?: "Latn"
        if (!isBicameralScript(script) || !shouldCapitalize(lastKeyboardState)) {
            return variations
        }
        val locale = getCurrentLocale()
        return variations.map { it.uppercase(locale) }
    }

    private fun getKeyActivatedState(key: KeyboardKey, state: KeyboardState): Boolean = when (key) {
        is KeyboardKey.Action -> {
            when (key.action) {
                KeyboardKey.ActionType.SHIFT -> state.isShiftPressed && !state.isCapsLockOn
                KeyboardKey.ActionType.CAPS_LOCK -> state.isCapsLockOn
                else -> false
            }
        }

        else -> {
            false
        }
    }

    private fun getKeyBackground(key: KeyboardKey): Drawable {
        ensureCacheValid()
        val theme = themeManager.currentTheme.value
        // Per-geometry colour overrides (null = use the theme colour).
        val bgOverride = adaptiveDimensions?.keyBgColor
        val borderColor = adaptiveDimensions?.keyBorderColor ?: theme.colors.keyBorder

        val backgroundColor =
            when (key) {
                is KeyboardKey.Character -> bgOverride ?: theme.colors.keyBackgroundCharacter

                is KeyboardKey.Action -> bgOverride ?: when (key.action) {
                    KeyboardKey.ActionType.SPACE -> theme.colors.keyBackgroundSpace
                    else -> theme.colors.keyBackgroundAction
                }

                is KeyboardKey.FlickKey -> bgOverride ?: theme.colors.keyBackgroundCharacter

                KeyboardKey.Spacer -> {
                    android.graphics.Color.TRANSPARENT
                }
            }

        val normalDrawable =
            GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = cachedCornerRadius
                setStroke(cachedStrokeWidth, borderColor)
            }

        val pressedDrawable =
            if (pressHighlightEnabled) {
                GradientDrawable().apply {
                    setColor(theme.colors.statePressed)
                    cornerRadius = cachedCornerRadius
                    setStroke(cachedStrokeWidth, theme.colors.keyBorderPressed)
                }
            } else {
                normalDrawable
            }

        val focusedDrawable =
            GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = cachedCornerRadius
                setStroke(cachedStrokeWidthThick, theme.colors.keyBorderFocused)
            }

        val activatedDrawable =
            GradientDrawable().apply {
                setColor(theme.colors.stateActivated)
                cornerRadius = cachedCornerRadius
                setStroke(cachedStrokeWidthThick, theme.colors.keyBorderFocused)
            }

        val stateListDrawable = StateListDrawable()
        stateListDrawable.addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
        stateListDrawable.addState(intArrayOf(android.R.attr.state_activated), activatedDrawable)
        stateListDrawable.addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
        stateListDrawable.addState(intArrayOf(), normalDrawable)

        return RippleDrawable(
            android.content.res.ColorStateList.valueOf(
                if (pressHighlightEnabled) theme.colors.statePressed else android.graphics.Color.TRANSPARENT
            ),
            stateListDrawable,
            null
        )
    }

    private fun getKeyTextColor(key: KeyboardKey): Int {
        val colors = themeManager.currentTheme.value.colors
        val override = adaptiveDimensions?.keyTextColor
        return when (key) {
            is KeyboardKey.Character -> override ?: colors.keyTextCharacter
            is KeyboardKey.Action -> override ?: colors.keyTextAction
            is KeyboardKey.FlickKey -> override ?: colors.keyTextCharacter
            KeyboardKey.Spacer -> android.graphics.Color.TRANSPARENT
        }
    }

    private fun addBadgeOverlay(base: Drawable, badge: Drawable?): Drawable {
        val density = context.resources.displayMetrics.density
        val iconSize = (10 * density).toInt()
        val leftInset = (5 * density).toInt()
        val topInset = (4 * density).toInt()

        return LayerDrawable(arrayOf(base, badge)).apply {
            setLayerSize(1, iconSize, iconSize)
            setLayerInset(1, leftInset, topInset, 0, 0)
            setLayerGravity(1, Gravity.TOP or Gravity.START)
        }
    }

    private fun createLangBadgeDrawable(text: String, color: Int): android.graphics.drawable.BitmapDrawable {
        val density = context.resources.displayMetrics.density
        val sizePx = (10 * density).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = android.graphics.Canvas(bitmap)
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = sizePx * 0.75f
                typeface = Typeface.DEFAULT_BOLD
            }
        canvas.drawText(text, sizePx / 2f, (sizePx - paint.descent() - paint.ascent()) / 2f, paint)
        return bitmap.toDrawable(context.resources)
    }

    fun cleanup() {
        backgroundJob.cancel()
        backgroundJob = SupervisorJob()
        backgroundScope = CoroutineScope(Dispatchers.IO + backgroundJob)
        effectiveLayout = null
        returnActiveButtonsToPool()
        buttonPool.clear()
        longPressConsumedButtons.clear()
        buttonPendingCallbacks.forEach { (_, pending) ->
            pending.handler.removeCallbacks(pending.runnable)
        }
        buttonPendingCallbacks.clear()
        backspaceController.cleanup()
        variationPopup?.dismiss()
        variationPopup = null
        languagePickerPopup?.dismiss()
        languagePickerPopup = null
        punctuationLoader.cleanup()
    }

    companion object {
        private const val STANDARD_KEY_WEIGHT = 1f
        private const val SHIFT_KEY_WEIGHT = 1.5f
        private const val BACKSPACE_KEY_WEIGHT = 1.5f
        private const val MAX_BUTTON_POOL_SIZE = 40
    }
}
