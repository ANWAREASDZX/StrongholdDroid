package com.strongholddroid.emulator.emulator

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.strongholddroid.emulator.StrongholdDroidApp
import com.strongholddroid.emulator.audio.AudioBridge
import com.strongholddroid.emulator.graphics.GraphicsBackendSelector
import com.strongholddroid.emulator.graphics.VulkanDetector
import com.strongholddroid.emulator.input.InputBridge
import com.strongholddroid.emulator.performance.PerformanceMonitor
import com.strongholddroid.emulator.profiles.GameProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Top-level orchestrator for a single StrongholdDroid session.
 *
 * Responsibilities:
 *   1. Resolve a [GameProfile] into a concrete [EmulatorConfig] (graphics
 *      backend auto-selection, save-state slot lookup, audio FIFO path).
 *   2. Build the runtime environment via [EnvironmentBuilder].
 *   3. Launch the Wine+box64 process via [WineLauncher] (JNI).
 *   4. Boot the [AudioBridge] renderer thread.
 *   5. Run the input-pump + fps-monitor coroutine loop until the game exits.
 *
 * Thread model:
 *   • All native calls happen on a single dedicated [Dispatchers.IO] thread
 *     (the "emulator worker"). Co-routines are used purely for ergonomic
 *     supervision; they hop onto the worker via [withContext(ioThread)].
 */
object EmulatorCore {

    private const val TAG = "EmulatorCore"

    /** Launch phases surfaced to the GameManager UI. */
    sealed class LaunchPhase {
        object Idle : LaunchPhase()
        object ExtractingRuntime : LaunchPhase()
        object InitializingPrefix : LaunchPhase()
        object Starting : LaunchPhase()
        object Running : LaunchPhase()
        data class Exited(val exitCode: Int) : LaunchPhase()
        data class Failed(val message: String) : LaunchPhase()
    }

    private val _phase = MutableStateFlow<LaunchPhase>(LaunchPhase.Idle)
    val phase: StateFlow<LaunchPhase> = _phase.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    @Volatile private var currentConfig: EmulatorConfig? = null
    @Volatile private var pumpJob: Job? = null
    @Volatile private var audioSession: Int = 0
    private val stopRequested = AtomicBoolean(false)
    private val exitDeferred = CompletableDeferred<Int>()

    fun isRunning(): Boolean = _running.value

    /**
     * Launches the game described by [profile], optionally restoring the
     * save-state at slot [saveSlot]. Returns the resolved [EmulatorConfig].
     *
     * Progress is published through [phase] so the UI can show what the
     * (potentially minutes-long) first launch is doing — v0.1.0 showed
     * nothing at all and users could not tell a working launch from a
     * dead one.
     */
    suspend fun launch(profile: GameProfile, saveSlot: Int): EmulatorConfig =
        withContext(Dispatchers.IO) {
            check(!_running.value) { "Emulator already running — call stop() first" }
            val ctx = StrongholdDroidApp.instance
            try {
                Log.i(TAG, "Launching profile '${profile.slug}' (saveSlot=$saveSlot)")
                _running.value = true
                _exitCode.value = null
                stopRequested.set(false)
                _phase.value = LaunchPhase.ExtractingRuntime

                // 1. Resolve config
                val backend = GraphicsBackendSelector.select(ctx, profile)
                val cfg = EmulatorConfig(
                    winePrefix        = EnvironmentBuilder.winePrefixFor(profile),
                    wineBinary        = File(ctx.filesDir, "usr/bin/wine").absolutePath,
                    gameExec          = profile.gameExecutable,
                    audioPipePath     = File(ctx.cacheDir, "pulse-audio.fifo").absolutePath,
                    graphicsBackend   = backend,
                    gameProfileSlug   = profile.slug,
                    saveStateSlot     = saveSlot,
                )
                currentConfig = cfg

                // 2. Build environment
                //    (first call also extracts assets/prebuilt.zip → filesDir)
                EnvironmentBuilder.ensureFirstRunExtraction(ctx)
                _phase.value = LaunchPhase.InitializingPrefix
                EnvironmentBuilder.ensureWinePrefix(ctx, profile, cfg)
                EnvironmentBuilder.ensureBox64Environment(ctx, cfg)
                EnvironmentBuilder.ensureDXVKDlls(ctx, profile, cfg)

                // 3. Start audio renderer BEFORE launching Wine — Wine will
                //    block on the FIFO until we're ready to read.
                _phase.value = LaunchPhase.Starting
                audioSession = AudioBridge.start(cfg)

                // 4. Launch Wine
                val pid = WineLauncher.launch(cfg)
                if (pid <= 0) {
                    AudioBridge.stop(audioSession); audioSession = 0
                    error("Wine launch failed: ${WineLauncher.lastError()}")
                }
                _phase.value = LaunchPhase.Running

                // 5. Start the input pump + fps monitor
                pumpJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    val perf = PerformanceMonitor(ctx, cfg, profile)
                    perf.start()
                    val pumpIntervalNs = 1_000_000_000L / 240  // 240 Hz cap
                    var lastPump = 0L
                    while (!stopRequested.get() && !exitDeferred.isCompleted) {
                        val now = System.nanoTime()
                        if (now - lastPump >= pumpIntervalNs) {
                            InputBridge.pumpIntoWine()
                            perf.onFrame(System.nanoTime())
                            lastPump = now
                        } else {
                            // Spin-yield so we hit exactly 240 Hz without busy-looping
                            Thread.sleep(0, 200_000)
                        }
                    }
                    perf.stop()
                }

                // 6. Block until exit
                val rc = WineLauncher.waitForExit(pid)
                _exitCode.value = rc
                exitDeferred.complete(rc)
                _phase.value = LaunchPhase.Exited(rc)

                cfg
            } catch (t: Throwable) {
                Log.e(TAG, "launch failed", t)
                _phase.value = LaunchPhase.Failed(t.message ?: t.javaClass.simpleName)
                cleanupAfterExit()
                throw t
            }
        }

    /** Politely stop the running game (SIGTERM to wineserver + SIGTERM to wine64). */
    fun requestShutdown() {
        if (!stopRequested.compareAndSet(false, true)) return
        val cfg = currentConfig ?: return
        Log.i(TAG, "requestShutdown: ${cfg.gameProfileSlug}")
        WineLauncher.requestShutdown()  // dispatches through native token registry
    }

    /** Forcibly kill the running game (SIGKILL). */
    fun forceKill() {
        stopRequested.set(true)
        WineLauncher.forceKill()
    }

    /** Block until the game has fully exited. Returns the exit code. */
    suspend fun awaitExit(): Int {
        return if (_running.value) exitDeferred.await().also { cleanupAfterExit() }
               else _exitCode.value ?: 0
    }

    private fun cleanupAfterExit() {
        pumpJob?.cancel(); pumpJob = null
        if (audioSession != 0) { AudioBridge.stop(audioSession); audioSession = 0 }
        _running.value = false
        currentConfig = null
    }
}
