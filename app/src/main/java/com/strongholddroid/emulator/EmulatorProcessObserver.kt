package com.strongholddroid.emulator

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.strongholddroid.emulator.emulator.EmulatorCore
import com.strongholddroid.emulator.performance.SaveStateManager

/**
 * Observes the process lifecycle to flush save-states the instant the
 * OS notifies us we're heading to the background.
 *
 * This is *non-trivial* on Android 14+ because our `EmulatorService` is
 * declared as `mediaPlayback` foreground service, so the process may
 * survive backgrounding. But if the user swipes us away, we get ~5 s
 * to write a save-state snapshot before the kernel delivers SIGKILL.
 */
class EmulatorProcessObserver : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        if (owner !is ProcessLifecycleOwner) return
        if (EmulatorCore.isRunning()) {
            SaveStateManager.snapshotBlocking(reason = "process-onStop")
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // Reserved — we never actually receive this for the process itself.
    }
}
