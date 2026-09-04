package com.urik.keyboard.ui.keyboard.components

import android.view.MotionEvent
import com.urik.keyboard.model.KeyboardKey
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class FlickGestureDetector {
    enum class FlickDirection {
        NONE, UP, DOWN, LEFT, RIGHT,
        UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT
    }

    interface FlickListener {
        fun onFlickStart(key: KeyboardKey.FlickKey, anchorX: Float, anchorY: Float)
        fun onFlickDirectionChanged(key: KeyboardKey.FlickKey, direction: FlickDirection)
        fun onFlickCommit(key: KeyboardKey.FlickKey, direction: FlickDirection)
        fun onFlickCancel()
        fun onActionTap(key: KeyboardKey.Action)
    }

    private var listener: FlickListener? = null
    private var density = 1f

    /**
     * Which compass positions the key under the finger actually carries. The host supplies this — it alone
     * resolves the shifted face and the non-text bindings. Default: all eight, i.e. the plain 45° sectors.
     */
    private var populatedDirections: (KeyboardKey.FlickKey) -> Set<FlickDirection> = { ALL_DIRECTIONS }

    private var activeKey: KeyboardKey.FlickKey? = null
    private var originX = 0f
    private var originY = 0f
    private var downTime = 0L
    private var currentDirection = FlickDirection.NONE

    private val flickCommitPx get() = FLICK_COMMIT_DP * density

    fun setFlickListener(listener: FlickListener?) {
        this.listener = listener
    }

    /** Teach the detector which directions a given key carries — see [populatedDirections]. */
    fun setPopulatedDirectionsProvider(provider: (KeyboardKey.FlickKey) -> Set<FlickDirection>) {
        populatedDirections = provider
    }

    /**
     * Abandon the in-progress gesture WITHOUT committing — used when a long-press hands the touch off to the
     * extra-key strip, so the subsequent UP doesn't also fire a flick commit. No listener callback.
     */
    fun cancel() {
        reset()
    }

    fun updateDisplayMetrics(density: Float) {
        this.density = density
    }

    fun handleTouchEvent(event: MotionEvent, keyAt: (Float, Float) -> KeyboardKey?): Boolean = when (event.action) {
        MotionEvent.ACTION_DOWN -> onDown(event, keyAt)
        MotionEvent.ACTION_MOVE -> onMove(event)
        MotionEvent.ACTION_UP -> onUp(event, keyAt)
        MotionEvent.ACTION_CANCEL -> {
            reset()
            listener?.onFlickCancel()
            true
        }
        else -> false
    }

    private fun onDown(event: MotionEvent, keyAt: (Float, Float) -> KeyboardKey?): Boolean {
        reset()
        val key = keyAt(event.x, event.y) ?: return false
        return when (key) {
            is KeyboardKey.FlickKey -> {
                activeKey = key
                originX = event.x
                originY = event.y
                downTime = System.currentTimeMillis()
                listener?.onFlickStart(key, event.x, event.y)
                true
            }
            is KeyboardKey.Action -> {
                activeKey = null
                downTime = System.currentTimeMillis()
                true
            }
            else -> false
        }
    }

    private fun onMove(event: MotionEvent): Boolean {
        val key = activeKey ?: return false
        val newDir = directionAt(key, event.x - originX, event.y - originY)
        if (newDir != currentDirection) {
            currentDirection = newDir
            listener?.onFlickDirectionChanged(key, currentDirection)
        }
        return true
    }

    private fun onUp(event: MotionEvent, keyAt: (Float, Float) -> KeyboardKey?): Boolean {
        val key = activeKey
        if (key == null) {
            val duration = System.currentTimeMillis() - downTime
            if (duration <= TAP_TIMEOUT_MS) {
                val tapped = keyAt(event.x, event.y)
                if (tapped is KeyboardKey.Action) listener?.onActionTap(tapped)
            }
            reset()
            return true
        }

        // Commit where the finger actually ENDED, never a direction frozen early in the gesture: the first
        // millimetres of a swipe are its noisiest part, and freezing there turned a left flick that set off
        // with a little upward drift into UP_LEFT however straight the rest of it ran. What the compass
        // guide highlights is now always what the release commits.
        val direction = directionAt(key, event.x - originX, event.y - originY)

        listener?.onFlickCommit(key, direction)
        reset()
        return true
    }

    private fun directionAt(key: KeyboardKey.FlickKey, dx: Float, dy: Float): FlickDirection {
        val dist = sqrt(dx * dx + dy * dy)
        return if (dist >= flickCommitPx) computeDirection(key, dx, dy) else FlickDirection.NONE
    }

    /**
     * Resolve the swipe angle against the directions the key REALLY carries, rather than eight fixed
     * 45°-wide sectors.
     *
     * A cluster key holds its band on left/right (letters) and typically a number on up with the diagonals
     * empty. Under fixed sectors a left flick had to stay within 22.5° of horizontal, and anything beyond
     * that landed in UP_LEFT — whose empty diagonal then fell back to `up`, so the key typed its number
     * instead of the band letter. Snapping to the nearest POPULATED direction gives each of the four
     * cardinals a full 90° sector on such a key: the boundary between "left" and "up" sits where it
     * belongs, on the diagonal.
     *
     * With all eight positions populated (a full compass key) this is exactly the old 45° split.
     */
    private fun computeDirection(key: KeyboardKey.FlickKey, dx: Float, dy: Float): FlickDirection {
        // atan2(dy, dx) in screen coords: 0° = right, 90° = down, -90° = up, ±180° = left.
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        val populated = populatedDirections(key)
        if (populated.isEmpty() || populated.size == ALL_DIRECTIONS.size) return sectorDirection(angle)
        val nearest = DIRECTION_ANGLES
            .filter { it.first in populated }
            .minByOrNull { angularDistance(angle, it.second) }
            ?: return sectorDirection(angle)
        // Cap the snap: a direction the key does not carry must not swallow a gesture aimed elsewhere
        // entirely — a straight-down flick on a key with nothing below it stays DOWN (and so commits the
        // centre character), instead of being dragged sideways into the band.
        return if (angularDistance(angle, nearest.second) <= MAX_SNAP_DEGREES) nearest.first
        else sectorDirection(angle)
    }

    /** The plain eight 45°-wide sectors, centred on each direction. */
    private fun sectorDirection(angle: Double): FlickDirection = when {
        angle < -157.5 || angle >= 157.5 -> FlickDirection.LEFT
        angle < -112.5 -> FlickDirection.UP_LEFT
        angle < -67.5 -> FlickDirection.UP
        angle < -22.5 -> FlickDirection.UP_RIGHT
        angle < 22.5 -> FlickDirection.RIGHT
        angle < 67.5 -> FlickDirection.DOWN_RIGHT
        angle < 112.5 -> FlickDirection.DOWN
        else -> FlickDirection.DOWN_LEFT
    }

    private fun angularDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun reset() {
        activeKey = null
        originX = 0f
        originY = 0f
        downTime = 0L
        currentDirection = FlickDirection.NONE
    }

    private companion object {
        const val FLICK_COMMIT_DP = 20f
        const val TAP_TIMEOUT_MS = 300L

        /** How far a gesture may sit off a populated direction and still snap to it. */
        const val MAX_SNAP_DEGREES = 67.5

        /** Cardinals first, so an exact tie between a cardinal and a diagonal resolves to the cardinal. */
        val DIRECTION_ANGLES = listOf(
            FlickDirection.RIGHT to 0.0,
            FlickDirection.DOWN to 90.0,
            FlickDirection.LEFT to 180.0,
            FlickDirection.UP to -90.0,
            FlickDirection.DOWN_RIGHT to 45.0,
            FlickDirection.DOWN_LEFT to 135.0,
            FlickDirection.UP_LEFT to -135.0,
            FlickDirection.UP_RIGHT to -45.0
        )

        val ALL_DIRECTIONS: Set<FlickDirection> = DIRECTION_ANGLES.map { it.first }.toSet()
    }
}
