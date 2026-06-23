package com.urik.keyboard.ui.keyboard.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** The size knobs a resize session edits: key-height scale, width fraction, bottom-lift dp, split fraction. */
data class ResizeValues(
    val heightScale: Float,
    val widthScale: Float,
    val bottomLiftDp: Float,
    val splitFraction: Float = 0f
)

/**
 * Seamless on-keyboard resize (futokxkb-style: long-press the keyboard's top-left corner, keep sliding the
 * SAME finger, release to save + exit). Hosted as the TOP child of [SwipeKeyboardView] so its bounds are the
 * keyboard itself (not the lifted container) and it owns the primary finger's DOWN before any key sees it.
 *
 * **Nothing is drawn while idle** — the top-left grip is an invisible hot-zone. A long-press there activates:
 * a faint dim + hot-point dots appear and the same finger resizes — vertical = height, horizontal = width
 * (drag left = wider). A second finger in the bottom-right zone adjusts bottom-lift (vertical) + width
 * (horizontal, drag right = wider). Releasing the PRIMARY finger commits + exits. (Centre = split deferred.)
 *
 * **Deltas use SCREEN-absolute coordinates**, not view-local: growing the height reflows the (bottom-anchored)
 * keyboard upward under the finger, so a view-local delta would feed back and oscillate. Screen coords track
 * the finger's true motion; dots are drawn at the local position so they still sit under the finger.
 */
class ResizeOverlayView(context: Context) : View(context) {
    var onBegin: (() -> ResizeValues)? = null
    var onApply: ((ResizeValues) -> Unit)? = null
    var onCommit: ((ResizeValues) -> Unit)? = null
    var onHaptic: (() -> Unit)? = null
    var longPressMs: Long = 400L

    var handleColor: Int = 0xFFFFFF00.toInt()
        set(value) { field = value; dotFill.color = value; dotRing.color = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val gripSizePx = 56f * density
    private val dotRadiusPx = 26f * density
    private val touchSlopPx = 12f * density

    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = handleColor; alpha = 90 }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = handleColor
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val dimPaint = Paint().apply { color = 0x55000000 }

    private var active = false

    private var primaryId = -1
    private var primaryStartScreenX = 0f
    private var primaryStartScreenY = 0f
    private var primaryPrevScreenX = 0f
    private var primaryPrevScreenY = 0f
    private var primaryDrawX = 0f
    private var primaryDrawY = 0f
    private var downInGrip = false
    private var movedBeforeActivate = false

    private var liftId = -1
    private var liftEverGrabbed = false
    private var liftPrevScreenX = 0f
    private var liftPrevScreenY = 0f
    private var liftDrawX = 0f
    private var liftDrawY = 0f

    // Split finger: a second finger grabbed in the centre column adjusts the split fraction by horizontal drag
    // (right = wider split). The split dot(s) are drawn whenever a resize is active so the grab point is visible.
    private var splitId = -1
    private var splitPrevScreenX = 0f
    private var splitDrawY = 0f
    /** Pixels of gap at split fraction 1.0 — set by the host so the dots sit on the real split edges. */
    var maxSplitPx = 0f

    private var cur = ResizeValues(1f, 1f, 0f, 0f)
    // The keyboard's unscaled height in px, captured at grab — the 1:1 reference so the top tracks the finger.
    private var baseHeightPx = 1f

    private val activateRunnable = Runnable { activate() }

    init {
        setWillNotDraw(false)
    }

    // Screen-absolute coords for any pointer, via index-0's raw/local offset (works pre-API-29 multitouch).
    private fun screenX(event: MotionEvent, index: Int) = event.getX(index) + (event.rawX - event.getX(0))

    private fun screenY(event: MotionEvent, index: Int) = event.getY(index) + (event.rawY - event.getY(0))

    private fun inGrip(x: Float, y: Float) = x <= gripSizePx && y <= gripSizePx

    private fun inBottomRight(x: Float, y: Float) =
        x >= width - 3f * gripSizePx && y >= height - 3f * gripSizePx

    /**
     * The split grab zone: spans from the centre out to (and a grip past) each split-edge dot, so grabbing
     * either dot works — not just the dark gap between them.
     */
    private fun inCenter(x: Float, @Suppress("UNUSED_PARAMETER") y: Float): Boolean {
        val gap = cur.splitFraction * maxSplitPx
        return abs(x - width / 2f) <= gap / 2f + 1.5f * gripSizePx
    }

    private fun activate() {
        if (!downInGrip || movedBeforeActivate) return
        cur = onBegin?.invoke() ?: ResizeValues(1f, 1f, 0f)
        baseHeightPx = (height / cur.heightScale.coerceAtLeast(0.1f)).coerceAtLeast(1f)
        active = true
        onHaptic?.invoke()
        invalidate()
    }

    private fun deactivate(commit: Boolean) {
        removeCallbacks(activateRunnable)
        if (active && commit) onCommit?.invoke(cur)
        active = false
        primaryId = -1
        liftId = -1
        liftEverGrabbed = false
        splitId = -1
        downInGrip = false
        movedBeforeActivate = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inGrip(event.x, event.y)) return false // let keys / suggestions handle it
                downInGrip = true
                movedBeforeActivate = false
                primaryId = event.getPointerId(0)
                primaryStartScreenX = event.rawX
                primaryStartScreenY = event.rawY
                primaryPrevScreenX = event.rawX
                primaryPrevScreenY = event.rawY
                primaryDrawX = event.x
                primaryDrawY = event.y
                postDelayed(activateRunnable, longPressMs)
                // Own the gesture for the whole grip interaction: stop the parent (SwipeKeyboardView) from
                // re-intercepting MOVEs — otherwise its onInterceptTouchEvent resolves the grip's corner DOWN
                // to the (nearest) Space key and a drag opens the space-slide menu mid-resize.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!active) return true
                val idx = event.actionIndex
                val px = event.getX(idx)
                val py = event.getY(idx)
                if (liftId == -1 && inBottomRight(px, py)) {
                    liftId = event.getPointerId(idx)
                    liftEverGrabbed = true
                    liftPrevScreenX = screenX(event, idx)
                    liftPrevScreenY = screenY(event, idx)
                    liftDrawX = px
                    liftDrawY = py
                    onHaptic?.invoke()
                } else if (splitId == -1 && inCenter(px, py)) {
                    splitId = event.getPointerId(idx)
                    splitPrevScreenX = screenX(event, idx)
                    splitDrawY = py
                    onHaptic?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!downInGrip) return false
                val refW = width.coerceAtLeast(1)
                val pIdx = event.findPointerIndex(primaryId)
                if (pIdx >= 0) {
                    val sx = screenX(event, pIdx)
                    val sy = screenY(event, pIdx)
                    primaryDrawX = event.getX(pIdx)
                    primaryDrawY = event.getY(pIdx)
                    if (!active) {
                        if (abs(sx - primaryStartScreenX) > touchSlopPx || abs(sy - primaryStartScreenY) > touchSlopPx) {
                            movedBeforeActivate = true
                            removeCallbacks(activateRunnable)
                        }
                    } else {
                        // 1:1 with the finger: dragging up by N px grows the keyboard by N px (top tracks finger).
                        cur = cur.copy(
                            heightScale = (cur.heightScale + (primaryPrevScreenY - sy) / baseHeightPx).coerceIn(0.5f, 5.0f),
                            widthScale = (cur.widthScale + (primaryPrevScreenX - sx) / refW).coerceIn(0.5f, 1.0f)
                        )
                    }
                    primaryPrevScreenX = sx
                    primaryPrevScreenY = sy
                }
                if (active && liftId != -1) {
                    val lIdx = event.findPointerIndex(liftId)
                    if (lIdx >= 0) {
                        val lsx = screenX(event, lIdx)
                        val lsy = screenY(event, lIdx)
                        liftDrawX = event.getX(lIdx)
                        liftDrawY = event.getY(lIdx)
                        // Resize from the BOTTOM: raising the bottom edge by N px adds N px of bottom padding
                        // AND removes N px of height, so the TOP edge stays where the primary finger holds it.
                        // Height is coupled to the ACTUAL (clamped) lift change, so once the bottom is docked
                        // (lift = 0) dragging further down does nothing — the top never grows past the finger.
                        val dyScreen = liftPrevScreenY - lsy
                        val oldLift = cur.bottomLiftDp
                        val newLift = (oldLift + dyScreen / density).coerceIn(0f, 240f)
                        val liftDeltaPx = (newLift - oldLift) * density
                        cur = cur.copy(
                            bottomLiftDp = newLift,
                            heightScale = (cur.heightScale - liftDeltaPx / baseHeightPx).coerceIn(0.5f, 5.0f),
                            widthScale = (cur.widthScale + (lsx - liftPrevScreenX) / refW).coerceIn(0.5f, 1.0f)
                        )
                        liftPrevScreenX = lsx
                        liftPrevScreenY = lsy
                    }
                }
                if (active && splitId != -1) {
                    val sIdx = event.findPointerIndex(splitId)
                    if (sIdx >= 0) {
                        val ssx = screenX(event, sIdx)
                        splitDrawY = event.getY(sIdx)
                        // Horizontal drag: right widens, left narrows; a half-width drag spans 0 → full split.
                        cur = cur.copy(
                            splitFraction =
                                (cur.splitFraction + (ssx - splitPrevScreenX) / (refW * 0.5f)).coerceIn(0f, 1f)
                        )
                        splitPrevScreenX = ssx
                    }
                }
                if (active) {
                    onApply?.invoke(cur)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == liftId) {
                    liftId = -1
                } else if (id == splitId) {
                    splitId = -1
                } else if (id == primaryId) {
                    deactivate(commit = true)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                deactivate(commit = active)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                deactivate(commit = false)
                return true
            }
        }
        return downInGrip
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return // idle: invisible hot-zone, nothing drawn
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        dot(canvas, primaryDrawX, primaryDrawY)
        // Once the bottom finger has grabbed, the dot stays where it was left (don't snap back to the
        // corner) — so there's no phantom higher dot to drag down again.
        if (liftEverGrabbed) {
            dot(canvas, liftDrawX, liftDrawY)
        } else {
            dot(canvas, width - 1.6f * gripSizePx, height - 1.6f * gripSizePx)
        }
        // Split handle: a single centre dot when un-split, else a dot on each split edge (drag right to widen).
        val gap = cur.splitFraction * maxSplitPx
        val scy = if (splitId != -1) splitDrawY else height / 2f
        if (gap < 4f) {
            dot(canvas, width / 2f, scy)
        } else {
            dot(canvas, width / 2f - gap / 2f, scy)
            dot(canvas, width / 2f + gap / 2f, scy)
        }
    }

    private fun dot(canvas: Canvas, cx: Float, cy: Float) {
        // Keep the whole dot on the keyboard even when the finger is at the very edge.
        val x = cx.coerceIn(dotRadiusPx, (width - dotRadiusPx).coerceAtLeast(dotRadiusPx))
        val y = cy.coerceIn(dotRadiusPx, (height - dotRadiusPx).coerceAtLeast(dotRadiusPx))
        canvas.drawCircle(x, y, dotRadiusPx, dotFill)
        canvas.drawCircle(x, y, dotRadiusPx, dotRing)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(activateRunnable)
    }
}
