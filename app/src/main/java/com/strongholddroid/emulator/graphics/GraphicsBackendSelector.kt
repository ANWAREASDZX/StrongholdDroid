package com.strongholddroid.emulator.graphics

import android.content.Context
import android.os.Build
import android.util.Log
import com.strongholddroid.emulator.emulator.EmulatorConfig
import com.strongholddroid.emulator.emulator.GraphicsBackend
import com.strongholddroid.emulator.profiles.GameProfile
import com.strongholddroid.emulator.profiles.StrongholdCrusaderProfile

/**
 * Decides which [EmulatorConfig.GraphicsBackend] should be used for a given
 * device + game combination. This is the *only* place where the choice is
 * made — every other component just honors the [EmulatorConfig] it receives.
 *
 * Decision matrix (arm64-v8a target):
 *
 *  ┌─────────────────────────┬──────────────────────┬──────────────────────┐
 *  │ Profile \ GPU family    │ Adreno 650+, Mali-G78 │ Mali-G52, Adreno 506  │
 *  ├─────────────────────────┼──────────────────────┼──────────────────────┤
 *  │ SC HD / Extreme (DX9)   │ DXVK_VULKAN          │ WINED3D_GL4ES         │
 *  │ SC 1.1     (DDraw)      │ WINED3D_ZINK         │ WINED3D_GL4ES         │
 *  └─────────────────────────┴──────────────────────┴──────────────────────┘
 *
 * Why Zink for SC 1.1 on high-end?
 *   DXVK does NOT support ddraw. Wine's builtin wined3d *does* implement
 *   ddraw, and on a desktop GL context that GL code path is mature. Zink
 *   bridges desktop GL → Vulkan, so we still get Vulkan's better driver
 *   model (no GL scheduler stall, no ANGLE wrapper) without paying for a
 *   full ddraw rewrite. On Adreno 506-class hardware, GL drivers are
 *   suspect and gl4es's heavy fast-path optimizations matter more, so
 *   we keep everything in GLES-via-gl4es.
 */
object GraphicsBackendSelector {

    private const val TAG = "GfxSelector"

    fun select(ctx: Context, profile: GameProfile): GraphicsBackend {
        val deviceClass = DeviceClassifier.classify(ctx)
        val isVulkanReady = VulkanDetector.isUsable(ctx)

        Log.i(TAG, "deviceClass=$deviceClass vulkanReady=$isVulkanReady " +
            "profile=${profile.slug}")

        val (primary, fallback, dynRes) = when (profile.slug) {
            StrongholdCrusaderProfile.SLUG_V11 -> when {
                deviceClass == DeviceClass.HIGH_END && isVulkanReady ->
                    Triple(GraphicsBackend.Backend.WINED3D_ZINK,
                           GraphicsBackend.Backend.WINED3D_GL4ES,
                           GraphicsBackend.DynamicResolution.FOR_SDM855)
                else ->
                    Triple(GraphicsBackend.Backend.WINED3D_GL4ES,
                           null,
                           GraphicsBackend.DynamicResolution.FOR_SDM665)
            }
            StrongholdCrusaderProfile.SLUG_HD,
            StrongholdCrusaderProfile.SLUG_EXTREME -> when {
                isVulkanReady && deviceClass != DeviceClass.LOW_END ->
                    Triple(GraphicsBackend.Backend.DXVK_VULKAN,
                           GraphicsBackend.Backend.WINED3D_GL4ES,
                           if (deviceClass == DeviceClass.HIGH_END)
                               GraphicsBackend.DynamicResolution.FOR_SDM855
                           else
                               GraphicsBackend.DynamicResolution.FOR_SDM665)
                else ->
                    Triple(GraphicsBackend.Backend.WINED3D_GL4ES,
                           null,
                           GraphicsBackend.DynamicResolution.FOR_SDM665)
            }
            else -> Triple(GraphicsBackend.Backend.WINED3D_GL4ES, null,
                GraphicsBackend.DynamicResolution.FOR_SDM665)
        }

        // Save the decision so EmulatorConfig can re-hydrate on relaunch
        BackendPrefs.write(ctx, profile.slug, primary.name, fallback?.name)

        return GraphicsBackend(
            primary = primary,
            fallback = fallback,
            dynamicResolution = dynRes,
        )
    }
}

/** Three-tier device classification for graphics presets. */
enum class DeviceClass {
    LOW_END,    // SD 665 / Mali-G52 class — Vulkan likely present but slow
    MID_RANGE,  // SD 730-765 / Mali-G76
    HIGH_END,  // SD 855+ / Mali-G78 / Adreno 650+
}

object DeviceClassifier {

    fun classify(ctx: Context): DeviceClass {
        val soc = SocDetector.detect()
        // Adreno
        val gpu = (Build.SUPPORTED_ABIS.firstOrNull() ?: "")
        // Best heuristic: RAM + cores + clock
        val cores = Runtime.getRuntime().availableProcessors()
        val ramMb = readTotalRamMb()
        return when {
            cores >= 8 && ramMb >= 8192 && soc in SocDetector.HIGH_END_SOCs ->
                DeviceClass.HIGH_END
            cores >= 6 && ramMb >= 6144 && soc in SocDetector.MID_SOCs    ->
                DeviceClass.MID_RANGE
            else -> DeviceClass.LOW_END
        }
    }

    private fun readTotalRamMb(): Long = try {
        val reader = java.io.RandomAccessFile("/proc/meminfo", "r")
        var line = reader.readLine()
        while (line != null && !line.startsWith("MemTotal:")) {
            line = reader.readLine()
        }
        reader.close()
        line?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.div(1024) ?: 0L
    } catch (_: Throwable) { 0L }
}

object SocDetector {
    val HIGH_END_SOCs = setOf(
        "Snapdragon 8 Gen 1", "Snapdragon 8 Gen 2", "Snapdragon 8 Gen 3",
        "Snapdragon 888", "Snapdragon 870", "Snapdragon 865", "Snapdragon 855",
        "Exynos 2200", "Exynos 2100", "Google Tensor G2", "Google Tensor G3",
        "Dimensity 9000", "Dimensity 9200", "Dimensity 9300",
    )
    val MID_SOCs = setOf(
        "Snapdragon 7 Gen 1", "Snapdragon 778G", "Snapdragon 765G", "Snapdragon 730",
        "Dimensity 1080", "Dimensity 920", "Exynos 1380", "Exynos 1280",
    )

    fun detect(): String {
        val buildSoc = Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL
        return buildSoc.takeIf { it.isNotBlank() } ?: "Generic ${Build.HARDWARE}"
    }
}

object BackendPrefs {
    private const val PREFS_NAME = "graphics_backend"

    fun write(ctx: Context, profileSlug: String, primary: String, fallback: String?) {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString("primary_$profileSlug", primary)
            .putString("fallback_$profileSlug", fallback)
            .apply()
    }

    fun read(ctx: Context, profileSlug: String): Pair<String?, String?> {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString("primary_$profileSlug", null) to
               sp.getString("fallback_$profileSlug", null)
    }
}
