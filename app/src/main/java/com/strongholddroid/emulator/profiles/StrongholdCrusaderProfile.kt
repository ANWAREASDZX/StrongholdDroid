package com.strongholddroid.emulator.profiles

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * Stronghold Crusader's three known PC releases, expressed as
 * [GameProfile]s the rest of the app understands.
 *
 * Why hard-code them instead of JSON files?
 *   • Fewer moving parts — the parser only needs to handle user
 *     overrides, not three well-known profiles.
 *   • Version detection rules can live next to the profile data.
 *   • No asset-extraction step on first run (faster cold start).
 *
 * The user can still drop a JSON file under <filesDir>/game_profiles/custom/
 * to override any of these — see [GameProfileManager].
 */
object StrongholdCrusaderProfile {

    const val SLUG_V11     = "stronghold_crusader_v11"
    const val SLUG_HD      = "stronghold_crusader_hd"
    const val SLUG_EXTREME = "stronghold_crusader_extreme"

    val V11 = GameProfile(
        slug             = SLUG_V11,
        displayName      = "Stronghold Crusader (2002)",
        versionString    = "1.1",
        versionYear      = 2002,
        graphicsApi      = GameProfile.GraphicsApi.DIRECT_X_7,
        nativeResolution = 1024 to 768,
        aspectRatio      = 4f / 3f,
        gameExecutable   = "C:\\Stronghold Crusader\\Stronghold_Crusader.exe",
        installSizeMb    = 850,
        musicFile        = "C:\\Stronghold Crusader\\music",
        saveStateSlots   = 10,
        knownIssues      = listOf(
            "DirectDraw surface lock may fail on Adreno drivers that " +
                "don't expose GL_EXT_unpack_subimage — fall back to wined3d-software",
            "Palette animation in 8-bit surface mode requires ARB_palette_texture",
        ),
    )

    val HD = GameProfile(
        slug             = SLUG_HD,
        displayName      = "Stronghold Crusader HD (2014)",
        versionString    = "1.4-HD",
        versionYear      = 2014,
        graphicsApi      = GameProfile.GraphicsApi.DIRECT_X_9,
        nativeResolution = 1920 to 1080,
        aspectRatio      = 16f / 9f,
        gameExecutable   = "C:\\Stronghold Crusader HD\\Stronghold_CrusaderHD.exe",
        gogId            = 1207661970,
        steamAppId       = 1127900,
        installSizeMb    = 1500,
        musicFile        = "C:\\Stronghold Crusader HD\\music",
        saveStateSlots   = 10,
        knownIssues      = listOf(
            "DXVK 2.x occasionally shows a 1-frame rendering glitch when " +
                "rotating the camera by 90° — fixed by setting dxgi.maxFrameLatency = 1",
        ),
    )

    val EXTREME = GameProfile(
        slug             = SLUG_EXTREME,
        displayName      = "Stronghold Crusader Extreme (2014)",
        versionString    = "1.4-E",
        versionYear      = 2014,
        graphicsApi      = GameProfile.GraphicsApi.DIRECT_X_9_EX,
        nativeResolution = 1920 to 1080,
        aspectRatio      = 16f / 9f,
        gameExecutable   = "C:\\Stronghold Crusader Extreme\\Stronghold_Crusader_Extreme.exe",
        gogId            = 1207661971,
        steamAppId       = 1127901,
        installSizeMb    = 1700,
        musicFile        = "C:\\Stronghold Crusader Extreme\\music",
        saveStateSlots   = 10,
        knownIssues      = listOf(
            "Larger unit counts (>5000) may exceed the Wine 6.0 dsound " +
                "voices limit; see Troubleshooting → Audio → Missing SFX",
            "Bumps VRAM usage to ~512 MB; ensure DynamicResolution.minScale=0.6",
        ),
    )

    val ALL = listOf(V11, HD, EXTREME)
}
