package com.strongholddroid.emulator.emulator

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Thin convenience wrapper for `box64 --version` and pre-launch
 * sanity checks. Box64 itself is launched indirectly inside
 * [wine_bridge.cpp::launch_game] via the `box64_run_wine()` weak
 * symbol exported by libbox64.so — we don't fork+exec box64 directly
 * from Kotlin because doing so would break the shared-library memory
 * sharing that gives box64 its NEON-emulated SSE cache.
 *
 * This class exists mostly to:
 *   1. Confirm `libbox64.so` is present (so the runtime doesn't silently
 *      fall back to QEMU-style pure interpretation, which would be unusable).
 *   2. Surface a self-test mode for the Settings UI so users can see which
 *      dynarec flags are active.
 */
object Box64Launcher {

    private const val TAG = "Box64Launcher"

    /** True if libbox64.so is loaded and exports `box64_run_wine`. */
    fun isAvailable(): Boolean = try {
        System.loadLibrary("box64")
        // Touch the weak symbol; if missing, we get UnsatisfiedLinkError
        nativeHasBox64()
    } catch (t: Throwable) {
        false
    }

    /** Returns a printable version string for the Settings UI. */
    fun versionString(): String =
        if (isAvailable()) "box64 ${nativeVersion()}" else "box64 unavailable"

    /**
     * Self-test — executes a tiny x86_64 binary that box64 can interpret
     * (a no-op ELF). Used by the Settings → Diagnostics page.
     * Returns elapsed milliseconds; <2000 is healthy on a Cortex-A78.
     */
    fun selfTest(ctx: Context): Long {
        val dummy = File(ctx.filesDir, "usr/bin/box64-selftest").also { parent ->
            if (!parent.exists()) parent.parentFile?.mkdirs()
        }
        if (!dummy.exists()) {
            // Stub — real self-test binary is shipped in assets/selftest.bin
            // and extracted on first run. Just return a constant for now.
            return -1L
        }
        val start = System.nanoTime()
        val rc = ProcessBuilder(dummy.absolutePath)
            .redirectErrorStream(true).start()
            .waitFor()
        val elapsed = (System.nanoTime() - start) / 1_000_000L
        if (rc != 0) Log.w(TAG, "box64 self-test rc=$rc")
        return elapsed
    }

    @Suppress("unused") private external fun nativeHasBox64(): Boolean
    @Suppress("unused") private external fun nativeVersion(): String
}
