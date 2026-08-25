package com.strongholddroid.emulator

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.strongholddroid.emulator.performance.ThermalManager
import com.strongholddroid.emulator.profiles.GameProfileManager
import com.strongholddroid.emulator.storage.ContainerManager

/**
 * StrongholdDroid Application class — owns process-wide singletons.
 *
 * Lifecycle responsibilities:
 *  • Load the JNI library ONCE on cold start
 *  • Boot the [ThermalManager] so it can subscribe to thermal state updates
 *  • Register a [ProcessLifecycleObserver] so we can flush save-states when
 *    the OS is about to background us (Android 14+ treats our service as
 *    a media-playback foreground service, but we still get ~5 s after the
 *    last activity finishes before the process is killed)
 */
class StrongholdDroidApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. JNI bootstrap — fail-fast if native layer is missing/broken.
        runCatching {
            System.loadLibrary("strongholddroid_jni")
        }.onFailure { t ->
            Log.e(TAG, "loadLibrary failed — native layer missing. " +
                "Did you run scripts/build_all.sh?", t)
            // We don't crash here; MainActivity shows a recovery screen
            // instructing the user how to fix it.
        }

        // 2. Singletons
        containerManager  = ContainerManager(this)
        profileManager    = GameProfileManager(this)
        thermalManager   = ThermalManager(this).also { it.start() }

        // 3. Process lifecycle hook — flush save state on backgrounding
        ProcessLifecycleOwner.get().lifecycle.addObserver(EmulatorProcessObserver())

        Log.i(TAG, "StrongholdDroid ${BuildConfig.VERSION_NAME} " +
            "initialized on API ${Build.VERSION.SDK_INT} (${Build.MANUFACTURER} ${Build.MODEL})")
    }

    companion object {
        private const val TAG = "StrongholdDroidApp"
        @Volatile lateinit var instance: StrongholdDroidApp
            private set

        @Volatile lateinit var containerManager: ContainerManager
            private set
        @Volatile lateinit var profileManager: GameProfileManager
            private set
        @Volatile lateinit var thermalManager: ThermalManager
            private set
    }
}
