package com.strongholddroid.emulator.emulator

import android.util.Log
import androidx.annotation.Keep
import java.io.File

/**
 * High-level wrapper around the native `wine_bridge` (see `wine_bridge.cpp`).
 *
 * Why a separate class instead of calling JNI from [EmulatorCore] directly?
 *   • Centralizes error translation (`lastError()` → Kotlin exceptions)
 *   • Adds logging that's testable (we can substitute a fake launcher in tests)
 *   • Gives us a single place to enforce the "only one Wine process at a time"
 *     invariant
 *
 * Token vs PID:
 *   The native layer returns a *token* (a small integer) rather than the raw
 *   Unix PID — this lets the bridge track the wineserver PID alongside the
 *   wine64 PID and tear both down together.
 */
object WineLauncher {

    private const val TAG = "WineLauncher"

    @Volatile private var currentToken: Int = 0

    /** Returns the native token (>=1) on success. */
    fun launch(cfg: EmulatorConfig): Int {
        require(cfg.wineBinary.isNotEmpty()) { "wineBinary path is empty" }
        require(File(cfg.wineBinary).exists()) {
            "Wine binary not found at ${cfg.wineBinary}. Did you run " +
                "scripts/build_all.sh and copy the result into the app's files dir?"
        }

        val env = EnvironmentBuilder.buildEnvList(cfg)
        val envKv = env.map { "${it.first}=${it.second}" }.toTypedArray()

        val token = nativeWineLaunch(
            wineBin   = cfg.wineBinary,
            prefix    = cfg.winePrefix,
            gameExec  = cfg.gameExec,
            envKv     = envKv,
            wineArch  = cfg.wineArch,
            wow64     = cfg.wow64,
            esync     = cfg.esync,
            fsync     = cfg.fsync,
            rtWidth   = cfg.renderTargetWidth,
            rtHeight  = cfg.renderTargetHeight,
            audioPipe = cfg.audioPipePath,
        )
        if (token <= 0) {
            Log.e(TAG, "native launch failed: ${lastError()}")
            return token
        }
        currentToken = token
        Log.i(TAG, "wine64 launched, token=$token")
        return token
    }

    /** Blocking — waits for the game process to exit, returns the exit code. */
    fun waitForExit(token: Int): Int {
        val rc = nativeWineWaitForExit(token)
        currentToken = 0
        return rc
    }

    fun requestShutdown() {
        val t = currentToken
        if (t <= 0) return
        nativeWineRequestShutdown(t)
    }

    fun forceKill() {
        val t = currentToken
        if (t <= 0) return
        nativeWineForceKill(t)
    }

    fun lastError(): String = nativeWineLastError()

    // ----------------------------------------------------------------------
    // JNI bindings — declared here so the symbols are local to this file.
    // ----------------------------------------------------------------------
    @Keep private external fun nativeWineLaunch(
        wineBin: String, prefix: String, gameExec: String,
        envKv: Array<String>, wineArch: String, wow64: Boolean,
        esync: Boolean, fsync: Boolean,
        rtWidth: Int, rtHeight: Int, audioPipe: String): Int

    @Keep private external fun nativeWineWaitForExit(token: Int): Int
    @Keep private external fun nativeWineRequestShutdown(token: Int)
    @Keep private external fun nativeWineForceKill(token: Int)
    @Keep private external fun nativeWineLastError(): String
}
