package com.strongholddroid.emulator.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import com.strongholddroid.emulator.profiles.GameProfile
import com.strongholddroid.emulator.profiles.GameVersionDetector
import java.io.File
import java.io.FileOutputStream

/**
 * Installs Stronghold Crusader game files into the per-profile Wine prefix.
 *
 * Supported source format:
 *   • **Pre-installed folder** — the user picks the game folder (or any
 *     parent of it) via SAF (Storage Access Framework).  The installer
 *     searches the tree for the game executable, detects the version and
 *     copies everything into the prefix's drive_c.
 *
 * Output layout (MUST match [GameProfile.gameExecutable]):
 *   <filesDir>/prefixes/<slug>/drive_c/Stronghold Crusader/Stronghold_Crusader.exe
 *   <filesDir>/prefixes/<slug>/drive_c/Stronghold Crusader HD/...
 *   <filesDir>/prefixes/<slug>/drive_c/Stronghold Crusader Extreme/...
 *
 * NOTE (v0.1.0 bug): this class used to install into
 * `<filesDir>/games/<slug>/drive_c/...` — a directory the Wine prefix never
 * reads — AND named the target folder `<slug>_Install` instead of the exact
 * folder the profiles' `gameExecutable` expects.  Both made the install
 * invisible to Wine even when it "succeeded".  Everything now goes directly
 * into [EnvironmentBuilder.prefixDir]'s drive_c with the exact folder name.
 */
class GameInstaller(private val ctx: Context) {

    private val tag = "GameInstaller"

    /** The drive_c of a profile's Wine prefix — where Wine actually looks. */
    fun driveCRoot(slug: String): File =
        File(ctx.filesDir, "prefixes/$slug/drive_c").apply { mkdirs() }

    /**
     * True when the profile's game executable is present in the prefix.
     * Used by the Library UI to show the installed state and to gate the
     * Launch button.
     */
    fun isInstalled(profile: GameProfile): Boolean {
        val exeRel = profile.gameExecutable
            .removePrefix("C:\\")
            .replace("\\", "/")
        return File(driveCRoot(profile.slug), exeRel).isFile
    }

    /**
     * Copies a pre-installed SC folder from a SAF URI into the profile's
     * drive_c.  The picked folder may be:
     *   • the game folder itself (contains Stronghold_Crusader.exe), or
     *   • any parent (e.g. the SAF root) — the tree is searched up to
     *     [MAX_SEARCH_DEPTH] levels for the exe.
     *
     * @return the installed [GameProfile], or null when no known SC version
     *         was found in the picked tree.
     */
    fun installFromFolder(uri: Uri): GameProfile? {
        val tempDir = File(ctx.cacheDir, "install_temp_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)
                ?: error("not a tree uri: $uri")

            // Locate the folder that actually contains the game exe.
            val gameDir = findGameDir(docFile, depth = 0)
                ?: run {
                    Log.w(tag, "no SC executable found under $uri")
                    return null
                }

            Log.i(tag, "copying game files from ${gameDir.name} ...")
            val detector = GameVersionDetector()
            // Detection works on plain Files; copy the found dir to temp first.
            copyDocTree(gameDir, tempDir)
            val (slug, conf) = detector.detect(tempDir)
                ?: run { Log.w(tag, "no SC version detected in ${tempDir}"); return null }
            val profile = com.strongholddroid.emulator.profiles.GameProfileManagerHolder
                .get(ctx).bySlug(slug) ?: return null

            // Target folder name must EXACTLY match GameProfile.gameExecutable
            // (e.g. "C:\Stronghold Crusader\Stronghold_Crusader.exe").
            val targetFolder = profile.installFolderName
            val targetRoot = File(driveCRoot(profile.slug), targetFolder)
            targetRoot.mkdirs()
            tempDir.copyRecursively(targetRoot, overwrite = true)

            // Verify the exe really landed where the profile expects it.
            val exeRel = profile.gameExecutable
                .removePrefix("C:\\")
                .replace("\\", "/")
            val exeOut = File(driveCRoot(profile.slug), exeRel)
            if (!exeOut.isFile) {
                Log.e(tag, "post-install verify failed: $exeOut missing")
                return null
            }

            Log.i(tag, "installed ${profile.displayName} → ${targetRoot.absolutePath} " +
                "(confidence=$conf, size=${exeOut.length()} bytes exe)")
            return profile
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Deletes a profile's installed game (keeps the prefix itself). */
    fun uninstall(slug: String) {
        val exeRelFolder = File(ctx.filesDir, "prefixes/$slug/drive_c")
        // Only remove the game folders we created, not windows/ etc.
        exeRelFolder.listFiles()?.forEach { child ->
            if (child.name.startsWith("Stronghold", ignoreCase = true)) {
                child.deleteRecursively()
            }
        }
    }

    // ---- internals ------------------------------------------------------------

    /** DFS for a document folder that directly contains a known SC exe. */
    private fun findGameDir(
        dir: androidx.documentfile.provider.DocumentFile,
        depth: Int,
    ): androidx.documentfile.provider.DocumentFile? {
        if (depth > MAX_SEARCH_DEPTH) return null
        val files = dir.listFiles()
        val hasExe = files.any { it.isFile && GameVersionDetector.isGameExecutable(it.name ?: "") }
        if (hasExe) return dir
        // Recurse into subdirectories (Common scenario: user picks a parent
        // folder containing "Stronghold Crusader/" inside).
        for (sub in files) {
            if (sub.isDirectory) {
                findGameDir(sub, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun copyDocTree(doc: androidx.documentfile.provider.DocumentFile, dest: File) {
        doc.listFiles().forEach { copyFile(it, dest) }
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

    companion object {
        private const val MAX_SEARCH_DEPTH = 3
    }
}
