package com.strongholddroid.emulator.emulator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.strongholddroid.emulator.R
import com.strongholddroid.emulator.profiles.GameProfileManager
import com.strongholddroid.emulator.profiles.GameProfileManagerHolder
import com.strongholddroid.emulator.profiles.StrongholdCrusaderProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Wine process alive while the user is in
 * another app. The game UI ([com.strongholddroid.emulator.ui.GameManagerActivity])
 * binds to this service for live state (fps, thermal, save-state events).
 *
 * Why a foreground service?
 * ------------------------
 * Android aggressively kills background processes; even a 2-second blip
 * (notification shade pulled down) would crash Wine. By declaring this as a
 * `mediaPlayback` service, we get ~5 minutes of guaranteed runtime after the
 * activity stops, plus the OS treats the audio session as user-perceptible
 * (so it won't kill us while audio is playing).
 */
class EmulatorService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow<State>(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()
        Log.i(TAG, "EmulatorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_STICKY  // if killed, restart with last intent if possible
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_LAUNCH -> {
                val profileSlug = intent.getStringExtra(EXTRA_PROFILE_SLUG)
                    ?: StrongholdCrusaderProfile.SLUG_V11
                val saveSlot   = intent.getIntExtra(EXTRA_SAVE_SLOT, -1)
                scope.launch { launchGame(profileSlug, saveSlot) }
            }
            ACTION_STOP -> {
                scope.launch { stopGame() }
            }
        }
    }

    private suspend fun launchGame(profileSlug: String, saveSlot: Int) {
        runCatching {
            _state.value = State.LAUNCHING
            val profile = GameProfileManagerHolder.get(this).bySlug(profileSlug)
                ?: error("Game profile '$profileSlug' not found")
            val config  = EmulatorCore.launch(profile, saveSlot)
            _state.value = State.RUNNING(config.gameProfileSlug)
        }.onFailure { t ->
            Log.e(TAG, "launch failed", t)
            _state.value = State.ERROR(t.message ?: "unknown")
        }
    }

    private suspend fun stopGame() {
        runCatching {
            _state.value = State.STOPPING
            EmulatorCore.requestShutdown()
            EmulatorCore.awaitExit()
            _state.value = State.IDLE
        }.onFailure {
            _state.value = State.ERROR(it.message ?: "unknown")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------ Notification plumbing ------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat() {
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setSmallIcon(R.drawable.ic_status)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    sealed class State {
        object IDLE : State()
        object LAUNCHING : State()
        object STOPPING : State()
        data class RUNNING(val profileSlug: String) : State()
        data class ERROR(val message: String) : State()
    }

    companion object {
        private const val TAG = "EmulatorService"
        private const val CHANNEL_ID = "strongholddroid_runtime"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_LAUNCH = "com.strongholddroid.LAUNCH"
        const val ACTION_STOP   = "com.strongholddroid.STOP"
        const val EXTRA_PROFILE_SLUG = "profile_slug"
        const val EXTRA_SAVE_SLOT    = "save_slot"

        fun start(context: Context, profileSlug: String, saveSlot: Int) {
            val i = Intent(context, EmulatorService::class.java).apply {
                action = ACTION_LAUNCH
                putExtra(EXTRA_PROFILE_SLUG, profileSlug)
                putExtra(EXTRA_SAVE_SLOT, saveSlot)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, EmulatorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(i)
        }
    }
}
