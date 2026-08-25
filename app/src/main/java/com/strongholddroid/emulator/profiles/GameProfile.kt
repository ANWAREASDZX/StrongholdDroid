package com.strongholddroid.emulator.profiles

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * StrongholdDroid game profile — describes how to launch & configure one
 * specific game version.
 *
 * Per-version profiles because:
 *   • SC 1.1 (2002) uses DirectX 7 / DirectDraw → needs wined3d path
 *   • SC HD (2014 remaster) uses DirectX 9 → DXVK works great
 *   • SC Extreme (2014 DLC) uses DirectX 9 too but has heavier unit counts
 *     → needs a more conservative render-target budget
 *
 * Each profile is persisted as JSON under assets/game_profiles/.
 */
@Parcelize
@Serializable
data class GameProfile(
    val slug: String,
    val displayName: String,
    val versionString: String,
    val versionYear: Int,
    val graphicsApi: GraphicsApi,
    val nativeResolution: Pair<Int, Int>,
    val aspectRatio: Float,                 // 4:3 = 1.333
    val gameExecutable: String,             // C:\\...\\Stronghold_Crusader.exe
    val steamAppId: Int? = null,
    val gogId: Int? = null,
    val installerExe: String? = null,       // C:\\...\\Setup.exe (GOG installer)
    val installSizeMb: Int,
    val musicFile: String? = null,          // path inside drive_c to MIDI files
    val saveStateSlots: Int = 10,
    val knownIssues: List<String> = emptyList(),
) : Parcelable {

    @Parcelize
    @Serializable
    enum class GraphicsApi { DIRECT_X_7, DIRECT_X_9, DIRECT_X_9_EX }

    fun supportsHd(): Boolean = graphicsApi != GraphicsApi.DIRECT_X_7
}
