package com.strongholddroid.emulator.graphics

import android.util.Log
import android.view.Choreographer
import com.strongholddroid.emulator.emulator.EmulatorConfig
import com.strongholddroid.emulator.emulator.GraphicsBackend
import com.strongholddroid.emulator.performance.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Frame-rate sampler + render-target scaler.
 *
 * This file is THE place where the dynamic-resolution algorithm lives.
 * It runs alongside [PerformanceMonitor] (which provides the frame timing)
 * and reacts to:
 *   • Rolling-average FPS dropping below [targetFps - 3] for >2 s  → scale DOWN
 *   • Rolling-average FPS climbing above [targetFps + 10] for >4 s → scale UP
 *   • Thermal throttling events from [com.strongholddroid.emulator.performance.ThermalManager]
 *     → immediately scale to [minScale] for 30 s
 *
 * The scale is communicated back to the DXVK runtime through a small
 * mmap'd file (render_target.json) that our DXVK fork polls every
 * present. This avoids touching Wine's swapchain logic.
 *
 * Algorithm: PID-style with proportional + deadband. Avoids oscillation
 * that's typical for naïve step-based scalers.
 */
class DynamicResolutionScaler(
    private val cfg: GraphicsBackend.DynamicResolution,
    private val baseWidth: Int,
    private val baseHeight: Int,
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    private val _scale = MutableStateFlow(1.0f)
    val scale: StateFlow<Float> = _scale.asStateFlow()

    private val fpsWindow = ArrayDeque<Float>(WINDOW_SIZE)
    private var lastScaleChangeMs: Long = 0
    @Volatile private var throttleUntilMs: Long = 0

    fun start(perf: PerformanceMonitor) {
        if (!cfg.enabled) return
        job = scope.launch {
            val target = cfg.targetFps.toFloat()
            while (true) {
                val fps = perf.rollingFps()
                pushFps(fps)
                val avg = averageFps()
                val now = System.currentTimeMillis()
                val inThrottle = now < throttleUntilMs
                when {
                    inThrottle -> setScale(cfg.minScale)
                    avg < target - DEADBAND_LOW && now - lastScaleChangeMs > COOLDOWN_MS_DOWN ->
                        adjustScaleBy(-1)
                    avg > target + DEADBAND_HIGH && now - lastScaleChangeMs > COOLDOWN_MS_UP ->
                        adjustScaleBy(+1)
                }
                kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() { job?.cancel(); scope.cancel() }

    /** Called by [ThermalManager] on a Level 2 throttle event. */
    fun onThermal(level: Int) {
        if (!cfg.enabled) return
        if (level >= 2) {
            throttleUntilMs = System.currentTimeMillis() + THROTTLE_DURATION_MS
            setScale(cfg.minScale)
            Log.i(TAG, "thermal throttle level=$level → scale=${cfg.minScale}")
        }
    }

    // ---- internals ----

    private fun pushFps(fps: Float) {
        synchronized(fpsWindow) {
            if (fpsWindow.size == WINDOW_SIZE) fpsWindow.removeFirst()
            fpsWindow.addLast(fps)
        }
    }

    private fun averageFps(): Float = synchronized(fpsWindow) {
        if (fpsWindow.isEmpty()) cfg.targetFps.toFloat()
        else fpsWindow.average().toFloat()
    }

    private fun stepForScale(scale: Float): Int {
        if (cfg.stepCount <= 1) return 1
        val invStep = 1.0f / cfg.stepCount
        val ratio = (scale - cfg.minScale) / (cfg.maxScale - cfg.minScale)
        return (ratio / invStep).roundToInt().coerceIn(0, cfg.stepCount)
    }

    private fun adjustScaleBy(direction: Int) {
        val cur = _scale.value
        val stepIdx = stepForScale(cur) + direction
        val stepFrac = stepIdx.toFloat() / cfg.stepCount.coerceAtLeast(1)
        val newScale = (cfg.minScale + stepFrac * (cfg.maxScale - cfg.minScale))
            .coerceIn(cfg.minScale, cfg.maxScale)
        if (abs(newScale - cur) > 0.01f) {
            setScale(newScale)
            lastScaleChangeMs = System.currentTimeMillis()
            Log.i(TAG, "scale ${"%.2f".format(cur)} → ${"%.2f".format(newScale)} (fps avg=${
                averageFps().toInt()})")
        }
    }

    private fun setScale(newScale: Float) {
        _scale.value = newScale
        val w = (baseWidth * newScale).roundToInt().coerceAtLeast(320)
        val h = (baseHeight * newScale).roundToInt().coerceAtLeast(240)
        RenderTargetWriter.write(w, h)
    }

    companion object {
        private const val TAG = "DynResScaler"
        private const val WINDOW_SIZE = 90          // ~1.5 s @ 60 fps poll
        private const val DEADBAND_LOW  = 3f
        private const val DEADBAND_HIGH = 10f
        private const val POLL_INTERVAL_MS = 16L
        private const val COOLDOWN_MS_DOWN = 2000L
        private const val COOLDOWN_MS_UP   = 4000L
        private const val THROTTLE_DURATION_MS = 30_000L
    }
}

/**
 * Writes the current render target dimensions to a mmap'd file in
 * cacheDir. The DXVK fork polls this file at the start of every
 * present cycle.
 */
object RenderTargetWriter {
    private const val PATH = "render_target.json"

    fun write(width: Int, height: Int) {
        val ctx = com.strongholddroid.emulator.StrongholdDroidApp.instance
        val file = java.io.File(ctx.cacheDir, PATH)
        file.writeText(
            """{"width":$width,"height":$height,"ts":${System.currentTimeMillis()}}"""
        )
    }
}
