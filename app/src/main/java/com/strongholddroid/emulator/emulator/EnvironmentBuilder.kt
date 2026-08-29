package com.strongholddroid.emulator.emulator

import android.content.Context
import android.util.Log
import com.strongholddroid.emulator.BuildConfig
import com.strongholddroid.emulator.StrongholdDroidApp
import com.strongholddroid.emulator.profiles.GameProfile
import com.strongholddroid.emulator.profiles.StrongholdCrusaderProfile
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Builds the runtime environment that Wine expects on Android.
 *
 * Architecture ("Arm64 Wine WOW64"):
 * wine itself runs natively (arm64 ELF); the game's 32-bit x86 code is
 * executed inside the wine process by box64's WoW64 cpu dll (xtajit.dll).
 *
 * Per-game isolation
 * ------------------
 * Every [GameProfile] gets its own WINEPREFIX at
 *   `<filesDir>/prefixes/<profile.slug>`
 * This isolates registry HKEY_CURRENT_USER settings, override DLL cache,
 * and per-game native DLL overrides. SC 1.1 needs ddrawex while SC HD
 * wants DXVK + d3dcompiler — mixing them in one prefix would produce a
 * Frankenstein that crashes 9/10 times.
 *
 * Runtime layout (under app/files, extracted from assets/prebuilt.zip):
 *   usr/bin/wine              ← arm64 ELF wine loader (runs natively)
 *   usr/bin/wineserver        ← arm64 ELF wineserver
 *   usr/bin/wineboot, ...     ← other wine programs (all arm64 ELF)
 *   usr/lib/wine/aarch64-unix  (.so)     ← unix-side libs wine dlopens
 *   usr/lib/wine/aarch64-windows/      ← native 64-bit builtin PE DLLs
 *   usr/lib/wine/i386-windows/         ← WoW64 32-bit builtin PE DLLs
 *   usr/lib/libpulse.so.0    ← pulse stub wine's winepulse.drv links
 *   usr/lib/libpulsecommon-XX.so
 *   usr/lib/libGL.so.1       ← gl4es (GL → GLES translation for wined3d)
 *   share/wine/nls/          ← locale/codepage tables (data_dir — see
 *                              build_apk.sh: wine resolves data_dir to
 *                              <filesDir>/share/wine from the usr/bin
 *                              binary location, NOT usr/share/wine)
 *   share/wine/fonts/        ← wine bitmap fonts (vgasys.fon, ...) —
 *                              dialogs/GDI stock text render blank without them
 *   wow64/wowbox64.dll       ← box64 WoW64 cpu dll (arm64 PE)
 *   dxvk-wine-dlls/          <- 32-bit DXVK dlls
 *   prefixes/<slug>/         ← per-game WINEPREFIX
 *   dxvk/<slug>/             ← DXVK native DLLs staged for this prefix
 *   saves/<slug>/<slot>.bin  ← save-states
 */
object EnvironmentBuilder {

    private const val TAG = "EnvBuilder"

    fun filesRoot(ctx: Context) = ctx.filesDir
    fun prefixDir(ctx: Context, slug: String) = File(filesRoot(ctx), "prefixes/$slug")
    fun dxvkDir(ctx: Context, slug: String)   = File(filesRoot(ctx), "dxvk/$slug")
    fun savesDir(ctx: Context, slug: String)  = File(filesRoot(ctx), "saves/$slug")
    fun usrDir(ctx: Context)                  = File(filesRoot(ctx), "usr")

    fun winePrefixFor(profile: GameProfile): String =
        prefixDir(StrongholdDroidApp.instance, profile.slug).absolutePath

    // ------ Public entry points called from EmulatorCore ------

    /**
     * Extracts the wine runtime from `assets/prebuilt.zip` (packaged by
     * scripts/build_apk.sh) into [ctx].filesDir.  Idempotent: a marker
     * file records the extracted build; re-extraction only happens when
     * the marker is absent (fresh install or cleared data).
     *
     * Contents: usr/bin (wine, wineserver, wineboot, ... — all arm64 ELF),
     * usr/lib/wine/{aarch64-unix,aarch64-windows,i386-windows},
     * usr/lib/{libpulse.so.0,libpulsecommon-XX.so,libGL.so.1},
     * share/wine/{nls,fonts}, wow64/wowbox64.dll, dxvk-wine-dlls/.
     */
    fun ensureFirstRunExtraction(ctx: Context) {
        // The marker records the runtime layout + app version it was extracted
        // from.  When a new APK ships a different prebuilt.zip (layout change,
        // wine rebuild, new X11 libs/fonts) the marker no longer matches and
        // the runtime is re-extracted over the old one — otherwise an app
        // upgrade would keep running the STALE runtime from the previous
        // install (exactly what happened upgrading v0.1.0 → v0.1.1).
        val runtimeVersion = "v2:${BuildConfig.VERSION_CODE}"  // v2 = share/wine layout
        val marker = File(ctx.filesDir, ".runtime-extracted")
        val wineBin = File(usrDir(ctx), "bin/wine")
        if (marker.exists() && wineBin.canExecute() &&
            marker.readText().contains("runtime=$runtimeVersion")) return

        Log.i(TAG, "Extracting wine runtime from assets/prebuilt.zip ...")
        val start = System.currentTimeMillis()
        try {
            ctx.assets.open("prebuilt.zip").use { asset ->
                ZipInputStream(asset.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val out = File(ctx.filesDir, entry.name)
                        // Zip-slip guard: canonical path must stay inside filesDir.
                        if (!out.canonicalPath.startsWith(ctx.filesDir.canonicalPath + File.separator)) {
                            throw IOException("bad zip entry: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            throw IOException("runtime asset extraction failed", e)
        }

        // Restore the executable bit — zip does not carry unix permissions.
        File(usrDir(ctx), "bin").listFiles()?.forEach { it.setExecutable(true, false) }

        // Sanity: the wine loader must exist and be runnable.
        if (!wineBin.canExecute()) {
            throw IOException("usr/bin/wine missing after extraction")
        }
        marker.writeText("runtime=$runtimeVersion\nextracted-at=${System.currentTimeMillis()}\n")
        Log.i(TAG, "Runtime extracted in ${System.currentTimeMillis() - start} ms")
    }

    /**
     * Idempotently creates the WINEPREFIX at the path described in [cfg].
     * Runs `wineboot --init` if the prefix is brand new, blocking until
     * wineboot reports success.
     */
    fun ensureWinePrefix(ctx: Context, profile: GameProfile, cfg: EmulatorConfig) {
        val prefix = File(cfg.winePrefix)
        if (prefix.exists() && File(prefix, "system.reg").exists()) {
            Log.d(TAG, "prefix exists, skipping init: ${prefix.absolutePath}")
            return
        }
        prefix.mkdirs()
        runCatching { initPrefixBlocking(ctx, profile, cfg) }
            .onFailure { Log.e(TAG, "init prefix failed", it); throw IOException("init prefix failed", it) }
    }

    /**
     * Idempotently creates the box64 config dir + box64rc file under
     * `<filesDir>/box64/box64rc`. We pre-bake a config that targets the
     * instruction mix used by SC (mild SSE2, no AVX).
     */
    fun ensureBox64Environment(ctx: Context, cfg: EmulatorConfig) {
        val dir = File(ctx.filesDir, "box64").apply { mkdirs() }
        val rc  = File(dir, "box64rc")
        if (!rc.exists()) {
            rc.writeText(
                """
                # StrongholdDroid box64rc — hand-tuned for Stronghold Crusader.
                # See https://github.com/ptitSeb/box64/blob/main/docs/USAGE.md
                [box64]
                BOX64_DYNAREC=1
                BOX64_DYNAREC_STRONGARM=${if (cfg.box64Dynarec.strongArm) 1 else 0}
                BOX64_DYNAREC_BIGBLOCK=${cfg.box64Dynarec.bigBlock}
                BOX64_DYNAREC_SAFE=${if (cfg.box64Dynarec.safeMode) 1 else 0}
                BOX64_DYNAREC_FORWARD=${if (cfg.box64Dynarec.forward) 1 else 0}
                BOX64_DYNAREC_STRONGMEM=${cfg.box64Dynarec.strongMem}
                BOX64_DYNAREC_X87DOUBLE=${if (cfg.box64Dynarec.x87Double) 1 else 0}
                BOX64_DYNAREC_FASTNAN=${if (cfg.box64Dynarec.neon) 1 else 0}
                BOX64_DYNAREC_FASTROUND=1
                BOX64_DYNAREC_FASTFORWARD=0
                BOX64_DYNAREC_FASTUNALIGN=1
                BOX64_ROLLING_LOG=0
                BOX64_NOALIGN=1

                # SC's main loop allocates large enough blocks that big-block
                # dynarec is beneficial on Cortex-A78+; disable on A55 to save icache.
                [Stronghold_Crusader.exe]
                BOX64_DYNAREC_BIGBLOCK=2
                BOX64_DYNAREC_STRONGMEM=2

                [Stronghold Crusader Extreme.exe]
                BOX64_DYNAREC_BIGBLOCK=2
                BOX64_DYNAREC_STRONGMEM=2
                """.trimIndent()
            )
        }
    }

    /**
     * Copies the DXVK dlls (d3d9.dll, dxgi.dll, ...) into the prefix's
     * `drive_c/windows/system32` so that Wine's `WINEDLLOVERRIDES` can
     * pick them up at native.
     */
    fun ensureDXVKDlls(ctx: Context, profile: GameProfile, cfg: EmulatorConfig) {
        val sys32 = File(cfg.winePrefix, "drive_c/windows/system32").apply { mkdirs() }
        val dxvkSrc = dxvkDir(ctx, profile.slug)
        if (!dxvkSrc.exists()) {
            Log.w(TAG, "DXVK dir missing for ${profile.slug} — runtime may fall back to wined3d")
            return
        }
        val primary = cfg.graphicsBackend.primary.dxvkDll
        val primarySrc = File(dxvkSrc, primary)
        if (primarySrc.exists()) {
            primarySrc.copyTo(File(sys32, primary), overwrite = true)
        }
        // Also stage dxgi.dll which DXVK always requires on Win64
        File(dxvkSrc, "dxgi.dll").takeIf { it.exists() }?.copyTo(
            File(sys32, "dxgi.dll"), overwrite = true)
        // d3dcompiler_47.dll — DXVK needs it for SC HD shaders
        File(dxvkSrc, "d3dcompiler_47.dll").takeIf { it.exists() }?.copyTo(
            File(sys32, "d3dcompiler_47.dll"), overwrite = true)
    }

    /**
     * Builds the KEY=VALUE env list handed to the native launcher.
     * Order matters: later entries overwrite earlier ones (same as POSIX).
     */
    fun buildEnvList(cfg: EmulatorConfig): List<Pair<String, String>> {
        val ctx = StrongholdDroidApp.instance
        val list = mutableListOf<Pair<String, String>>()

        // Path-like basics (POSIX-internal; Wine will translate to its own PATH)
        list += "PATH" to "${usrDir(ctx)}/bin:${usrDir(ctx)}/lib:${ctx.filesDir}/box64"
        // LD_LIBRARY_PATH — CRITICAL: wine's unix libs (winepulse.drv etc.)
        // link against usr/lib/libpulse.so.0 and wined3d dlopens usr/lib/
        // libGL.so.1 (gl4es); the Android dynamic linker only finds them via
        // LD_LIBRARY_PATH (there is no ldconfig on Android).
        // Since v0.2.0 it ALSO carries the X11 client libs (libX11.so, ...)
        // that winex11.so NEEDs — without them the display driver cannot
        // load and no window can ever open.
        list += "LD_LIBRARY_PATH" to "${usrDir(ctx)}/lib"
        list += "HOME" to cfg.winePrefix
        list += "TMPDIR" to ctx.cacheDir.absolutePath
        // XDG_RUNTIME_DIR — CRITICAL: wineserver creates its socket at
        // $XDG_RUNTIME_DIR/.wine-<uid> (falls back to /tmp which is NOT
        // writable for an Android app).  Point it at our cache dir or
        // every wine process dies at startup.
        list += "XDG_RUNTIME_DIR" to ctx.cacheDir.absolutePath
        list += "XDG_DATA_HOME" to ctx.filesDir.absolutePath

        // DISPLAY — the X server (XServer XSDL / Termux:X11) listens on
        // TCP 127.0.0.1:6000 on the device.  wine's winex11.drv connects
        // there to put the game on screen.
        list += "DISPLAY" to (cfg.xDisplay.ifEmpty { "127.0.0.1:0" })

        // Keep wineserver from looking at the system XDG dirs.
        list += "WINEPREFIX" to cfg.winePrefix

        // Box64 config
        list += "BOX64_RCFILE" to "${ctx.filesDir}/box64/box64rc"
        list += "BOX64_LOG" to "0"
        list += cfg.box64Dynarec.toEnvList()

        // Wine config.  A diagnostic-friendly WINEDEBUG: errors from all
        // channels (driver load failures, PE loader errors, ...) get
        // captured by the native pump into filesDir/logs/wine.log so the
        // in-app log viewer can explain launch failures.  The native side
        // only falls back to -all when this var is absent.
        list += "WINEDEBUG" to "err+all"

        // Launch log tee target — wine_bridge.cpp extracts this and the
        // pump threads append the child's stdout/stderr to the file.
        list += "STRONGHOLDDROID_LOGFILE" to WineLog.currentLogPath(ctx)

        // Wine override list — forces the DXVK dlls we just staged
        // to be loaded as native, falling back to builtin if absent.
        val overrides = buildWineDllOverrides(cfg)
        list += "WINEDLLOVERRIDES" to overrides

        // Audio routing — points Wine's pulseaudio backend at our FIFO
        list += "PULSE_PIPE" to cfg.audioPipePath

        // PulseAudio env to keep Wine's pulse driver from spinning up its
        // own daemon (we already own the sink end of the FIFO).
        list += "PULSE_SERVER" to "null:"
        list += "PULSE_CLIENTCONFIG" to "exit-on-no-server=1"

        // Render target override — used by our internal DXVK fork that
        // reads STRONGHOLDDROID_RENDER_TARGET to size the swapchain.
        if (cfg.renderTargetWidth > 0 && cfg.renderTargetHeight > 0) {
            list += "STRONGHOLDDROID_RENDER_TARGET" to "${cfg.renderTargetWidth}x${cfg.renderTargetHeight}"
        }

        // Locale — SC expects en-US for unit glyphs
        list += "LANG" to "en_US.UTF-8"
        list += "LC_ALL" to "en_US.UTF-8"

        return list
    }

    // ------ internals ------

    private fun buildWineDllOverrides(cfg: EmulatorConfig): String {
        val overrides = mutableListOf<String>()
        // Always: pulseaudio-related libs as builtin (don't let Wine try to
        // load real libpulse)
        overrides += "pulse=native"
        overrides += "pulseaudio=native"
        // DXVK-managed backends
        overrides += "${cfg.graphicsBackend.primary.dxvkDll}=native,builtin"
        cfg.graphicsBackend.fallback?.let { fb ->
            overrides += "${fb.dxvkDll}=builtin,native"
        }
        // SC requires ddraw (DirectDraw) — even on HD versions, the game
        // uses ddraw for the menu surface, so always have it available.
        overrides += "ddraw=builtin,native"
        // dinput8 — SC uses DirectInput8 for gamepad-style axis polling
        overrides += "dinput8=builtin,native"
        // dsound — Stronghold's audio. Wine's builtin dsound routes through
        // our pulseaudio backend by default.
        overrides += "dsound=builtin"
        // d3dcompiler_47 — needed for DXVK shader cross-compile
        overrides += "d3dcompiler_47=native,builtin"
        // Suppress winemenubuilder (desktop menu integration) — pointless
        // on Android and its failures pollute the log.
        overrides += "winemenubuilder.exe=d"
        return overrides.joinToString(";")
    }

    private fun initPrefixBlocking(
        ctx: Context, profile: GameProfile, cfg: EmulatorConfig
    ) {
        // Stage the box64 WoW64 cpu dll BEFORE wineboot runs — wine's
        // wow64 layer (dlls/wow64/syscall.c load_64bit_module) looks for
        // the cpu dll at C:\windows\system32\<name>, and on arm64 hosts
        // the default name is "xtajit.dll" (see get_cpu_dll_name()).
        // Without it, every 32-bit process dies at startup.
        stageWow64CpuDll(ctx, cfg)

        // NOTE (v0.1.0 bug): this used to exec usr/bin/wineboot directly —
        // but that file is a POSIX *shell script wrapper* (wine's
        // apploader), which is at best unreliable on Android.  The correct
        // invocation is `wine wineboot --init`: the wine LOADER resolves
        // the wineboot.exe PE module from its builtin DLL directory.
        val wineLoader = File(usrDir(ctx), "bin/wine")
        if (!wineLoader.canExecute()) {
            throw IOException("wine loader missing at ${wineLoader.absolutePath}")
        }
        val pb = ProcessBuilder(
            wineLoader.absolutePath, "wineboot", "--init", "--foreground"
        ).apply {
            environment().putAll(buildEnvList(cfg).toMap())
            redirectErrorStream(true)
        }
        val proc = pb.start()
        // Drain output to logcat AND to the launch log file so failures
        // are visible to the user in-app (see WineLog.readTail).
        val logFile = File(WineLog.currentLogPath(ctx))
        logFile.parentFile?.mkdirs()
        val reader = proc.inputStream.bufferedReader()
        Thread {
            reader.forEachLine {
                Log.d(TAG, "[wineboot] $it")
                synchronized(WineLog.LOCK) {
                    runCatching { logFile.appendText("[wineboot] $it\n") }
                }
            }
        }.start()
        val rc = proc.waitFor()
        if (rc != 0) error("wineboot failed with rc=$rc — see ${logFile.absolutePath}")

        // Re-stage the wow64 cpu dll — wineboot's prefix creation may have
        // reset drive_c/windows/system32 between the two staging passes.
        stageWow64CpuDll(ctx, cfg)

        // Apply per-profile registry tweaks (this is what the Wine prefix's
        // user.reg file contains — appending here lets us tweak the registry
        // before the game launches).
        applyRegistryTweaks(cfg, profile)
    }

    /**
     * Copies the box64 WoW64 cpu dll (wow64/wowbox64.dll from the runtime
     * asset) into the prefix's 64-bit system dir as `xtajit.dll`.
     */
    private fun stageWow64CpuDll(ctx: Context, cfg: EmulatorConfig) {
        val src = File(ctx.filesDir, "wow64/wowbox64.dll")
        if (!src.exists()) {
            Log.w(TAG, "wow64/wowbox64.dll missing — 32-bit games will not run")
            return
        }
        val sys32 = File(cfg.winePrefix, "drive_c/windows/system32")
        sys32.mkdirs()
        src.copyTo(File(sys32, "xtajit.dll"), overwrite = true)
        Log.d(TAG, "staged xtajit.dll (box64 wow64 cpu dll) into prefix")
    }

    private fun applyRegistryTweaks(cfg: EmulatorConfig, profile: GameProfile) {
        val userReg = File(cfg.winePrefix, "user.reg")
        if (!userReg.exists()) return
        // Append common tweaks
        userReg.appendText(
            """
            [Software\\Wine\\Direct3D] ${System.currentTimeMillis()}
            "strict_shader_math"="enabled"
            "csmt"="enabled"
            "msaa"="enabled"

            [Software\\Wine\\DirectDraw] ${System.currentTimeMillis()}
            "hw_blit"="enabled"
            "palette_emulation"="disabled"
            """.trimIndent()
        )
        if (profile.slug.startsWith("stronghold_crusader_v11")) {
            // SC 1.1 specific tweaks — the GDI ddraw renderer is the safe
            // path for the X11 display architecture: no desktop-GL context
            // is required, all blits go through winex11 XPutImage.
            // (v0.1.0 set "opengl" here, which cannot work without a GLX
            // path — the game would black-screen.)
            userReg.appendText(
                """
                [Software\\Wine\\DirectDraw] ${System.currentTimeMillis()}
                "DirectDrawRenderer"="gdi"
                "DesktopStyle"="Maximized"
                """.trimIndent()
            )
        }
    }

    /**
     * Probes the X server TCP port (127.0.0.1:6000 — XServer XSDL /
     * Termux:X11).  Wine (and wineboot!) cannot do anything useful when
     * no display is reachable, so the UI checks this BEFORE launching and
     * explains what to do instead of failing silently.
     */
    fun isXServerReachable(timeoutMs: Int = 400): Boolean =
        try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", 6000), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
}

/** Tiny helper for the per-launch wine log file shared by the whole app. */
object WineLog {
    val LOCK = Any()

    fun logDir(ctx: Context): File = File(ctx.filesDir, "logs").apply { mkdirs() }

    fun currentLogPath(ctx: Context): String =
        File(logDir(ctx), "wine.log").absolutePath

    /** Last [maxLines] lines of the launch log — shown in the error dialog. */
    fun readTail(ctx: Context, maxLines: Int = 100): String {
        val f = File(currentLogPath(ctx))
        if (!f.isFile) return "(no wine log yet)"
        val lines = f.readLines()
        return if (lines.size <= maxLines) lines.joinToString("\n")
               else lines.takeLast(maxLines).joinToString("\n")
    }
}
