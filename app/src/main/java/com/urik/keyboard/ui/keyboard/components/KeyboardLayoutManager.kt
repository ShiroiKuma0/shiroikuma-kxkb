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
import android.os.Looper
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
import com.urik.keyboard.model.KeyAppearance
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
    // Letters-page capabilities: the cluster bands (centre char -> band) and whether the page has flat
    // Character letter keys (a swipe board) — either one puts the host in bar-commit (cluster) mode.
    private val onClusterBands: (Map<Char, String>, Boolean) -> Unit = { _, _ -> },
    // Long-press Space (held, not slid): returns true if the host consumed it (e.g. a literal-space escape
    // during cluster typing), false to fall back to the default long-press behaviour (punctuation popup).
    private val onSpaceLongPress: () -> Boolean = { false },
    private val onShowInputMethodPicker: () -> Unit = {},
    private val onFlickBinding: (KeyboardKey.FlickBinding) -> Unit = {},
    // A cycle key was tapped: the host steps through its entries (delete previous, commit next, wrap).
    private val onCycleTap: (List<String>) -> Unit = {},
    private val characterVariationService: CharacterVariationService,
    private val languageManager: LanguageManager,
    private val themeManager: ThemeManager,
    cacheMemoryManager: CacheMemoryManager
) {
    private var clipboardEnabled = false
    private var activeLanguages: List<String> = emptyList()
    private var visibleLayoutsByLanguage: Map<String, Set<String>> = emptyMap()
    private var showLanguageSwitchKey = false
    private var showNumberHints = false
    private var hasMultipleImes = false
    private var pressHighlightEnabled = true
    private var keyPreviewEnabled = true

    var effectiveLayout: KeyboardLayout? = null
        private set

    /**
     * Total height (in keyHeight units) of the last-rendered LETTERS page — the keyboard's "home" height. The
     * fixed-grid Number pad has its own row count, so it is scaled to this so switching to it never resizes
     * the keyboard. 0 until a letters page has rendered (then the numpad renders un-scaled, a safe fallback).
     */
    private var referenceHeightUnits: Float = 0f

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

    /**
     * The pulse-length factor the strength setting maps to (see [HapticSignature.durationScaleFor]): the
     * setting must be felt even where the vibrator ignores amplitude, and at 255 a full-amplitude pulse of
     * the original ~30 ms was still faint — "strong" needs length as much as amplitude.
     */
    private val hapticDurationScale: Float
        get() = HapticSignature.durationScaleFor(hapticAmplitude)

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
        // Let the detector widen a key's sectors to the directions that key really carries (see
        // FlickGestureDetector.computeDirection) — resolved against the face that would actually commit.
        detector.setPopulatedDirectionsProvider { key -> populatedFlickDirections(key) }
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
                // A tap on a cycle key steps through its entries (a flick in a direction falls through to the
                // normal slide handling below).
                if (direction == FlickGestureDetector.FlickDirection.NONE &&
                    key.tapKind == KeyboardKey.TapKind.CYCLE && key.cycleTaps.isNotEmpty()
                ) {
                    onCycleTap(key.cycleTaps)
                    return
                }
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

    /** Single reusable magnified key-preview bubble (lazily created the first time it's enabled+shown). */
    private var keyPreviewPopup: KeyPreviewPopup? = null

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

    /** Compass position -> the direction that reaches it, for [populatedFlickDirections]. */
    private val flickDirectionPositions = listOf(
        FlickGestureDetector.FlickDirection.UP to "up",
        FlickGestureDetector.FlickDirection.DOWN to "down",
        FlickGestureDetector.FlickDirection.LEFT to "left",
        FlickGestureDetector.FlickDirection.RIGHT to "right",
        FlickGestureDetector.FlickDirection.UP_LEFT to "upLeft",
        FlickGestureDetector.FlickDirection.UP_RIGHT to "upRight",
        FlickGestureDetector.FlickDirection.DOWN_LEFT to "downLeft",
        FlickGestureDetector.FlickDirection.DOWN_RIGHT to "downRight"
    )

    /**
     * The directions this key would really commit something in — mirroring exactly what onFlickCommit
     * reads: a binding off the base key, or a character off the SHIFTED face. An empty result (a key with
     * a bare centre) leaves the detector on its plain 45° sectors.
     */
    private fun populatedFlickDirections(key: KeyboardKey.FlickKey): Set<FlickGestureDetector.FlickDirection> {
        val face = flickFace(key, lastKeyboardState)
        return flickDirectionPositions
            .filter { (_, pos) -> key.bindings.containsKey(pos) || flickCharAt(face, pos) != null }
            .map { it.first }
            .toSet()
    }

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
        val layers = mutableListOf(
            keyBackground,
            ClusterMainsDrawable(
                key.clusterMains, mainsPaint, basePx,
                (adaptiveDimensions?.primaryOffsetXPx ?: 0).toFloat(),
                (adaptiveDimensions?.primaryOffsetYPx ?: 0).toFloat()
            )
        )
        val hintLabels = flickHintLabels(key).filterKeys { it != "left" && it != "right" }
        buildFlickHintsDrawable(key, hintLabels)?.let { layers.add(it) }
        return LayerDrawable(layers.toTypedArray())
    }

    /**
     * A column key: its band of main characters stacked VERTICALLY down the centre, plus the left/right and
     * diagonal hints — but NOT up/down (those ARE the band ends, reached by the vertical slide).
     */
    private fun createColumnBackground(
        keyBackground: Drawable,
        key: KeyboardKey.FlickKey,
        primaryTextSp: Float
    ): Drawable {
        val mainsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = getKeyTextColor(key)
            textAlign = Paint.Align.CENTER
            typeface = keyLabelTypeface()
        }
        val basePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, primaryTextSp, context.resources.displayMetrics
        )
        val layers = mutableListOf(
            keyBackground,
            ColumnMainsDrawable(
                key.clusterMains, mainsPaint, basePx,
                (adaptiveDimensions?.primaryOffsetXPx ?: 0).toFloat(),
                (adaptiveDimensions?.primaryOffsetYPx ?: 0).toFloat()
            )
        )
        val hintLabels = flickHintLabels(key).filterKeys { it != "up" && it != "down" }
        buildFlickHintsDrawable(key, hintLabels)?.let { layers.add(it) }
        return LayerDrawable(layers.toTypedArray())
    }

    /** Draws each character of a column band centred in its own VERTICAL slot at primary size (top to bottom). */
    private class ColumnMainsDrawable(
        private val mains: String,
        private val paint: Paint,
        private val basePx: Float,
        private val offX: Float = 0f,
        private val offY: Float = 0f
    ) : Drawable() {
        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty || mains.isEmpty()) return
            paint.textSize = basePx
            paint.textAlign = Paint.Align.CENTER
            val fm = paint.fontMetrics
            val lineHeight = fm.descent - fm.ascent
            val total = lineHeight * mains.length
            val cx = b.exactCenterX() + offX
            // Baseline of the first (top) glyph so the whole stack is vertically centred on the key.
            var baseline = b.exactCenterY() + offY - total / 2f - fm.ascent
            for (i in mains.indices) {
                canvas.drawText(mains[i].toString(), cx, baseline, paint)
                baseline += lineHeight
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
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

    /**
     * Show the magnified key-preview bubble for a pressed character [button], if the preview is enabled.
     * Only shows for character-bearing keys whose centre tap commits a glyph — plain [KeyboardKey.Character]
     * keys, and a [KeyboardKey.FlickKey] whose centre tap is a character (NOT a `bindings["center"]` action).
     * The glyph is the label the renderer already computed for the key (shift/uppercase honoured); colours
     * resolve from the per-geometry look knobs, falling back to the active theme. Reuses one popup instance.
     */
    private fun showKeyPreview(button: Button) {
        if (!keyPreviewEnabled) return
        val key = button.getTag(R.id.key_data) as? KeyboardKey ?: return

        val glyph: String =
            when (key) {
                is KeyboardKey.Character -> getKeyLabel(key, lastKeyboardState)
                is KeyboardKey.FlickKey -> {
                    // A FlickKey whose centre tap is an editor action (not a character) is out of scope.
                    if (key.bindings.containsKey("center")) return
                    flickFace(key, lastKeyboardState).center
                }
                else -> return
            }
        if (glyph.isEmpty()) return

        val colors = themeManager.currentTheme.value.colors
        val textColor = getKeyTextColor(key)
        val bgColor = adaptiveDimensions?.keyBgColor ?: colors.keyBackgroundCharacter
        val borderColor = adaptiveDimensions?.keyBorderColor ?: colors.keyBorder

        val popup = keyPreviewPopup ?: KeyPreviewPopup(context, themeManager).also { keyPreviewPopup = it }
        popup.show(
            glyph = glyph,
            anchorView = button,
            // Render relative to the key's own label size (px) so the bubble tracks the look's font scale.
            labelTextSizePx = button.textSize,
            textColor = textColor,
            bgColor = bgColor,
            borderColor = borderColor,
            typeface = button.typeface ?: keyLabelTypeface()
        )
    }

    /** Draws each character of a cluster band centred in its own horizontal slot at (fit-to-width) primary size. */
    private class ClusterMainsDrawable(
        private val mains: String,
        private val paint: Paint,
        private val basePx: Float,
        private val offX: Float = 0f,
        private val offY: Float = 0f
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
            var x = b.exactCenterX() - total / 2f + offX
            val fm = paint.fontMetrics
            val cy = b.exactCenterY() - (fm.ascent + fm.descent) / 2f + offY
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
        accessibilityManager = accessibilityManager,
        showKeyPreview = { button -> showKeyPreview(button) },
        dismissKeyPreview = { keyPreviewPopup?.hide() }
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

    // --- Compass long-press extra-key strip ("XK") ------------------------------------------------------
    private val flickStripHandler = Handler(Looper.getMainLooper())
    private var flickStripRunnable: Runnable? = null
    private var flickStripActive = false
    private var flickStripDownX = 0f
    private var flickStripDownY = 0f
    private var flickStripDownRawX = 0f
    private var flickStripDownRawY = 0f
    private var flickStripLastChar: String? = null
    private var extraStripPopup: ExtraKeyStripPopup? = null
    private val flickStripSlopPx = 20f * context.resources.displayMetrics.density

    /** Arm the long-press strip for a held compass key that defines extra keys (cancelled by a flick-sized move). */
    private fun scheduleFlickStrip(key: KeyboardKey.FlickKey, view: View) {
        if (flickFace(key, lastKeyboardState).longPressExtraKeys.isEmpty()) return
        cancelFlickStripTimer()
        val r = Runnable { showFlickStrip(key, view) }
        flickStripRunnable = r
        flickStripHandler.postDelayed(r, currentLongPressDuration.durationMs)
    }

    private fun cancelFlickStripTimer() {
        flickStripRunnable?.let { flickStripHandler.removeCallbacks(it) }
        flickStripRunnable = null
    }

    // --- Compass guide (FlickPopup) deferred show --------------------------------------------------------
    // The guide used to be raised on EVERY touch-down (harmless while it was a transparent grid, but since it
    // became the bordered black panel every plain tap flashed it). Now it appears ONLY after a brief HOLD (the
    // user is reading the directions): a quick tap just enters the key, and a flick/swipe in motion cancels
    // the pending guide — the mover knows where they're going. A guide already up from a hold stays and
    // tracks the direction highlight.
    private var flickGuideRunnable: Runnable? = null
    private val flickGuideDelayMs = 200L

    private fun scheduleFlickGuide(key: KeyboardKey.FlickKey, view: View) {
        cancelFlickGuideTimer()
        val r = Runnable { showFlickGuide(key, view) }
        flickGuideRunnable = r
        flickStripHandler.postDelayed(r, flickGuideDelayMs)
    }

    private fun cancelFlickGuideTimer() {
        flickGuideRunnable?.let { flickStripHandler.removeCallbacks(it) }
        flickGuideRunnable = null
    }

    private fun showFlickGuide(key: KeyboardKey.FlickKey, view: View) {
        if (flickPopup != null) return
        val popup = FlickPopup(context, themeManager, 18f * (adaptiveDimensions?.compassFontScale ?: 2f))
        flickPopup = popup
        popup.show(key, view)
    }

    // --- Arrow-key hold repeat ---------------------------------------------------------------------------
    // A held arrow key keeps the cursor MOVING (like a hardware key's typematic repeat) instead of raising
    // the compass guide — the key's whole point is its centre action, so the guide told you nothing and the
    // hold was wasted. Flicks still work: a flick-sized move before the timer fires cancels the repeat.
    private var arrowRepeatRunnable: Runnable? = null
    private var arrowRepeatActive = false

    /** The centre editor-action binding IF this key is a repeatable cursor arrow, else null. */
    private fun arrowRepeatBinding(key: KeyboardKey.FlickKey): KeyboardKey.FlickBinding.Action? =
        (key.bindings["center"] as? KeyboardKey.FlickBinding.Action)
            ?.takeIf { it.name in ARROW_ACTION_NAMES }

    private fun scheduleArrowRepeat(binding: KeyboardKey.FlickBinding.Action) {
        cancelArrowRepeat()
        val r = object : Runnable {
            override fun run() {
                if (arrowRepeatRunnable !== this) return
                if (!arrowRepeatActive) {
                    arrowRepeatActive = true
                    // The hold owns the key now — the release must not ALSO commit a tap.
                    flickGestureDetector.cancel()
                    performContextualHaptic(null)
                }
                handleFlickBinding(binding)
                flickStripHandler.postDelayed(this, ARROW_REPEAT_INTERVAL_MS)
            }
        }
        arrowRepeatRunnable = r
        flickStripHandler.postDelayed(r, currentLongPressDuration.durationMs)
    }

    private fun cancelArrowRepeat() {
        arrowRepeatRunnable?.let { flickStripHandler.removeCallbacks(it) }
        arrowRepeatRunnable = null
        arrowRepeatActive = false
    }

    // --- Mic-key hold: voice-language quick flip ---------------------------------------------------------
    // Holding the mic key (the compass key whose centre is the "voice" action) fires the voice-language
    // flip ONCE (layout language ⇄ English; English/GNU layouts ⇄ Czech) instead of raising the compass
    // guide — the same touch-ownership model as the arrow keys' hold repeat.
    private var voiceHoldRunnable: Runnable? = null
    private var voiceHoldFired = false

    private fun isVoiceKey(key: KeyboardKey.FlickKey): Boolean =
        (key.bindings["center"] as? KeyboardKey.FlickBinding.Action)?.name == "voice"

    private fun scheduleVoiceHold() {
        cancelVoiceHold()
        val r = Runnable {
            voiceHoldFired = true
            // The hold owns the key now — the release must not ALSO fire the centre tap.
            flickGestureDetector.cancel()
            performVoiceFlipHaptic()
            handleFlickBinding(KeyboardKey.FlickBinding.Action("voice_lang"))
        }
        voiceHoldRunnable = r
        flickStripHandler.postDelayed(r, currentLongPressDuration.durationMs)
    }

    private fun cancelVoiceHold() {
        voiceHoldRunnable?.let { flickStripHandler.removeCallbacks(it) }
        voiceHoldRunnable = null
        voiceHoldFired = false
    }

    /** Long-press fired: the packed strip takes over the touch; the compass preview stays put, strip above it. */
    private fun showFlickStrip(key: KeyboardKey.FlickKey, view: View) {
        flickGestureDetector.cancel() // the strip owns the gesture now — no flick commit on release
        currentVariationKeyType = key.type
        val density = context.resources.displayMetrics.density
        // The strip is case-aware: under shift the key's shifted face supplies its own row (and centre),
        // so "i" and "I" can order their candidates differently.
        val face = flickFace(key, lastKeyboardState)
        val chars = listOf(face.center) + face.longPressExtraKeys // centre first = the highlighted base char
        val popup = ExtraKeyStripPopup(context, themeManager)
        extraStripPopup?.dismiss()
        extraStripPopup = popup
        popup.setChars(
            chars = chars,
            baseIndex = 0,
            textSizePx = 14f * (adaptiveDimensions?.extraStripFontScale ?: 2.5f) * density,
            maxWidthPx = context.resources.displayMetrics.widthPixels - (16 * density).toInt(),
            downRawX = flickStripDownRawX,
            downRawY = flickStripDownRawY,
            onSelected = characterVariationCallback
        )
        popup.showAboveCompass(view, flickPopup?.height ?: 0)
        flickStripLastChar = face.center
        flickStripActive = true
        popupSelectionMode = true
        swipeKeyboardView?.setPopupActive(true)
        performContextualHaptic(null)
    }

    private fun onFlickStripMove(rawX: Float, rawY: Float) {
        val char = extraStripPopup?.moveTo(rawX, rawY)
        if (char != flickStripLastChar) {
            flickStripLastChar = char
            if (char != null) performContextualHaptic(null)
        }
    }

    private fun commitFlickStrip() {
        extraStripPopup?.commitHighlighted()
        dismissFlickStrip()
    }

    /** Released over the lock: drop the compass, keep the strip up and tappable (tap a glyph to enter, tap
     *  outside to close). The gesture is over, but the strip stays modal until it's dismissed. */
    private fun lockFlickStrip() {
        flickStripActive = false
        flickPopup?.dismiss()
        flickPopup = null
        extraStripPopup?.enterLockMode(
            onPick = { ch -> characterVariationCallback(ch); extraStripPopup?.dismiss() },
            onDismiss = { resetFlickStripState() }
        )
    }

    /** Reset the strip bookkeeping WITHOUT dismissing (the popup is already dismissing — called from its
     *  onDismiss in lock mode). */
    private fun resetFlickStripState() {
        flickStripActive = false
        flickStripLastChar = null
        extraStripPopup = null
        popupSelectionMode = false
        swipeKeyboardView?.setPopupActive(false)
    }

    private fun dismissFlickStrip() {
        flickStripActive = false
        flickStripLastChar = null
        flickPopup?.dismiss()
        flickPopup = null
        extraStripPopup?.dismiss()
        extraStripPopup = null
        popupSelectionMode = false
        swipeKeyboardView?.setPopupActive(false)
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
        val effect = HapticSignature.BackspaceChirp.createEffect(amplitude, hapticDurationScale)
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

    /** Per-language switcher-visible layout ids; a language absent from the map shows all. */
    fun updateVisibleLayouts(map: Map<String, Set<String>>) {
        visibleLayoutsByLanguage = map
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

    fun updateKeyPreview(enabled: Boolean) {
        keyPreviewEnabled = enabled
        if (!enabled) keyPreviewPopup?.hide()
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

    /** The double tick confirming the long-press voice-language flip. Same gating as every haptic. */
    private fun performVoiceFlipHaptic() {
        if (!hapticEnabled || hapticAmplitude == 0) return
        try {
            val amplitude = if (supportsAmplitudeControl) {
                hapticAmplitude
            } else {
                android.os.VibrationEffect.DEFAULT_AMPLITUDE
            }
            vibrateEffect(HapticSignature.VoiceFlipDouble.createEffect(amplitude, hapticDurationScale))
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "KeyboardLayoutManager",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "performVoiceFlipHaptic")
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

                    is KeyboardKey.FlickKey ->
                        // The mic key gets a firm, unmistakable pulse — recording starts/stops on it. Every
                        // other compass/cluster/column key clicks like the plain key of its type.
                        when {
                            isVoiceKey(key) -> HapticSignature.VoicePulse
                            key.type == KeyboardKey.KeyType.PUNCTUATION ||
                                key.type == KeyboardKey.KeyType.SYMBOL -> HapticSignature.PunctuationTick
                            key.type == KeyboardKey.KeyType.NUMBER -> HapticSignature.NumberClick
                            else -> HapticSignature.LetterClick
                        }

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
            val effect = signature.createEffect(amplitude, hapticDurationScale)
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
        if (layout.mode == KeyboardMode.LETTERS) publishClusterBands(layout)
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
                showFlickHints = layout.showFlickHints,
                hardwareKeymap = layout.hardwareKeymap
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
                val bgColor = adaptiveDimensions?.keyboardBgColor
                    ?: themeManager.currentTheme.value.colors.keyboardBackground
                // When split, the background leaves a see-through vertical strip down the centre (the gap
                // between the two halves) so the app shows through; the un-split bottom row's opaque keys
                // cover the strip there. When not split it's a plain fill.
                background =
                    if (splitGapPx > 0) SplitBackgroundDrawable(bgColor, splitGapPx) else null
                if (splitGapPx <= 0) setBackgroundColor(bgColor)

                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = context.getString(R.string.keyboard_description)
            }

        // Keep the keyboard the SAME total height on EVERY page: the LETTERS page sets the reference height,
        // and any other page (symbols, alt, number pad — whatever its row count) is scaled to fill exactly
        // that height, so a page with more rows gets proportionally shorter keys instead of a taller keyboard.
        // A page that already matches the letters row count scales by 1.0 (unchanged).
        val pageUnits = pageHeightUnits(processedRows)
        if (layout.mode == KeyboardMode.LETTERS && pageUnits > 0f) referenceHeightUnits = pageUnits
        val pageHeightScale =
            if (layout.mode != KeyboardMode.LETTERS && referenceHeightUnits > 0f && pageUnits > 0f) {
                referenceHeightUnits / pageUnits
            } else {
                1f
            }

        // A futokxkb column board is a row of tall `heightRows` column keys; the rows it spans hold spacers
        // (under the columns) and any stacked continuation keys (e.g. a 3-key compass column). Render such a
        // group as a transposed band of vertical columns; everything else is an ordinary horizontal row.
        val lastRowIndex = processedRows.size - 1
        var index = 0
        while (index < processedRows.size) {
            val span = columnBandSpan(processedRows, index)
            // The first row (top) and the last row (bottom) get their own height multiplier; everything in
            // between renders at 1.0. A column band that starts at row 0 is the top; one ending at the last
            // row is the bottom. Defaults are 1.0, so an un-tuned layout renders identically. The numpad's
            // page scale (1.0 for every other page) keeps its total height equal to the letters page.
            val rowHeightScale =
                edgeRowHeightScale(startIndex = index, span = maxOf(span, 1), lastRowIndex = lastRowIndex) * pageHeightScale
            if (span > 0) {
                keyboardContainer.addView(
                    createColumnBandView(processedRows.subList(index, index + span), state, rowHeightScale)
                )
                index += span
            } else {
                val row = processedRows[index]
                val hasNumberRowGutter = index == 0 && isTopNumberRow(row) && processedRows.size > 1
                keyboardContainer.addView(createRowView(row, state, hasNumberRowGutter, rowHeightScale))
                index += 1
            }
        }

        return keyboardContainer
    }

    private fun rowMaxHeightRows(row: List<KeyboardKey>): Int =
        row.maxOfOrNull { (it.attributes?.heightRows ?: 1f).toInt() } ?: 1

    /**
     * The per-key height multiplier for the row/band beginning at [startIndex] and spanning [span] rows: the
     * top-row factor when it starts at row 0, the bottom-row factor when it ends at [lastRowIndex], else 1.0.
     * Both factors default to 1.0, so an un-tuned layout (and any non-edge row) renders unchanged. A
     * single-row keyboard's only row counts as the top.
     */
    private fun edgeRowHeightScale(startIndex: Int, span: Int, lastRowIndex: Int): Float {
        val dims = adaptiveDimensions ?: return 1f
        return when {
            startIndex == 0 -> dims.topRowHeightScale
            startIndex + span - 1 == lastRowIndex -> dims.bottomRowHeightScale
            else -> 1f
        }
    }

    /**
     * If the row at [i] starts a column band (has a `heightRows` > 1 key), how many rows it spans: the
     * starting row plus the following same-length continuation rows (those carrying spacers under the tall
     * keys), capped at the band's heightRows. Returns 0 when the row is an ordinary row.
     */
    private fun columnBandSpan(rows: List<List<KeyboardKey>>, i: Int): Int {
        val n = rowMaxHeightRows(rows[i])
        if (n <= 1) return 0
        val len = rows[i].size
        var span = 1
        while (span < n && i + span < rows.size) {
            val next = rows[i + span]
            if (next.size == len && next.any { it is KeyboardKey.Spacer }) span++ else break
        }
        return span
    }

    /**
     * The page's total height in keyHeight units — the SAME walk the render loop does (column bands counted by
     * their span, top/bottom rows by their edge factor). Used to scale the Number pad to the LETTERS height.
     */
    private fun pageHeightUnits(rows: List<List<KeyboardKey>>): Float {
        var units = 0f
        var i = 0
        val last = rows.size - 1
        while (i < rows.size) {
            val span = maxOf(columnBandSpan(rows, i), 1)
            units += edgeRowHeightScale(i, span, last) * span
            i += span
        }
        return units
    }

    /**
     * Render a column band as a horizontal strip of vertical columns. Each column stacks the non-spacer keys
     * at its position across the grouped rows, weighted by their `heightRows`, so a `heightRows:N` column key
     * fills the full band height while N stacked single keys each take 1/N — matching the futokxkb grid.
     */
    private fun createColumnBandView(
        group: List<List<KeyboardKey>>,
        state: KeyboardState,
        rowHeightScale: Float = 1f
    ): LinearLayout {
        ensureCacheValid()
        val n = group.maxOf { rowMaxHeightRows(it) }
        val baseKeyHeight = requireDim("keyHeight")
        val baseMinTarget = requireDim("minTarget")
        // Edge-row height factor for a top/bottom column band (1.0 = identical to the un-scaled band).
        val keyHeight = if (rowHeightScale == 1f) baseKeyHeight else (baseKeyHeight * rowHeightScale).toInt().coerceAtLeast(1)
        val minTarget = if (rowHeightScale == 1f) baseMinTarget else (baseMinTarget * rowHeightScale).toInt().coerceAtLeast(1)
        val visualHeight = keyHeight + 2
        val verticalMargin = ((minTarget - visualHeight) / 2).coerceAtLeast(0)
        val horizontalMargin = requireDim("horizontalMargin")
        val rowUnit = visualHeight + verticalMargin * 2
        val cols = group.maxOf { it.size }
        val firstRow = group[0]

        val band =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams =
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowUnit * n)
                        .apply { setMargins(0, 0, 0, verticalMargin) }
            }

        for (c in 0 until cols) {
            val colKeys = group.mapNotNull { it.getOrNull(c) }.filter { it !is KeyboardKey.Spacer }
            val weight = colKeys.firstOrNull()?.let { getKeyWeight(it, firstRow) } ?: STANDARD_KEY_WEIGHT
            val column =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    isBaselineAligned = false
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                }
            for (key in colKeys) {
                val button = getOrCreateKeyButton(key, state, firstRow)
                button.layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        (key.attributes?.heightRows ?: 1f)
                    ).apply { setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin) }
                column.addView(button)
            }
            band.addView(column)
        }
        return band
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
        hasNumberRowGutter: Boolean = false,
        rowHeightScale: Float = 1f
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

            val leftContainer = createHalfRowContainer(leftKeys, state, rowHeightScale)
            val gapSpacer =
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(splitGapPx, LinearLayout.LayoutParams.MATCH_PARENT)
                }
            val rightContainer = createHalfRowContainer(rightKeys, state, rowHeightScale)

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
                    val keyButton = getOrCreateKeyButton(key, state, keys, rowHeightScale)
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

    private fun createHalfRowContainer(
        keys: List<KeyboardKey>,
        state: KeyboardState,
        rowHeightScale: Float = 1f
    ): LinearLayout =
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
                    val keyButton = getOrCreateKeyButton(key, state, keys, rowHeightScale)
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

    private fun getOrCreateKeyButton(
        key: KeyboardKey,
        state: KeyboardState,
        rowKeys: List<KeyboardKey>,
        rowHeightScale: Float = 1f
    ): Button {
        val button =
            if (buttonPool.isNotEmpty()) {
                buttonPool.removeAt(buttonPool.size - 1).apply {
                    isPressed = false
                }
            } else {
                Button(context)
            }

        configureButton(button, key, state, rowKeys, rowHeightScale)
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
                // The character-variation popup is taking over — drop the preview so they don't stack.
                keyPreviewPopup?.hide()
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

            is KeyboardKey.Action if key.action == KeyboardKey.ActionType.SPACE ->
                buttonLongPressRunnables[button] = Runnable {
                    // Consume the tap only when the long-press actually did something (literal-space escape
                    // during cluster typing, or the punctuation popup) — otherwise a held Space still types.
                    if (handleSpaceLongPress(button)) {
                        longPressConsumedButtons.add(button)
                    }
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
    private fun configureButton(
        button: Button,
        key: KeyboardKey,
        state: KeyboardState,
        rowKeys: List<KeyboardKey>,
        rowHeightScale: Float = 1f
    ) {
        ensureCacheValid()

        button.apply {
            setOnClickListener(null)
            setOnLongClickListener(null)
            setOnTouchListener(null)

            val minTarget = requireDim("minTarget")
            val keyHeight = requireDim("keyHeight")
            val gutterReduction = if (isTopNumberRow(rowKeys)) requireDim("numberRowGutter") else 0
            // Edge-row height factor (top/bottom rows only; 1.0 = no change → identical to the un-scaled path).
            val scaledKeyHeight =
                if (rowHeightScale == 1f) keyHeight else (keyHeight * rowHeightScale).toInt().coerceAtLeast(1)
            val adjustedKeyHeight = (scaledKeyHeight - gutterReduction).coerceAtLeast(scaledKeyHeight / 2)
            // The touch target tracks the (scaled) key height so a shorter/taller edge row still centres
            // its key; at scale 1.0 this is the original minTarget.
            val scaledMinTarget = if (rowHeightScale == 1f) minTarget else (minTarget * rowHeightScale).toInt().coerceAtLeast(1)
            val adjustedMinTarget = (scaledMinTarget - gutterReduction).coerceAtLeast(scaledMinTarget / 2)
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

            // The voice key renders the traced mic icon (see the background chain below) — no emoji glyph.
            text = if (key is KeyboardKey.FlickKey && isVoiceKey(key)) "" else getKeyLabel(key, state)

            // Per-key font-scale override (futokxkb appearance) multiplies the resolved size; applied to the
            // button text AND the cluster/column band (both derive from finalTextSize).
            val finalTextSize =
                getCachedTextSize(adjustedKeyHeight) * (keyAppearance(key)?.fontScale ?: 1f)

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
            // Primary-glyph position offset (signed px): shift the centred label by padding the opposite
            // edges asymmetrically (+X → right, +Y → down). null/0 = unchanged symmetric padding.
            val primaryOffsetX = adaptiveDimensions?.primaryOffsetXPx ?: 0
            val primaryOffsetY = adaptiveDimensions?.primaryOffsetYPx ?: 0
            setPadding(
                horizontalPadding + primaryOffsetX,
                verticalPadding + primaryOffsetY,
                horizontalPadding - primaryOffsetX,
                verticalPadding - primaryOffsetY
            )

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
                } else if (key is KeyboardKey.FlickKey && isVoiceKey(key)) {
                    // Traced-outline mic in the label colour instead of the colour-emoji 🎙 glyph.
                    centeredIconOverlay(
                        keyBackground,
                        R.drawable.ic_mic_outline,
                        getKeyTextColor(key),
                        TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_SP,
                            finalTextSize * 0.93f,
                            context.resources.displayMetrics
                        ).toInt()
                    )
                } else if (key is KeyboardKey.FlickKey && key.clusterMains.isNotEmpty() &&
                    effectiveLayout?.showFlickHints == true
                ) {
                    if (key.columnar) {
                        createColumnBackground(keyBackground, flickFace(key, state), finalTextSize)
                    } else {
                        createClusterBackground(keyBackground, flickFace(key, state), finalTextSize)
                    }
                } else if (key is KeyboardKey.FlickKey && effectiveLayout?.showFlickHints == true) {
                    createFlickHintsBackground(keyBackground, flickFace(key, state))
                } else {
                    keyBackground
                }
            setTextColor(getKeyTextColor(key))

            isActivated = getKeyActivatedState(key, state)
            isClickable = true
            isFocusable = true
            // All long presses are handled by our own posted runnables — keep the framework's
            // long-press machinery off so OEM skins (One UI) can't pop a content-description
            // tooltip that marks the press handled and swallows the release click.
            isLongClickable = false

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

                    // Size every action icon to a fixed square (a constant fraction of the key HEIGHT, which
                    // is uniform per geometry) so shift / backspace / enter etc. render at the SAME size on
                    // every key and every layout. A fixed inset instead scaled the icon with the key's box, so
                    // a narrow/short key (or a different layout) got a visibly smaller icon than enter.
                    val iconSizePx =
                        ((adaptiveDimensions?.keyHeightPx
                            ?: (48 * context.resources.displayMetrics.density).toInt()) * 0.52f)
                            .toInt().coerceAtLeast(1)
                    val baseLayer = LayerDrawable(arrayOf(keyBackground, iconDrawable)).apply {
                        setLayerSize(1, iconSizePx, iconSizePx)
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
                setOnTouchListener(flickTouchListener(key))
            }
        }
    }

    /**
     * The touch listener of a compass/cluster/column key: it owns the whole gesture (tap, flick, hold-guide,
     * long-press strip, arrow repeat, voice hold) and fires the touch-down haptic every key gives. Exposed so
     * a test can attach it to a bare button.
     */
    @VisibleForTesting
    @SuppressLint("ClickableViewAccessibility")
    internal fun flickTouchListener(key: KeyboardKey.FlickKey): View.OnTouchListener =
        View.OnTouchListener { view, event ->
            val keyAt = { _: Float, _: Float -> view.getTag(R.id.key_data) as? KeyboardKey }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Flick keys own their whole gesture. Stop the parent keyboard view from
                    // intercepting and cancelling the flick mid-swipe — without this, certain
                    // keys (e.g. さ) had their flick cancelled on longer swipes, dropping input.
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    // The touch-down click every key gives. Flick keys replace the standard
                    // listeners (which fire it for plain character keys), so without this the
                    // compass/cluster letters — every letter on the kxkb boards — were silent while
                    // Space/Enter/Backspace clicked. The mic key's firm VoicePulse comes through the
                    // same call (the signature is chosen by key).
                    performContextualHaptic(key)
                    // The FlickPopup is this key's own (richer) preview — never stack the plain bubble on top.
                    keyPreviewPopup?.hide()
                    flickPopup?.dismiss()
                    flickPopup = null
                    flickStripActive = false
                    flickStripDownX = event.x
                    flickStripDownY = event.y
                    flickStripDownRawX = event.rawX
                    flickStripDownRawY = event.rawY
                    val arrowBinding = arrowRepeatBinding(key)
                    if (arrowBinding != null) {
                        // A held arrow key auto-repeats its motion — no compass guide, no strip.
                        scheduleArrowRepeat(arrowBinding)
                    } else if (isVoiceKey(key)) {
                        // A held mic key flips the voice language — no compass guide either.
                        scheduleVoiceHold()
                    } else {
                        // No guide flash on a plain tap: defer the compass panel to a brief hold or
                        // a flick-sized move (see scheduleFlickGuide).
                        scheduleFlickGuide(key, view)
                        scheduleFlickStrip(key, view) // arm the long-press extra-key strip
                    }
                    flickGestureDetector.handleTouchEvent(event, keyAt)
                }
                MotionEvent.ACTION_MOVE ->
                    if (flickStripActive) {
                        onFlickStripMove(event.rawX, event.rawY) // slide picks an extra
                        true
                    } else if (arrowRepeatActive) {
                        true // the repeat owns the touch; wandering neither cancels nor flicks
                    } else if (voiceHoldFired) {
                        true // the flip already happened; the release commits nothing more
                    } else {
                        // A flick-sized move means the user is flicking/swiping, not holding — drop
                        // the strip, the pending guide (the guide is hold-only; showing it mid-swipe
                        // was noise) and a pending arrow repeat or voice hold.
                        val dx = event.x - flickStripDownX
                        val dy = event.y - flickStripDownY
                        if (dx * dx + dy * dy >= flickStripSlopPx * flickStripSlopPx) {
                            cancelFlickStripTimer()
                            cancelFlickGuideTimer()
                            cancelArrowRepeat()
                            cancelVoiceHold()
                        }
                        flickGestureDetector.handleTouchEvent(event, keyAt)
                    }
                MotionEvent.ACTION_UP -> {
                    cancelFlickStripTimer()
                    cancelFlickGuideTimer()
                    val wasRepeating = arrowRepeatActive
                    cancelArrowRepeat()
                    val holdFlipped = voiceHoldFired
                    cancelVoiceHold()
                    if (flickStripActive) {
                        when (extraStripPopup?.releaseAction()) {
                            ExtraKeyStripPopup.Release.LOCK -> lockFlickStrip()
                            ExtraKeyStripPopup.Release.CANCEL -> dismissFlickStrip()
                            else -> commitFlickStrip()
                        }
                        flickGestureDetector.cancel()
                        true
                    } else if (wasRepeating || holdFlipped) {
                        true // the hold was the input; the gesture was already cancelled
                    } else {
                        flickGestureDetector.handleTouchEvent(event, keyAt)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelFlickStripTimer()
                    cancelFlickGuideTimer()
                    val wasRepeating = arrowRepeatActive
                    cancelArrowRepeat()
                    cancelVoiceHold()
                    if (flickStripActive) {
                        dismissFlickStrip()
                        flickGestureDetector.cancel()
                        true
                    } else if (wasRepeating) {
                        true
                    } else {
                        flickGestureDetector.handleTouchEvent(event, keyAt)
                    }
                }
                else -> flickGestureDetector.handleTouchEvent(event, keyAt)
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
        keyPreviewPopup?.hide()
        cancelArrowRepeat() // the held key is going away with its buttons — don't keep firing its action
        cancelVoiceHold()

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
                SpaceMenuItem(context.getString(R.string.space_menu_editor), false) { onMenuAction("editor") },
                SpaceMenuItem(context.getString(R.string.space_menu_mode), false) { onMenuAction("mode") },
                SpaceMenuItem(context.getString(R.string.space_menu_user_dictionary), false) { onMenuAction("user_dictionary") },
                SpaceMenuItem(context.getString(R.string.space_menu_learned_words), false) { onMenuAction("learned_words") },
                SpaceMenuItem(context.getString(R.string.space_menu_all_settings), false) { onMenuAction("settings") },
                SpaceMenuItem(context.getString(R.string.space_menu_export_import), false) { onMenuAction("export_import") }
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
        // Only the switcher-ACTIVE layouts (Library toggle) appear; a language with no stored set
        // shows all of them. Overlong lists spill into a "…" item — highlighting it swaps the middle
        // column for the extras, so every active layout stays reachable in one slide.
        val visibleSet = visibleLayoutsByLanguage[currentLang]
        val visibleEntries = registry.forLanguage(currentLang)
            .let { all -> visibleSet?.let { ids -> all.filter { it.id in ids } } ?: all }
        val layoutItems = visibleEntries.map { entry ->
            SpaceMenuItem(entry.name, current = false) { onSwitchToLayout(currentLang, entry.id) }
        }
        val shownLayoutItems = if (layoutItems.size > MAX_LAYOUT_MENU_ITEMS) {
            layoutItems.take(MAX_LAYOUT_MENU_ITEMS - 1) +
                SpaceMenuItem("…", current = false, overflow = layoutItems.drop(MAX_LAYOUT_MENU_ITEMS - 1)) {}
        } else {
            layoutItems
        }
        val layouts = SpaceMenuColumn(
            header = context.getString(R.string.space_menu_layouts),
            // The Layout library sits on top of the layouts column (it's about layouts), then the current
            // language's switchable variants.
            items = listOf(
                SpaceMenuItem(context.getString(R.string.space_menu_library), current = false) { onMenuAction("library") }
            ) + shownLayoutItems
        )
        return listOf(actions, languages, layouts).filter { it.items.isNotEmpty() }
    }

    /**
     * The face a flick/cluster key shows and commits given the shift state: the explicit [FlickKey.shifted]
     * variant if present (e.g. katakana), else an uppercased copy for bicameral scripts, else the key itself.
     */
    private fun flickFace(key: KeyboardKey.FlickKey, state: KeyboardState): KeyboardKey.FlickKey {
        // Keys render uppercase for both manual AND auto shift (so auto-caps shows caps, like the letter keys).
        if (!shouldCapitalize(state)) return key
        // Multi-state `case`: the face matching the precise shift state wins; else the generic shifted face.
        val precise = when {
            state.isCapsLockOn -> key.caseFaces["shiftLocked"]
            state.isAutoShift -> key.caseFaces["shifted"]
            else -> key.caseFaces["shiftedManually"]
        }
        (precise ?: key.shifted)?.let { return it }
        // `shiftable: false` opts this cluster/column key out of auto-uppercasing (e.g. a fixed-symbol band).
        if (key.attributes?.shiftable == false) return key
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

    /** Extract the letters layout's cluster bands (centre char -> band, e.g. 'w' -> "mwk") for prediction. */
    private fun publishClusterBands(layout: KeyboardLayout) {
        val bands = mutableMapOf<Char, String>()
        var hasFlatLetters = false
        for (row in layout.rows) {
            for (key in row) {
                if (key is KeyboardKey.FlickKey && key.clusterMains.length > 1) {
                    key.center.firstOrNull()?.let { bands[it] = key.clusterMains }
                }
                if (key is KeyboardKey.Character && key.type == KeyboardKey.KeyType.LETTER) {
                    hasFlatLetters = true
                }
            }
        }
        onClusterBands(bands, hasFlatLetters)
    }

    /** Cycle to the next active layout language (globe-key tap + interim space long-press). */
    fun cycleToNextLanguage() {
        val nextLang = languageManager.getNextLayoutLanguage()
        onLanguageSwitch(nextLang)
        val displayName =
            com.urik.keyboard.settings.KeyboardSettings.getLanguageDisplayNames()[nextLang] ?: nextLang
        com.urik.keyboard.utils.KxkbToast.show(context, displayName)
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

                KeyboardKey.ActionType.MODE_SWITCH_NUMPAD -> {
                    context.getString(R.string.numpad_mode_label)
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

                KeyboardKey.ActionType.MODE_SWITCH_NUMPAD -> {
                    context.getString(R.string.numpad_mode_description)
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

    private fun handleSpaceLongPress(view: View): Boolean {
        // Cluster typing: let the host insert a literal space (the escape from committing a candidate).
        if (onSpaceLongPress()) {
            performContextualHaptic(KeyboardKey.Action(KeyboardKey.ActionType.SPACE))
            return true
        }

        if (longPressPunctuationMode != LongPressPunctuationMode.SPACEBAR) {
            // Nothing else applies: commit the space HERE and consume. Leaving the long press
            // unconsumed hands it to the framework, where OEM decorations (One UI's
            // content-description tooltip — the "Mezerník" flash) mark it handled and swallow
            // the release click, so the key silently did nothing on Samsung.
            performContextualHaptic(KeyboardKey.Action(KeyboardKey.ActionType.SPACE))
            onKeyClick(KeyboardKey.Action(KeyboardKey.ActionType.SPACE))
            return true
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
        return true
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

    /** The per-key appearance overrides (futokxkb), or null to inherit theme / look-knob colours + sizes. */
    private fun keyAppearance(key: KeyboardKey): KeyAppearance? = key.appearance

    /**
     * A functional (non-letter) key — shift / backspace / enter / space / mode-switch — i.e. any
     * [KeyboardKey.Action], or any key whose futokxkb `style` attribute is "Action". Letter / cluster /
     * column / character keys are NOT functional. Drives the optional functional-key background colour.
     */
    private fun isFunctionalKey(key: KeyboardKey): Boolean =
        key is KeyboardKey.Action ||
            key.attributes?.style.equals("Action", ignoreCase = true) ||
            // A compass/flick key whose CENTRE tap is a non-text binding is functional too — e.g. the column
            // layout's Enter (tap = FlickBinding.Action("enter")) or a symbols/mode Layer key. Letter, cluster
            // and column keys commit a plain character on tap (no "center" binding), so they stay non-functional.
            (key is KeyboardKey.FlickKey && key.bindings.containsKey("center"))

    private fun getKeyBackground(key: KeyboardKey): Drawable {
        ensureCacheValid()
        val theme = themeManager.currentTheme.value
        val app = keyAppearance(key)
        // Functional keys may carry a separate background colour (null = inherit the normal key-bg override).
        val keyBgOverride = adaptiveDimensions?.keyBgColor
        val functionalBgOverride =
            if (isFunctionalKey(key)) adaptiveDimensions?.functionalKeyBgColor ?: keyBgOverride else keyBgOverride
        // Per-key appearance wins, then the per-geometry colour overrides (null = use the theme colour).
        val bgOverride = app?.backgroundColor ?: functionalBgOverride
        val borderColor =
            app?.borderColor ?: adaptiveDimensions?.keyBorderColor ?: theme.colors.keyBorder

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
        keyAppearance(key)?.color?.let { return it }
        val colors = themeManager.currentTheme.value.colors
        val override = adaptiveDimensions?.keyTextColor
        return when (key) {
            is KeyboardKey.Character -> override ?: colors.keyTextCharacter
            is KeyboardKey.Action -> override ?: colors.keyTextAction
            is KeyboardKey.FlickKey -> override ?: colors.keyTextCharacter
            KeyboardKey.Spacer -> android.graphics.Color.TRANSPARENT
        }
    }

    /** An icon drawn centred on the key face, tinted like a label (the voice key's traced mic). */
    private fun centeredIconOverlay(base: Drawable, iconRes: Int, tint: Int, sizePx: Int): Drawable {
        val icon = ContextCompat.getDrawable(context, iconRes)?.mutate() ?: return base
        icon.setTint(tint)
        return LayerDrawable(arrayOf(base, icon)).apply {
            setLayerSize(1, sizePx, sizePx)
            setLayerGravity(1, Gravity.CENTER)
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
        keyPreviewPopup?.hide()
        keyPreviewPopup = null
        punctuationLoader.cleanup()
    }

    companion object {
        private const val STANDARD_KEY_WEIGHT = 1f
        private const val SHIFT_KEY_WEIGHT = 1.5f
        private const val BACKSPACE_KEY_WEIGHT = 1.5f
        private const val MAX_BUTTON_POOL_SIZE = 40
        private val ARROW_ACTION_NAMES = setOf("arrow_up", "arrow_down", "arrow_left", "arrow_right")

        /** Layouts shown directly in the space-menu column (the rest spill behind "…"). */
        private const val MAX_LAYOUT_MENU_ITEMS = 6
        private const val ARROW_REPEAT_INTERVAL_MS = 50L
    }
}
