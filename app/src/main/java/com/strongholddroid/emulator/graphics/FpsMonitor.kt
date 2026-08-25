package com.strongholddroid.emulator.graphics

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import com.strongholddroid.emulator.performance.PerformanceMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts frames per second by listening to DXVK's `vkQueuePresentKHR`
 * callbacks that are surfaced to JNI through our prebuilt libvulkan
 * wrapper. When the game isn't running, [Choreographer] is used as a
 * fallback at ~60 Hz so unit tests still get a plausible number.
 *
 * The monitor exposes a rolling average over [ROLLING_WINDOW_MS] — short
 * enough that [DynamicResolutionScaler] reacts in ~1.5 s, long enough to
 * ignore single-frame stutters caused by async shader compilation.
 */
class FpsMonitor(private val perfMon: PerformanceMonitor? = null) {

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val frameTimes = ArrayDeque<Long>(CAPACITY)
    private val lastFrameNs = AtomicLong(0)

    /** Called from JNI (vulkan_present_callback) every time a frame is
     * presented to the surface. */
    fun onFramePresented() {
        val now = SystemClock.elapsedRealtimeNanos()
        val prev = lastFrameNs.getAndSet(now)
        if (prev == 0L) return
        val dt = now - prev
        synchronized(frameTimes) {
            if (frameTimes.size == CAPACITY) frameTimes.removeFirst()
            frameTimes.addLast(dt)
        }
        _fps.value = 1_000_000_000f / dt.coerceAtLeast(1)
        perfMon?.onFrame(dt)
    }

    /** Rolling-average FPS over the last ~1.5 s. */
    fun rollingFps(): Float = synchronized(frameTimes) {
        if (frameTimes.isEmpty()) 0f
        else {
            val avgNs = frameTimes.average()
            (1_000_000_000f / avgNs).toFloat()
        }
    }

    fun reset() {
        synchronized(frameTimes) { frameTimes.clear() }
        lastFrameNs.set(0)
        _fps.value = 0f
    }

    companion object {
        // ~1.5 s window at 60 fps
        private const val CAPACITY = 90
    }
}
