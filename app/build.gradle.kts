import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Load local.properties if present (for SDK/NDK/prebuilt paths)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val prebuiltDir = (localProps.getProperty("native.prebuilt.dir")
    ?: "${rootDir}/app/src/main/cpp/prebuilt")

android {
    namespace = "com.strongholddroid.emulator"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.strongholddroid.emulator"
        minSdk = 26          // Android 8.0+ — required for AAudio + Vulkan 1.1
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-alpha"

        // StrongholdDroid ships with native bridges that wrap libwine/libbox64.
        // The prebuilt libs for these are NOT in the repo (too large) —
        // they are produced by scripts/build_all.sh and dropped into `prebuiltDir`.
        // The CMake build will fail loudly if the libs are missing rather than
        // silently producing a broken APK.

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26",
                    "-DSTRONGHOLDDROID_PREBUILT_DIR=$prebuiltDir"
                )
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                // Aggressive LTO is set per-ABI in release builds below.
                // NOTE: arm64-v8a ONLY — scripts/build_all.sh produces no
                // x86_64 prebuilts, and app/src/main/cpp/CMakeLists.txt
                // hard-fails for any ABI whose prebuilt dir is missing.
                abiFilters += listOf("arm64-v8a")
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Release signing is ONLY configured when the keystore env vars are
        // present.  Without this guard AGP would try to read "/dev/null" as
        // a keystore and every assembleRelease/assembleCi would die with
        // "Keystore file not found" — the ci buildType inherits from release
        // via initWith(), so it must stay signable without secrets too.
        val keystorePath = System.getenv("STRONGHOLDDROID_KEYSTORE")
        val storePasswordEnv = System.getenv("STRONGHOLDDROID_STORE_PASSWORD")
        val keyAliasEnv = System.getenv("STRONGHOLDDROID_KEY_ALIAS")
        val keyPasswordEnv = System.getenv("STRONGHOLDDROID_KEY_PASSWORD")
        if (keystorePath != null && File(keystorePath).exists()) {
            create("release") {
                storeFile = File(keystorePath)
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Faster builds during control-system iteration
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Debug" } }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Fall back to the debug keystore when no release secrets are
            // configured — the release variant stays installable and the
            // produced APK keeps the standard `app-release.apk` name.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            // Native LTO for faster runtime in release
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_ARM_NEON=TRUE")
                    cppFlags += listOf("-O3", "-flto=auto", "-fvisibility=hidden")
                }
            }
            // Split APKs by ABI to reduce download size on each device class
            ndk { abiFilters += listOf("arm64-v8a") } // x86_64 excluded from release
        }
        create("ci") {
            // Lightweight CI build that skips heavy native LTO to keep CI under 30 min.
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
            // Always debug-signed: CI machines have no release keystore.
            signingConfig = signingConfigs.getByName("debug")
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo" } }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false          // Use uncompressed JNI for fast dlopen
            pickFirsts += listOf("**/libc++_shared.so")
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                // Strips debug-info from the bundled DXVK dlls
                "x86_64-w64-mingw32/**/winemenubuilder.exe"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Material 3 UI
    implementation("com.google.android.material:material:1.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // DocumentFile / SAF helpers for installing game assets
    implementation("androidx.documentfile:documentfile:1.0.1")

    // JSON ( kotlinx.serialization ) for game profiles
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Gamepad support
    implementation("androidx.core:core:1.13.1") // InputDeviceCompat, MotionEvent

    // Debug only — never ship in release
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
