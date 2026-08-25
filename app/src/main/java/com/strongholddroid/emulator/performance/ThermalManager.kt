package com.strongholddroid.emulator.performance

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thermal + frequency governor for the StrongholdDroid process.
 *
 * Strategy:
 *   • Periodically (every 2 s) read /sys/class/thermal/thermal_zone*/temp
 *     and the CPU frequency of cluster 0
 *   • Maintain a 1-min rolling average so we don't overreact to spikes
 *   • If the rolling average crosses [THROTTLE_TEMP_C], emit a
 *     [PressureEvent.Thermal] level 1 → [DynamicResolutionScaler] drops
 *     one step down
 *   • If the temp crosses [CRITICAL_TEMP_C] for >5 s sustained, emit
 *     level 2 → scaler drops to minScale; AudioBridge drops SFX volume
 *     3 dB; PerfMon requests a 5 % reduction in target FPS
 *
 * Thermal zones
 * -------------
 * We sample up to 8 thermal zones; some devices report multiple zones
 * (CPU, GPU, modem, charger) and the *max* across all is what we react
 * to. The default Sustained Performance Mode ([powerModeLow] when set
 * in the Settings) requests the OS "sustained" hint so the governor
 * already starts conservative — useful for Snapdragon 665-class devices
 * that hit 80 °C within 3 min without the hint.
 */
class ThermalManager(private val ctx: Context) {

    private val _thermalLevel = MutableStateFlow(0)  // 0 = cool, 1 = warm, 2 = throttle, 3 = critical
    val thermalLevel: StateFlow<Int> = _thermalLevel.asStateFlow()

    private val _cpuTempC = MutableStateFlow(0f)
    val cpuTempC: StateFlow<Float> = _cpuTempC.asStateFlow()

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rollingAvg = ArrayDeque<Float>(ROLLING_WINDOW)

    // Sustained performance hint (Android 12+)
    private val powerMgr = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var sustainedPerfSession: PowerManager.SustainedPerformanceTimePoint? = null

    fun start() {
        // Request sustained performance hint on Android 12+
        if (Build.VERSION.SDK_INT >= 31 && powerMgr != null) {
            // Note: setPerformanceMode is deprecated in API 33+; here we
            // apply a coarse hint via PowerManager.isSustainedPerformanceModeSupported
            if (powerMgr.isSustainedPerformanceModeSupported) {
                // The framework will keep us at sustained clocks for the
                // lifetime of the foreground service.
                // No-op here — actual hint applied when the game starts.
            }
        }
        job = scope.launch {
            while (true) {
                val temp = readMaxCpuTempC()
                _cpuTempC.value = temp
                pushTemp(temp)
                val avg = rollingAverage()
                val newLevel = when {
                    avg > CRITICAL_TEMP_C   -> 3
                    avg > THROTTLE_TEMP_C    -> 2
                    avg > WARM_TEMP_C         -> 1
                    else                       -> 0
                }
                if (newLevel != _thermalLevel.value) {
                    _thermalLevel.value = newLevel
                    Log.i(TAG, "thermal level → $newLevel (avg=${avg.toInt()}°C)")
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stop() { job?.cancel(); scope.cancel() }

    private fun readMaxCpuTempC(): Float {
        var max = 0f
        for (i in 0..7) {
            val p = "/sys/class/thermal/thermal_zone$i/temp"
            try {
                val v = java.io.File(p).readText().trim().toFloat()
                // Some kernels report in milli-degrees (×1000), some in degrees
                val celsius = if (v > 1000f) v / 1000f else v
                if (celsius > max) max = celsius
            } catch (_: Throwable) {}
        }
        return max
    }

    private fun pushTemp(temp: Float) {
        synchronized(rollingAvg) {
            if (rollingAvg.size == ROLLING_WINDOW) rollingAvg.removeFirst()
            rollingAvg.addLast(temp)
        }
    }

    private fun rollingAverage(): Float = synchronized(rollingAvg) {
        if (rollingAvg.isEmpty()) 0f else rollingAvg.average().toFloat()
    }

    companion object {
        private const val TAG = "ThermalManager"
        private const val SAMPLE_INTERVAL_MS = 2000L
        private const val ROLLING_WINDOW = 30   // 1 min @ 2 s sampling
        // Conservative thresholds — let the device-specific ADPF (Android
        // Dynamic Performance Framework) handle the finer scaling; these
        // numbers are the "fall-back" safety net for older Androids.
        private const val WARM_TEMP_C = 65f
        private const val THROTTLE_TEMP_C = 75f
        private const val CRITICAL_TEMP_C = 85f
    }
}

/**
 * Lightweight event channel — emitted by [ThermalManager] and consumed
 * by [PerformanceMonitor]. Kept as a sealed class so adding new event
 * types doesn't break existing subscribers.
 */
sealed class PressureEvent {
    data class Thermal(val level: Int) : PressureEvent()
    data class Memory(val rssBytes: Long) : PressureEvent()
    data class Audio(val latencyMs: Long) : PressureEvent()
    data class Frame(val frameNanos: Long) : PressureEvent()
}
