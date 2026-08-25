package com.strongholddroid.emulator.audio

import android.util.Log
import androidx.annotation.Keep
import com.strongholddroid.emulator.emulator.EmulatorConfig
import com.strongholddroid.emulator.performance.PerformanceMonitor

/**
 * Kotlin-side façade for the native `audio_bridge.cpp`.
 *
 * Owns the AAudio / OpenSL ES session lifetime and propagates latency
 * estimates back to the [PerformanceMonitor] so the FPS scaler can react
 * to audio underruns (when audio glitches, video is usually the next to
 * go because the DXVK swapchain gets starved too).
 */
object AudioBridge {

    private const val TAG = "AudioBridgeKt"

    /** Stronghold Crusader's SFX sample rate. */
    const val SC_SFX_SAMPLE_RATE = 44_100
    /** Music (MIDI rendered by Wine's fluidsynth) downsamples to this. */
    const val SC_MUSIC_SAMPLE_RATE = 22_050

    @Volatile private var currentSession: Int = 0
    @Volatile private var currentCfg: EmulatorConfig? = null
    @Volatile private var perfMon: PerformanceMonitor? = null

    /**
     * Start the audio session. Called by [EmulatorCore.launch] BEFORE the
     * Wine process is forked so Wine's pulse driver sees the FIFO ready.
     *
     * Returns the native session token (>0) on success.
     */
    fun start(cfg: EmulatorConfig): Int {
        val ctx = com.strongholddroid.emulator.StrongholdDroidApp.instance
        val useAAudio = android.os.Build.VERSION.SDK_INT >= 27
        // Buffer size — ~10 ms at sample rate. The native code doubles it
        // for the safe ring so worst-case jitter is ~20 ms before underrun.
        val bufferFrames = SC_SFX_SAMPLE_RATE / 100

        val session = nativeAudioStart(
            sampleRate    = SC_SFX_SAMPLE_RATE,
            channels       = 2,
            bits           = 16,
            bufferFrames   = bufferFrames,
            useAaudio      = useAAudio,
            lowLatency     = true,
            fifoPath       = cfg.audioPipePath,
        )
        if (session <= 0) {
            Log.e(TAG, "audio session start failed — game will be silent")
        } else {
            Log.i(TAG, "audio session=$session started (useAAudio=$useAAudio)")
        }
        currentSession = session
        currentCfg = cfg
        return session
    }

    fun stop(session: Int) {
        if (session <= 0) return
        nativeAudioStop(session)
        Log.i(TAG, "audio session=$session stopped")
        if (session == currentSession) {
            currentSession = 0
            currentCfg = null
        }
    }

    /** Called by [PerformanceMonitor] at ~1 Hz to push current latency. */
    fun reportLatency(mon: PerformanceMonitor) {
        val s = currentSession
        if (s <= 0) return
        val us = nativeAudioLatencyUs(s)
        mon.notifyAudioLatency(us / 1000)
    }

    fun attachPerformanceMonitor(mon: PerformanceMonitor) {
        perfMon = mon
    }

    // JNI bindings.
    @Keep private external fun nativeAudioStart(
        sampleRate: Int, channels: Int, bits: Int, bufferFrames: Int,
        useAaudio: Boolean, lowLatency: Boolean, fifoPath: String): Int
    @Keep private external fun nativeAudioStop(session: Int)
    @Keep private external fun nativeAudioLatencyUs(session: Int): Long
}
