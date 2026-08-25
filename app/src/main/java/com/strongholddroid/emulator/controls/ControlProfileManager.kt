package com.strongholddroid.emulator.controls

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists [ControlProfile]s to internal storage as JSON.
 *
 * File layout (under <filesDir>/control_profiles/):
 *   <game_slug>.json    — the user-customized profile
 *   <game_slug>.bak     — previous version (so the Settings page can
 *                          offer a "reset to defaults" with undo)
 *
 * Loading order:
 *   1. User JSON if present
 *   2. Bundled asset (assets/control_profiles/<slug>.json) if present
 *   3. [ControlProfiles.defaultFor] as final fallback
 */
class ControlProfileManager(private val ctx: Context) {

    private val dir = File(ctx.filesDir, "control_profiles").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(slug: String): ControlProfile {
        // 1. User file
        val user = File(dir, "$slug.json")
        if (user.exists()) {
            try {
                return json.decodeFromString<ControlProfile>(user.readText())
            } catch (t: Throwable) {
                Log.w(TAG, "user profile parse failed for $slug, falling back", t)
                user.copyTo(File(dir, "$slug.corrupt.${System.currentTimeMillis()}"), overwrite = true)
                user.delete()
            }
        }
        // 2. Bundled asset
        try {
            ctx.assets.open("control_profiles/$slug.json").use { stream ->
                val txt = stream.bufferedReader().readText()
                return json.decodeFromString<ControlProfile>(txt)
            }
        } catch (_: Throwable) { /* fallthrough */ }
        // 3. Defaults
        return ControlProfiles.defaultFor(slug)
    }

    fun save(profile: ControlProfile) {
        val slug = profile.gameProfileSlug
        val target = File(dir, "$slug.json")
        if (target.exists()) {
            target.copyTo(File(dir, "$slug.bak"), overwrite = true)
        }
        target.writeText(json.encodeToString(ControlProfile.serializer(), profile))
        Log.i(TAG, "saved profile for $slug")
    }

    fun resetToDefaults(slug: String) {
        val user = File(dir, "$slug.json")
        if (user.exists()) {
            user.copyTo(File(dir, "$slug.bak"), overwrite = true)
            user.delete()
            Log.i(TAG, "reset profile for $slug to defaults")
        }
    }

    fun listAll(): List<ControlProfile> = dir.listFiles()
        ?.filter { it.name.endsWith(".json") }
        ?.map { runCatching { json.decodeFromString<ControlProfile>(it.readText()) }.getOrNull() }
        ?.filterNotNull() ?: emptyList()

    companion object {
        private const val TAG = "ControlProfileMgr"
    }
}
