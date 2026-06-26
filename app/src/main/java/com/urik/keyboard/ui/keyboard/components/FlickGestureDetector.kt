package com.urik.keyboard.ui.keyboard.components

import android.view.MotionEvent
import com.urik.keyboard.model.KeyboardKey
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

    private var activeKey: KeyboardKey.FlickKey? = null
    private var originX = 0f
    private var originY = 0f
    private var downTime = 0L
    private var currentDirection = FlickDirection.NONE
    private var directionLocked = false

    private val flickCommitPx get() = FLICK_COMMIT_DP * density
    private val directionLockPx get() = DIRECTION_LOCK_DP * density

    fun setFlickListener(listener: FlickListener?) {
        this.listener = listener
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
        val dx = event.x - originX
        val dy = event.y - originY
        val dist = sqrt(dx * dx + dy * dy)

        if (!directionLocked) {
            val newDir = if (dist >= flickCommitPx) computeDirection(dx, dy) else FlickDirection.NONE
            if (newDir != currentDirection) {
                currentDirection = newDir
                listener?.onFlickDirectionChanged(key, currentDirection)
            }
            if (dist >= directionLockPx) directionLocked = true
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

        val dx = event.x - originX
        val dy = event.y - originY
        val dist = sqrt(dx * dx + dy * dy)
        val direction = if (dist >= flickCommitPx) computeDirection(dx, dy) else FlickDirection.NONE
        val finalDir = if (directionLocked) currentDirection else direction

        listener?.onFlickCommit(key, finalDir)
        reset()
        return true
    }

    private fun computeDirection(dx: Float, dy: Float): FlickDirection {
        // atan2(dy, dx) in screen coords: 0° = right, 90° = down, -90° = up, ±180° = left.
        // Eight 45°-wide sectors centred on each direction.
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        return when {
            angle < -157.5 || angle >= 157.5 -> FlickDirection.LEFT
            angle < -112.5 -> FlickDirection.UP_LEFT
            angle < -67.5 -> FlickDirection.UP
            angle < -22.5 -> FlickDirection.UP_RIGHT
            angle < 22.5 -> FlickDirection.RIGHT
            angle < 67.5 -> FlickDirection.DOWN_RIGHT
            angle < 112.5 -> FlickDirection.DOWN
            else -> FlickDirection.DOWN_LEFT
        }
    }

    private fun reset() {
        activeKey = null
        originX = 0f
        originY = 0f
        downTime = 0L
        currentDirection = FlickDirection.NONE
        directionLocked = false
    }

    private companion object {
        const val FLICK_COMMIT_DP = 20f
        const val DIRECTION_LOCK_DP = 30f
        const val TAP_TIMEOUT_MS = 300L
    }
}
