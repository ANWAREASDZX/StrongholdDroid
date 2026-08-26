// Top-level build file for StrongholdDroid
// Plugin versions are pinned here for reproducible builds across CI and local dev.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "1.9.24" apply false
    // Required so app/build.gradle.kts can apply `kotlin.plugin.serialization`
    // without an inline version. Kotlin serialization plugin is versioned in
    // lockstep with the Kotlin compiler (both 1.9.24 here).
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
