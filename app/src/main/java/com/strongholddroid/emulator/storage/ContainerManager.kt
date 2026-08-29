package com.strongholddroid.emulator.storage

import android.content.Context
import android.util.Log
import com.strongholddroid.emulator.profiles.GameProfile
import java.io.File

/**
 * Top-level container manager. Owns the per-profile filesystem layout
 * that Wine+box64 expect:
 *
 *   <filesDir>/
 *     usr/                 ← wine + wineserver + builtin DLLs
 *     box64/               ← box64rc + box64 shared cache
 *     prefixes/<slug>/     ← WINEPREFIX per game profile
 *     dxvk/<slug>/          ← DXVK native DLLs
 *     games/<slug>/drive_c/ ← C: drive — where the user installs SC
 *     saves/<slug>/         ← save-states
 *     control_profiles/    ← per-game control JSONs
 *     crashes/              ← per-session crash dumps
 *     thermal-history.csv  ← CSV for post-session thermal analysis
 *
 * The ContainerManager is responsible for:
 *   • Verifying the <filesDir>/usr/ binaries exist on launch
 *   • Mounting the per-profile `drive_c` read-write during a session
 *   • Compacting unused data (old saves, crashed sessions' logs)
 *   • Reporting disk usage for the Settings UI
 */
class ContainerManager(private val ctx: Context) {

    fun filesRoot(): File = ctx.filesDir

    fun wineBinary(): File = File(ctx.filesDir, "usr/bin/wine")
    fun wineServer(): File = File(ctx.filesDir, "usr/bin/wineserver")
    fun wow64CpuDll(): File = File(ctx.filesDir, "wow64/wowbox64.dll")

    fun verifyRuntime(): List<VerifyIssue> {
        val issues = mutableListOf<VerifyIssue>()
        if (!wineBinary().exists())  issues += VerifyIssue.MissingBinary("wine")
        if (!wineServer().exists())  issues += VerifyIssue.MissingBinary("wineserver")
        // The box64 WoW64 cpu dll is required for 32-bit x86 games on arm64
        if (android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }
            && !wow64CpuDll().exists())
            issues += VerifyIssue.MissingBinary("wowbox64.dll")
        // Check the runtime libpulse stub (wine's winepulse.drv links it)
        val libpulse = File(ctx.filesDir, "usr/lib/libpulse.so.0")
        if (!libpulse.exists()) issues += VerifyIssue.MissingBinary("libpulse.so.0")
        return issues
    }

    fun diskUsageBytes(): Long = ctx.filesDir.walkTopDown().map { it.length() }.sum()
    fun saveStateBytes(): Long = File(ctx.filesDir, "saves")
        .takeIf { it.exists() }?.walkTopDown()?.map { it.length() }?.sum() ?: 0L
    fun crashDumpBytes(): Long = File(ctx.filesDir, "crashes")
        .takeIf { it.exists() }?.walkTopDown()?.map { it.length() }?.sum() ?: 0L

    fun cleanupOldCrashes(keepCount: Int = 3) {
        val dir = File(ctx.filesDir, "crashes")
        if (!dir.exists()) return
        val files = dir.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > keepCount) {
            files.drop(keepCount).forEach { it.delete() }
            Log.i(TAG, "pruned ${files.size - keepCount} old crash dumps")
        }
    }

    fun ensureStructureFor(profile: GameProfile) {
        listOf(
            "usr/bin", "usr/lib", "usr/lib/wine/x86_64-windows",
            "prefixes/${profile.slug}",
            "dxvk/${profile.slug}",
            "games/${profile.slug}/drive_c",
            "saves/${profile.slug}",
            "crashes",
        ).forEach { rel ->
            File(ctx.filesDir, rel).mkdirs()
        }
    }

    sealed class VerifyIssue {
        data class MissingBinary(val name: String) : VerifyIssue()
        data class MissingPrefix(val slug: String) : VerifyIssue()
        data class PermissionDenied(val path: String) : VerifyIssue()
    }

    companion object { private const val TAG = "ContainerMgr" }
}
