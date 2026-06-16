package com.urik.keyboard.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.annotation.VisibleForTesting
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.urik.keyboard.utils.ErrorLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class DeviceSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

enum class DevicePosture {
    NORMAL,
    FLAT,
    HALF_OPENED
}

/**
 * The device's discrete fold state. On a tri-fold (白い熊's Mate XT) Jetpack's [FoldingFeature] is absent
 * and its single `hinge_angle` sensor can't tell semi-folded from unfolded — so this is derived from the
 * Huawei HALL sensor (its `values[0]` bitmask: popcount = number of closed hinges) or, where there is no
 * HALL sensor, from screen-area tiers. A non-foldable phone is always [FOLDED] (its one screen).
 */
enum class FoldState {
    FOLDED,
    SEMI_FOLDED,
    UNFOLDED
}

data class PostureInfo(
    val sizeClass: DeviceSizeClass,
    val posture: DevicePosture,
    val hingeBounds: Rect? = null,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val isTablet: Boolean = false,
    val orientation: Int = Configuration.ORIENTATION_PORTRAIT,
    val foldState: FoldState = FoldState.FOLDED
)

class PostureDetector(private val context: Context, private val scope: CoroutineScope) {
    private var windowInfoTracker: WindowInfoTracker? = null
    private var windowContext: Context? = null

    private val _postureInfo = MutableStateFlow(getCurrentPostureInfo())
    val postureInfo: StateFlow<PostureInfo> = _postureInfo.asStateFlow()

    private var collectJob: Job? = null
    private var debounceJob: Job? = null
    private var fallbackJob: Job? = null

    private val sensorManager: SensorManager? by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    // The Huawei HALL sensor (no permission); null on devices without it (→ screen-area fallback).
    private val hallSensor: Sensor? by lazy {
        sensorManager?.getSensorList(Sensor.TYPE_ALL)?.firstOrNull { it.stringType == "android.sensor.hall" }
    }
    @Volatile private var lastHallValue: Int? = null
    private var hallRegistered = false
    private val hallListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val value = event.values.firstOrNull()?.toInt() ?: return
                if (value == lastHallValue) return
                lastHallValue = value
                // The fold changed — re-emit posture immediately so the geometry follows live, even if
                // the IME never receives an onConfigurationChanged for the fold.
                _postureInfo.value = getCurrentPostureInfo()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

    private fun registerHallListener() {
        if (hallRegistered) return
        val sm = sensorManager ?: return
        val sensor = hallSensor ?: return
        try {
            sm.registerListener(hallListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            hallRegistered = true
        } catch (e: Exception) {
            // best-effort; falls back to screen-area classification
        }
    }

    private fun unregisterHallListener() {
        if (!hallRegistered) return
        try {
            sensorManager?.unregisterListener(hallListener)
        } catch (e: Exception) {
            // ignore
        }
        hallRegistered = false
    }

    /**
     * Classify the fold state. Prefer the HALL bitmask (popcount = closed hinges: 2→folded, 1→semi,
     * 0→unfolded — race-free, the code rides the event); fall back to screen-area tiers (calibrated to
     * the Mate XT but sensible as size tiers anywhere) where there's no HALL sensor.
     */
    private fun classifyFold(widthDp: Int, heightDp: Int): FoldState {
        lastHallValue?.let { hall ->
            return when (Integer.bitCount(hall)) {
                0 -> FoldState.UNFOLDED
                1 -> FoldState.SEMI_FOLDED
                else -> FoldState.FOLDED
            }
        }
        val areaKDp2 = (widthDp.toLong() * heightDp.toLong()) / 1000
        return when {
            areaKDp2 < AREA_FOLDED_MAX_KDP2 -> FoldState.FOLDED
            areaKDp2 < AREA_SEMI_MAX_KDP2 -> FoldState.SEMI_FOLDED
            else -> FoldState.UNFOLDED
        }
    }

    fun attachToWindow(windowCtx: Context) {
        windowContext = windowCtx
        registerHallListener()
        _postureInfo.value = getCurrentPostureInfo()
        try {
            windowInfoTracker = WindowInfoTracker.getOrCreate(windowCtx)
            fallbackJob?.cancel()
            fallbackJob = null
            startWindowInfoCollection()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "PostureDetector",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "attachToWindow")
            )
            ensureFallbackPolling()
        }
    }

    fun start() {
        registerHallListener()
        ensureFallbackPolling()
    }

    private fun startWindowInfoCollection() {
        collectJob?.cancel()

        val ctx = windowContext ?: return

        collectJob =
            scope.launch {
                windowInfoTracker?.windowLayoutInfo(ctx)?.collectLatest { layoutInfo ->
                    debounceJob?.cancel()
                    debounceJob =
                        launch {
                            delay(DEBOUNCE_MS)
                            updatePostureFromLayoutInfo(layoutInfo)
                        }
                }
            }
    }

    private fun ensureFallbackPolling() {
        if (fallbackJob?.isActive == true) return

        fallbackJob =
            scope.launch {
                while (true) {
                    _postureInfo.value = getCurrentPostureInfo()
                    delay(FALLBACK_POLL_MS)
                }
            }
    }

    fun onConfigurationChanged() {
        _postureInfo.value = getCurrentPostureInfo()
    }

    fun stop() {
        collectJob?.cancel()
        fallbackJob?.cancel()
        debounceJob?.cancel()
        collectJob = null
        fallbackJob = null
        debounceJob = null
        windowInfoTracker = null
        windowContext = null
        unregisterHallListener()
    }

    @VisibleForTesting
    internal fun updatePostureFromLayoutInfo(layoutInfo: androidx.window.layout.WindowLayoutInfo) {
        val effectiveContext = windowContext ?: context
        val displayMetrics = effectiveContext.resources.displayMetrics
        val widthPx = displayMetrics.widthPixels
        val heightPx = displayMetrics.heightPixels
        val density = displayMetrics.density

        val widthDp = (widthPx / density).toInt()
        val heightDp = (heightPx / density).toInt()
        val smallestWidthDp = minOf(widthDp, heightDp)

        val sizeClass =
            when {
                widthDp < COMPACT_WIDTH_DP -> DeviceSizeClass.COMPACT
                widthDp < MEDIUM_WIDTH_DP -> DeviceSizeClass.MEDIUM
                else -> DeviceSizeClass.EXPANDED
            }

        val isTablet = smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP

        val foldingFeature = layoutInfo.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()

        val posture =
            when {
                foldingFeature == null -> DevicePosture.NORMAL
                foldingFeature.state == FoldingFeature.State.FLAT -> DevicePosture.FLAT
                foldingFeature.state == FoldingFeature.State.HALF_OPENED -> DevicePosture.HALF_OPENED
                else -> DevicePosture.NORMAL
            }

        val hingeBounds =
            if (foldingFeature != null && foldingFeature.orientation == FoldingFeature.Orientation.VERTICAL) {
                foldingFeature.bounds
            } else {
                null
            }

        _postureInfo.value =
            PostureInfo(
                sizeClass = sizeClass,
                posture = posture,
                hingeBounds = hingeBounds,
                screenWidthPx = widthPx,
                screenHeightPx = heightPx,
                isTablet = isTablet,
                orientation = effectiveContext.resources.configuration.orientation,
                foldState = classifyFold(widthDp, heightDp)
            )
    }

    private fun getCurrentPostureInfo(ctx: Context = windowContext ?: context): PostureInfo {
        val displayMetrics = ctx.resources.displayMetrics
        val widthPx = displayMetrics.widthPixels
        val heightPx = displayMetrics.heightPixels
        val density = displayMetrics.density

        val widthDp = (widthPx / density).toInt()
        val heightDp = (heightPx / density).toInt()
        val smallestWidthDp = minOf(widthDp, heightDp)

        val sizeClass =
            when {
                widthDp < COMPACT_WIDTH_DP -> DeviceSizeClass.COMPACT
                widthDp < MEDIUM_WIDTH_DP -> DeviceSizeClass.MEDIUM
                else -> DeviceSizeClass.EXPANDED
            }

        val isTablet = smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP

        return PostureInfo(
            sizeClass = sizeClass,
            posture = DevicePosture.NORMAL,
            hingeBounds = null,
            screenWidthPx = widthPx,
            screenHeightPx = heightPx,
            isTablet = isTablet,
            orientation = ctx.resources.configuration.orientation,
            foldState = classifyFold(widthDp, heightDp)
        )
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
        const val FALLBACK_POLL_MS = 2000L
        const val COMPACT_WIDTH_DP = 600
        const val MEDIUM_WIDTH_DP = 840
        const val TABLET_SMALLEST_WIDTH_DP = 600

        // Screen-area fold tiers in 1000·dp² (fallback when there's no HALL sensor). Calibrated to the
        // Mate XT: folded ≈378, semi ≈769, unfolded ≈1196 — thresholds sit in the wide gaps between.
        const val AREA_FOLDED_MAX_KDP2 = 570L
        const val AREA_SEMI_MAX_KDP2 = 980L
    }
}
