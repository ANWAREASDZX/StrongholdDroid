package com.strongholddroid.emulator.performance

import android.content.Context
import android.util.Log
import com.strongholddroid.emulator.emulator.EmulatorConfig
import com.strongholddroid.emulator.profiles.GameProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Aggregates thermal + memory + audio + frame-timing events into a single
 * "pressure" stream that [com.strongholddroid.emulator.graphics.DynamicResolutionScaler]
 * reacts to.
 *
 * Also exposes a 1.5 s rolling-average FPS — this is what the user-facing
 * FPS counter reads from.
 *
 * Threading
 * --------
 * Event producers (ThermalManager every 2 s, AudioBridge every 1 s,
 * FpsMonitor every frame) all call into this class. The class itself
 * is internally synchronized — it does not require callers to be on
 * a specific thread.
 */
class PerformanceMonitor(
    private val ctx: Context,
    private val config: EmulatorConfig,
    private val profile: GameProfile,
) {

    private val _pressure = MutableSharedFlow<PressureEvent>(replay = 4, extraBufferCapacity = 8)
    val pressure: SharedFlow<PressureEvent> = _pressure.asSharedFlow()

    private val frameNanos = ArrayDeque<Long>(FRAME_WINDOW)
    private val fpsAccumulator = AtomicLong(0)
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        job = scope.launch {
            while (true) {
                delay(1000)
                // Periodically poll the AudioBridge for its latency estimate
                com.strongholddroid.emulator.audio.AudioBridge.reportLatency(this@PerformanceMonitor)
            }
        }
    }

    fun stop() { job?.cancel(); scope.cancel() }

    /** Called from [com.strongholddroid.emulator.graphics.FpsMonitor] every frame. */
    fun onFrame(frameNanos: Long) {
        synchronized(frameNanos) {
            if (frameNanos.size == FRAME_WINDOW) frameNanos.removeFirst()
            frameNanos.addLast(frameNanos)
        }
        _pressure.tryEmit(PressureEvent.Frame(frameNanos))
    }

    /** Called from [com.strongholddroid.emulator.emulator.ProcessMonitor] when
     * the Wine process's RSS growth exceeds the watch threshold. */
    fun notifyMemoryPressure(rssBytes: Long) {
        _pressure.tryEmit(PressureEvent.Memory(rssBytes))
        Log.w(TAG, "memory pressure: rss=${rssBytes / 1024 / 1024}MB")
    }

    /** Called from [com.strongholddroid.emulator.audio.AudioBridge] at 1 Hz. */
    fun notifyAudioLatency(latencyMs: Long) {
        _pressure.tryEmit(PressureEvent.Audio(latencyMs))
        if (latencyMs > 50) {
            Log.w(TAG, "audio latency ${latencyMs}ms — above budget")
        }
    }

    /** Called from [com.strongholddroid.emulator.performance.ThermalManager] on level change. */
    fun notifyThermal(level: Int) {
        _pressure.tryEmit(PressureEvent.Thermal(level))
    }

    /** 1.5 s rolling-average FPS, used by [DynamicResolutionScaler]. */
    fun rollingFps(): Float = synchronized(frameNanos) {
        if (frameNanos.isEmpty()) 0f
        else {
            val avgNs = frameNanos.average()
            (1_000_000_000f / avgNs).toFloat()
        }
    }

    /** Current FPS for the user-facing counter. */
    fun instantFps(): Float = synchronized(frameNanos) {
        if (frameNanos.isEmpty()) 0f
        else 1_000_000_000f / frameNanos.last()
    }

    companion object { private const val TAG = "PerfMon"; private const val FRAME_WINDOW = 90 }
}
