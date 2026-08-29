package com.strongholddroid.emulator.emulator

import android.content.Context
import com.strongholddroid.emulator.StrongholdDroidApp
import java.io.File

/**
 * Thin convenience wrapper for box64 WoW64 runtime diagnostics.
 *
 * In the "Arm64 Wine WOW64" architecture box64 is not a separate library
 * or process — its emulator is compiled INTO the WoW64 cpu dll
 * (wowbox64.dll, an aarch64-windows PE shipped in the runtime asset and
 * staged into the WINEPREFIX as xtajit.dll by EnvironmentBuilder).
 *
 * This class exists to:
 *   1. Confirm the wow64 cpu dll is present in the extracted runtime
 *      (filesDir/wow64/wowbox64.dll) — without it 32-bit games cannot run.
 *   2. Surface a self-test mode for the Settings UI.
 */
object Box64Launcher {

    private const val TAG = "Box64Launcher"

    /** True if the box64 WoW64 cpu dll is present in the extracted runtime. */
    fun isAvailable(): Boolean {
        val ctx = StrongholdDroidApp.instance
        return File(ctx.filesDir, "wow64/wowbox64.dll").exists()
    }

    /** Returns a printable version string for the Settings UI. */
    fun versionString(): String =
        if (isAvailable()) "box64 WoW64 cpu dll (wowbox64.dll)" else "box64 unavailable"

    /**
     * Self-test placeholder — a real self-test would need a running wine
     * prefix; the extracted-dll presence check above is the cheap proxy.
     */
    fun selfTest(ctx: Context): Long {
        return if (isAvailable()) 0L else -1L
    }

}
