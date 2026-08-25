package com.strongholddroid.emulator.performance

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * Per-device-class performance target. Used by:
 *   • [com.strongholddroid.emulator.graphics.DynamicResolutionScaler]
 *     to pick the target FPS
 *   • [com.strongholddroid.emulator.graphics.GraphicsBackendSelector]
 *     to pick the [com.strongholddroid.emulator.emulator.EmulatorConfig.GraphicsBackend.DynamicResolution] preset
 *   • The Settings UI to expose the targets to the user
 */
@Parcelize
@Serializable
data class PerformanceProfile(
    val name: String,
    val targetFps: Int,
    val minScale: Float,
    val maxScale: Float,
    val audioBufferFrames: Int,
    val box64BigBlock: Int,
    val box64StrongMem: Int,
    val renderTargetDefault: Pair<Int, Int>,
) : Parcelable {

    companion object {
        val LOW_END    = PerformanceProfile(
            name = "Low-end (Snapdragon 665, Mali-G52)",
            targetFps = 30, minScale = 0.75f, maxScale = 1.0f,
            audioBufferFrames = 735,     // ~17 ms at 44.1 kHz
            box64BigBlock = 1, box64StrongMem = 1,
            renderTargetDefault = 960 to 540,
        )
        val MID_RANGE = PerformanceProfile(
            name = "Mid-range (Snapdragon 730G, Mali-G76)",
            targetFps = 45, minScale = 0.85f, maxScale = 1.0f,
            audioBufferFrames = 588,
            box64BigBlock = 1, box64StrongMem = 1,
            renderTargetDefault = 1280 to 720,
        )
        val HIGH_END   = PerformanceProfile(
            name = "High-end (Snapdragon 855+, Adreno 650)",
            targetFps = 60, minScale = 0.85f, maxScale = 1.25f,
            audioBufferFrames = 441,
            box64BigBlock = 2, box64StrongMem = 2,
            renderTargetDefault = 1920 to 1080,
        )
    }
}
