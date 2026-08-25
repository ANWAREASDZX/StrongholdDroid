package com.strongholddroid.emulator.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import com.strongholddroid.emulator.profiles.GameProfile
import com.strongholddroid.emulator.profiles.GameVersionDetector
import com.strongholddroid.emulator.profiles.StrongholdCrusaderProfile
import java.io.File
import java.io.FileOutputStream

/**
 * Installs Stronghold Crusader game files into a Wine `drive_c/` directory.
 *
 * Supported source formats:
 *   • **GOG installer** (zip / exe) — extracted with unzip if it's an
 *     offline GOG zip; for innoextract-style .exe, the user must convert
 *     it first (innoextract runs under Wine at runtime — too brittle).
 *   • **CD image (.iso / .bin/.cue)** — mounted via the bundled
 *     inotify-based loopback mounter (or extracted via 7z on the host).
 *   • **Pre-installed folder** — the user drops the entire SC install
 *     folder via SAF (Storage Access Framework).
 *
 * Output layout under <filesDir>/games/<profile.slug>/drive_c/:
 *   Stronghold Crusader\\Stronghold_Crusader.exe
 *   Stronghold Crusader\\*.dll
 *   Stronghold Crusader\\music\\*.mp3
 *   Stronghold Crusader\\maps\\*.map
 *   Stronghold Crusader\\sav\\*.sav
 *
 * On a successful install, the [GameProfile.gameExecutable] path inside
 * the prefix's `drive_c/` is symlinked (or copied) to the install
 * location, and the [com.strongholddroid.emulator.emulator.EnvironmentBuilder]
 * picks it up at next launch.
 */
class GameInstaller(private val ctx: Context) {

    private val tag = "GameInstaller"

    /** Returns the drive_c directory for a given game profile slug. */
    fun driveCRoot(slug: String): File =
        File(ctx.filesDir, "games/$slug/drive_c").apply { mkdirs() }

    /**
     * Copies a pre-installed SC folder from a SAF URI into the
     * drive_c/<profile>/ layout. Returns the [GameProfile] that was
     * detected, or null if the install didn't match any known version.
     */
    fun installFromFolder(uri: Uri, targetSlug: String? = null): GameProfile? {
        val tempDir = File(ctx.cacheDir, "install_temp_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            copyTree(uri, tempDir)
            val detector = GameVersionDetector()
            val (slug, conf) = detector.detect(tempDir)
                ?: run { Log.w(tag, "no SC version detected in ${tempDir}"); return null }
            val profile = com.strongholddroid.emulator.profiles.GameProfileManagerHolder
                .get(ctx).bySlug(slug) ?: return null

            val targetRoot = File(driveCRoot(profile.slug),
                "${profile.slug.replace("stronghold_crusader_", "Stronghold_Crusader_")}_Install")
            targetRoot.mkdirs()
            tempDir.copyRecursivelyTo(targetRoot, overwrite = true)

            Log.i(tag, "installed ${profile.displayName} → ${targetRoot.absolutePath} " +
                "(confidence=${conf})")
            return profile
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun copyTree(uri: Uri, dest: File) {
        val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)
            ?: error("not a tree uri: $uri")
        docFile.listFiles().forEach { child -> copyFile(child, dest) }
    }

    private fun copyFile(doc: androidx.documentfile.provider.DocumentFile, dest: File) {
        if (doc.isDirectory) {
            val sub = File(dest, doc.name ?: "unknown").apply { mkdirs() }
            doc.listFiles().forEach { copyFile(it, sub) }
        } else {
            val out = File(dest, doc.name ?: "unknown")
            ctx.contentResolver.openInputStream(doc.uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
        }
    }
}
