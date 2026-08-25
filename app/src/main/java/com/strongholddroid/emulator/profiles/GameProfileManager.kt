package com.strongholddroid.emulator.profiles

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads [GameProfile]s from assets (shipped with the APK) and merges any
 * user overrides (under <filesDir>/game_profiles/custom/).
 */
class GameProfileManager(private val ctx: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, GameProfile>()

    init { loadAll() }

    fun bySlug(slug: String): GameProfile? = cache[slug]

    fun all(): List<GameProfile> = cache.values.toList()

    fun detectFromInstallDir(installDir: File): GameProfile? {
        val detector = GameVersionDetector()
        return detector.detect(installDir)?.let { (slug, _) -> bySlug(slug) }
    }

    private fun loadAll() {
        // Bundled — three SC profiles ship with the APK.
        listOf(
            StrongholdCrusaderProfile.V11,
            StrongholdCrusaderProfile.HD,
            StrongholdCrusaderProfile.EXTREME,
        ).forEach { cache[it.slug] = it }

        // User overrides
        val userDir = File(ctx.filesDir, "game_profiles/custom")
        if (userDir.exists()) {
            userDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
                try {
                    val p = json.decodeFromString<GameProfile>(f.readText())
                    cache[p.slug] = p
                    Log.i(TAG, "loaded user profile: ${p.slug}")
                } catch (t: Throwable) {
                    Log.w(TAG, "user profile parse failed: ${f.name}", t)
                }
            }
        }
    }

    companion object { private const val TAG = "GameProfileMgr" }
}

/**
 * Singleton accessor for the [GameProfileManager]. The Application owns
 * the instance and exposes it via [com.strongholddroid.emulator.StrongholdDroidApp].
 */
object GameProfileManagerHolder {
    @Volatile private var instance: GameProfileManager? = null
    fun get(ctx: Context): GameProfileManager =
        instance ?: synchronized(this) {
            instance ?: GameProfileManager(ctx.applicationContext).also { instance = it }
        }
}
