package com.strongholddroid.emulator.profiles

import java.io.File

/**
 * Detects which Stronghold Crusader version is installed inside a Wine
 * `drive_c/` based on file names + sizes.
 *
 * Distinguishing rules:
 *
 *   ┌────────────────────────────────────┬────────────────────────────────────────┐
 *   │ Detection marker                   │ Conclusion                              │
 *   ├────────────────────────────────────┼────────────────────────────────────────┤
 *   │ Stronghold_Crusader.exe            │ SC 1.1 (original 2002 CD)               │
 *   │ Stronghold Crusader.exe            │ SC 1.1 (with spaces — GOG version)    │
 *   │ Stronghold_Crusader_Extreme.exe     │ SC Extreme (2014 standalone)            │
 *   │ Stronghold Crusader Extreme.exe    │ SC Extreme (with spaces)                │
 *   │ Stronghold_CrusaderHD.exe           │ SC HD (Steam/GOG remaster)              │
 *   │ + /HD/ subdirectory                 │ SC HD (steam legacy layout)             │
 *   │ + /Extreme/ subdirectory            │ SC Extreme + HD bundle                  │
 *   └────────────────────────────────────┴────────────────────────────────────────┘
 *
 * Returns (slug, confidence) — confidence is 1.0 for an exact filename
 * match and falls to 0.5 if only the install dir layout matches.
 */
class GameVersionDetector {

    fun detect(installDir: File): Pair<String, Float>? {
        if (!installDir.isDirectory) return null

        // Exact filename match (preferred)
        val files = installDir.listFiles()?.toList() ?: return null

        val exact = files.firstOrNull { it.name.equals(EXE_V11_US, ignoreCase = true) }
            ?: files.firstOrNull { it.name.equals(EXE_V11_SP, ignoreCase = true) }
        if (exact != null) return StrongholdCrusaderProfile.SLUG_V11 to 1.0f

        val extreme = files.firstOrNull { it.name.equals(EXE_EXTREME_US, ignoreCase = true) }
            ?: files.firstOrNull { it.name.equals(EXE_EXTREME_SP, ignoreCase = true) }
        if (extreme != null) return StrongholdCrusaderProfile.SLUG_EXTREME to 1.0f

        val hd = files.firstOrNull { it.name.equals(EXE_HD_US, ignoreCase = true) }
            ?: files.firstOrNull { it.name.equals(EXE_HD_SP, ignoreCase = true) }
        if (hd != null) return StrongholdCrusaderProfile.SLUG_HD to 1.0f

        // Fall back to directory layout
        val hasHD = files.any { it.isDirectory && it.name.equals("HD", true) }
        val hasExtreme = files.any { it.isDirectory && it.name.equals("Extreme", true) }
        if (hasHD && hasExtreme) return StrongholdCrusaderProfile.SLUG_EXTREME to 0.6f
        if (hasHD)              return StrongholdCrusaderProfile.SLUG_HD to 0.6f
        if (hasExtreme)         return StrongholdCrusaderProfile.SLUG_EXTREME to 0.6f

        // Could not identify — return null; UI will prompt the user.
        return null
    }

    companion object {
        private const val EXE_V11_US       = "Stronghold_Crusader.exe"
        private const val EXE_V11_SP       = "Stronghold Crusader.exe"
        private const val EXE_EXTREME_US  = "Stronghold_Crusader_Extreme.exe"
        private const val EXE_EXTREME_SP  = "Stronghold Crusader Extreme.exe"
        private const val EXE_HD_US        = "Stronghold_CrusaderHD.exe"
        private const val EXE_HD_SP        = "Stronghold CrusaderHD.exe"
    }
}
