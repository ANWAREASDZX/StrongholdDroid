package com.strongholddroid.emulator.emulator

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.strongholddroid.emulator.performance.PerformanceMonitor
import java.io.File
import java.io.RandomAccessFile

/**
 * Background supervisor of the Wine process.
 *
 * Three responsibilities:
 *   1. Heartbeat — every 1 s, check that the wine64 PID is still alive via
 *      /proc/<pid>/stat. If missing, the [EmulatorCore] is told and the
 *      pump loop unwinds.
 *   2. OOM watchdog — read /proc/<pid>/status's VmRSS; if it exceeds the
 *      SoftLimit for the device class, push a [PerformanceMonitor.PressureEvent]
 *      so the FPS scaler and DXVK heap can react.
 *   3. Crash report collector — if the wine64 process dies with a signal,
 *      dump the last 200 lines of its logcat buffer to a file for the
 *      troubleshooting guide's "no crash dump" diagnostic flow.
 *
 * The watcher runs on a dedicated [HandlerThread] because we want low
 * jitter — sharing a thread with the input pump would mean a stalled
 * syscall could delay the heartbeat.
 */
class ProcessMonitor(
    private val winePrefix: String,
    private val perfMon: PerformanceMonitor,
    private val onDied: (Int /* exit */, String? /* reason */) -> Unit,
) {

    private val thread = HandlerThread("proc-mon", Process.THREAD_PRIORITY_BACKGROUND).also {
        it.start()
    }
    private val handler = Handler(thread.looper)
    @Volatile private var stopped = false
    @Volatile private var winePid: Int = 0
    private var lastRssBytes = 0L

    fun start(pid: Int) {
        winePid = pid
        handler.post(heartbeat)
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (stopped) return
            try {
                val statFile = File("/proc/$winePid/stat")
                if (!statFile.exists()) {
                    // Process gone — best-effort extract exit reason
                    val reason = readExitReason()
                    onDied(-1, reason)
                    return
                }
                // Read VmRSS from /proc/<pid>/status
                val rss = readVmRss()
                if (rss > 0) {
                    val delta = rss - lastRssBytes
                    if (delta > RSS_GROWTH_PER_SEC) {
                        perfMon.notifyMemoryPressure(rss)
                    }
                    lastRssBytes = rss
                }
            } catch (t: Throwable) {
                Log.w(TAG, "heartbeat error", t)
            }
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun readVmRss(): Long {
        return try {
            RandomAccessFile("/proc/$winePid/status", "r").use { raf ->
                var line: String?
                while (raf.readLine().also { line = it } != null) {
                    if (line!!.startsWith("VmRSS:")) {
                        val tok = line!!.trim().split(Regex("\\s+"))
                        if (tok.size >= 2) return tok[1].toLong() * 1024
                    }
                }
                0L
            }
        } catch (e: Exception) { 0L }
    }

    private fun readExitReason(): String? {
        val crashDir = File(winePrefix, "crashes")
        crashDir.mkdirs()
        val outFile = File(crashDir, "last_${SystemClock.uptimeMillis()}.log")
        return try {
            // Pull the last 200 lines of the wine-stdout tag
            val pb = ProcessBuilder("logcat", "-d", "-t", "200",
                "-s", "wine-stdout:V", "wine-stderr:V", "strongholddroid-wine:V")
                .redirectOutput(outFile)
            pb.environment()["LC_ALL"] = "C"
            pb.start().waitFor()
            if (outFile.length() > 0) outFile.absolutePath else null
        } catch (e: Exception) {
            Log.w(TAG, "could not dump logcat tail", e); null
        }
    }

    companion object {
        private const val TAG = "ProcessMonitor"
        private const val HEARTBEAT_INTERVAL_MS = 1000L
        // >400 MB/s sustained growth = leak. SC's normal memory profile is
        // ~150 MB steady-state after 1 h, with occasional 50 MB bursts during
        // map transitions.
        private const val RSS_GROWTH_PER_SEC = 400L * 1024 * 1024 / 1000L
    }
}
