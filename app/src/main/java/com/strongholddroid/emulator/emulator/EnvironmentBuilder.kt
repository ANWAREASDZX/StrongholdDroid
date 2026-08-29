package com.strongholddroid.emulator.emulator

import android.content.Context
import android.util.Log
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
 *   usr/share/wine/nls/      ← locale/codepage tables
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
     * usr/share/wine/nls, wow64/wowbox64.dll, dxvk-wine-dlls/.
     */
    fun ensureFirstRunExtraction(ctx: Context) {
        val marker = File(ctx.filesDir, ".runtime-extracted")
        val wineBin = File(usrDir(ctx), "bin/wine")
        if (marker.exists() && wineBin.canExecute()) return

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
        marker.writeText("extracted-at=${System.currentTimeMillis()}\n")
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
        list += "LD_LIBRARY_PATH" to "${usrDir(ctx)}/lib"
        list += "HOME" to cfg.winePrefix
        list += "TMPDIR" to ctx.cacheDir.absolutePath
        list += "XDG_DATA_HOME" to ctx.filesDir.absolutePath

        // Box64 config
        list += "BOX64_RCFILE" to "${ctx.filesDir}/box64/box64rc"
        list += "BOX64_LOG" to "0"
        list += cfg.box64Dynarec.toEnvList()

        // Wine config — note we don't set WINEDEBUG here because the
        // native launcher sets it to "-all" by default to silence noise.

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

        // wineboot is a separate arm64 ELF program shipped in the runtime
        // asset (usr/bin/wineboot) — NOT `wine wineboot` (the wine CLI
        // would spawn a full loader session just to run a prefix tool).
        val wineboot = File(usrDir(ctx), "bin/wineboot")
        if (!wineboot.canExecute()) {
            throw IOException("wineboot missing at ${wineboot.absolutePath}")
        }
        val pb = ProcessBuilder(
            wineboot.absolutePath, "--init", "--foreground"
        ).apply {
            environment().putAll(buildEnvList(cfg).toMap())
            redirectErrorStream(true)
        }
        val proc = pb.start()
        // Drain output to logcat
        val reader = proc.inputStream.bufferedReader()
        Thread {
            reader.forEachLine { Log.d(TAG, "[wineboot] $it") }
        }.start()
        val rc = proc.waitFor()
        if (rc != 0) error("wineboot failed with rc=$rc")

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
            // SC 1.1 specific tweaks — render at 16bpp via directdraw registry
            userReg.appendText(
                """
                [Software\\Wine\\DirectDraw] ${System.currentTimeMillis()}
                "DirectDrawRenderer"="opengl"
                "DesktopStyle"="Maximized"
                """.trimIndent()
            )
        }
    }
}
