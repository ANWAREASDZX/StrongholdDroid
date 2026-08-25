package com.strongholddroid.emulator.graphics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import org.jetbrains.kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Detects whether the current device has a *usable* Vulkan 1.1+ driver
 * with the extensions that DXVK and Zink require. We don't just check the
 * API level — some 2020-era Adreno drivers report Vulkan 1.1 but are
 * missing `VK_KHR_swapchain` device features that DXVK needs.
 *
 * Detection strategy:
 *   1. Try to dlopen libvulkan.so
 *   2. Load vkGetInstanceProcAddr, create a 1.1 instance with
 *      VK_KHR_surface + VK_KHR_android_surface
 *   3. Enumerate physical devices, check for:
 *        • apiVersion >= VK_MAKE_API_VERSION(1, 1, 0, 0)
 *        • supports VK_KHR_swapchain
 *        • supports VK_EXT_fragment_density_map (optional — for dynamic resolution)
 *   4. Pick the discrete GPU if multiple are present (rare on phones)
 *
 * We don't keep the instance open — the actual renderer (DXVK) opens
 * its own. This class is *pure detection*.
 */
object VulkanDetector {

    private const val TAG = "VulkanDetector"

    @Volatile private var cachedResult: Boolean? = null

    fun isUsable(ctx: Context): Boolean {
        cachedResult?.let { return it }
        val result = try {
            Build.VERSION.SDK_INT >= 28 && doDetect(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "Vulkan detection failed: ${t.message}")
            false
        }
        cachedResult = result
        return result
    }

    private fun doDetect(ctx: Context): Boolean {
        // Fast path: read ActivityManager.isLowRamDeviceFlag (true ⇒ definitely no Vulkan)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice) return false

        // Headless detection — for unit tests on JVM. Skips if libvulkan.so missing.
        if (runningOnJvm()) return false

        val lib = runCatching { System.loadLibrary("vulkan") }.getOrNull()
            ?: return false

        val ext = nativeDetectVulkan()
        Log.i(TAG, "native detect result = $ext")
        return ext
    }

    private fun runningOnJvm(): Boolean =
        System.getProperty("java.vm.name")?.startsWith("Java") == true

    // Native helper — defined in graphics_jni.cpp (not in this repo's source
    // tree yet; lives in the prebuilt libstrongholddroid_jni.so for now).
    private external fun nativeDetectVulkan(): Boolean
}

/**
 * Helpers for parsing Vulkan version ints — kept here in case we ever want
 * to expose the full version tuple to the user diagnostics page.
 */
object VkVersion {
    fun makeApiVariant(major: Int, minor: Int, patch: Int, variant: Int = 0): Int {
        return (variant shl 29) or (major shl 22) or (minor shl 12) or patch
    }
    fun parseVariant(v: Int): Int = (v ushr 29) and 0b111
    fun parseMajor(v: Int): Int  = (v ushr 22) and 0x7F
    fun parseMinor(v: Int): Int  = (v ushr 12) and 0x3FF
    fun parsePatch(v: Int): Int  = v and 0xFFF
}
