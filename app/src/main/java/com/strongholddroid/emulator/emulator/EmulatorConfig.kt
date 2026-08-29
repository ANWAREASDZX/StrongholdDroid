package com.strongholddroid.emulator.emulator

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * StrongholdDroid emulator configuration.
 *
 * This is the *resolved* configuration — meaning every field has been
 * filled in from defaults / game profile / user settings and is ready
 * to hand off to the native launcher. It is *frozen* at launch time
 * so the running emulator and its observers can compare against it
 * when the user fiddles with settings mid-session.
 */
@Parcelize
@Serializable
data class EmulatorConfig(
    /** Absolute path to a Wine prefix directory, e.g. .../prefixes/sc-hd */
    val winePrefix: String,

    /** Absolute path to the wine loader binary inside the app's private files dir */
    val wineBinary: String,

    /** Windows path of the game's main executable (e.g. C:\\Stronghold HD\\Stronghold_Crusader.exe) */
    val gameExec: String,

    /**
     * WINEARCH for the prefix. Stronghold Crusader is a 32-bit x86 game —
     * with the "Arm64 Wine WOW64" runtime (aarch64 wine + box64 wow64 cpu
     * dll) 32-bit executables run in a win32 prefix through WoW64.
     */
    val wineArch: String = "win32",

    /** Enable Wine's esync (eventfd-based sync). Requires RLIMIT_NOFILE bump. */
    val esync: Boolean = true,

    /** Enable Wine's fsync (futex-based sync). Requires kernel >= 5.16. */
    val fsync: Boolean = true,

    /** Enable Wine's new WoW64 (per-process syscall thunking). */
    val wow64: Boolean = true,

    /** Render target size for DXVK; 0 means "use screen". */
    val renderTargetWidth: Int = 0,
    val renderTargetHeight: Int = 0,

    /** Path to the audio FIFO that [com.strongholddroid.emulator.audio.AudioBridge] listens on. */
    val audioPipePath: String,

    /** Graphics backend to use. */
    val graphicsBackend: GraphicsBackend,

    /** Box64 dynarec flags. */
    val box64Dynarec: Box64DynarecFlags = Box64DynarecFlags(),

    /** Game profile slug, e.g. "stronghold_crusader_hd". */
    val gameProfileSlug: String,

    /** Save-state slot to load on launch (-1 = fresh boot). */
    val saveStateSlot: Int = -1,
) : Parcelable

/** Selects how DX7 → modern API translation happens. */
@Parcelize
@Serializable
data class GraphicsBackend(
    val primary: Backend,
    val fallback: Backend?,
    val dynamicResolution: DynamicResolution,
) : Parcelable {

    enum class Backend(val dxvkDll: String) {
        /**
         * DXVK 2.x — converts D3D9/D3D10/D3D11 to Vulkan. Best for SC HD/Extreme
         * (DirectX 9) and works on any Vulkan 1.1 device. We pass `d3d9.dll`,
         * `dxgi.dll` as native Wine overrides.
         */
        DXVK_VULKAN("d3d9.dll"),

        /**
         * wined3d → Zink (OpenGL 4.x → Vulkan). Used when DXVK fails to init
         * (rare; usually missing instance extensions). Works for SC 1.1's
         * DirectDraw path because wined3d's ddraw implementation targets
         * desktop GL, which Zink forwards to Vulkan.
         */
        WINED3D_ZINK("wined3d.dll"),

        /**
         * wined3d → gl4es — for low-end Mali GPUs that lack Vulkan drivers.
         * SC's DirectX 7 → desktop GL works well; the OpenGL ES 3.x layer
         * gl4es provides handles the GL → GLES translation.
         */
        WINED3D_GL4ES("wined3d.dll");
    }

    @Parcelize
    @Serializable
    data class DynamicResolution(
        val enabled: Boolean,
        val targetFps: Int,            // e.g. 30 for medium-end devices
        val minScale: Float,          // 0.5 means we'll drop to half-res
        val maxScale: Float,          // 1.5 means we'll go above native res
        val stepCount: Int,           // discrete steps between min & max
    ) : Parcelable {
        companion object {
            val OFF = DynamicResolution(false, 30, 1.0f, 1.0f, 1)
            val FOR_SDM665 = DynamicResolution(true, 30, 0.75f, 1.0f, 4)
            val FOR_SDM855 = DynamicResolution(true, 60, 0.85f, 1.25f, 5)
        }
    }
}

/** Flags passed to box64's `BOX64_DYNAREC_*` env vars. */
@Parcelize
@Serializable
data class Box64DynarecFlags(
    val strongArm: Boolean = true,        // BOX64_DYNAREC_STRONGARM=1
    val bigBlock: Int = 0,                // 0=off, 1=conservative, 2=aggressive
    val safeMode: Boolean = false,        // BOX64_DYNAREC_SAFE=1 — disable risky opts
    val forward: Boolean = true,          // BOX64_DYNAREC_FORWARD=1 — pass 2 hot-patches
    val strongMem: Int = 1,               // 0=off, 1=conservative, 2=aggressive
    val x87Double: Boolean = false,       // SC uses single-precision FPU; default is fine
    val x87NoBcd: Boolean = false,
    val neon: Boolean = true,
) : Parcelable {
    /** Serialise to env var KEY=VALUE list for [EnvironmentBuilder]. */
    fun toEnvList(): List<Pair<String, String>> = listOf(
        "BOX64_DYNAREC"               to if (safeMode) "0" else "1",
        "BOX64_DYNAREC_STRONGARM"     to if (strongArm) "1" else "0",
        "BOX64_DYNAREC_BIGBLOCK"      to bigBlock.toString(),
        "BOX64_DYNAREC_SAFE"          to if (safeMode) "1" else "0",
        "BOX64_DYNAREC_FORWARD"       to if (forward) "1" else "0",
        "BOX64_DYNAREC_STRONGMEM"     to strongMem.toString(),
        "BOX64_DYNAREC_X87DOUBLE"     to if (x87Double) "1" else "0",
        "BOX64_DYNAREC_FASTNAN"       to if (neon) "1" else "0",
        "BOX64_DYNAREC_FASTROUND"     to "1",
        "BOX64_DYNAREC_BLEND"         to "0",
        "BOX64_DYNAREC_CALLRET"       to "0",
        "BOX64_DYNAREC_FASTFORWARD"   to "0",
        "BOX64_DYNAREC_FASTUNALIGN"   to "1",
        "BOX64_ROLLING_LOG"           to "0",
        "BOX64_NOALIGN"               to "1",
    )
}
