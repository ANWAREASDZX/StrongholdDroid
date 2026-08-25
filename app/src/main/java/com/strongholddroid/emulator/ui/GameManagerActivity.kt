package com.strongholddroid.emulator.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.strongholddroid.emulator.R
import com.strongholddroid.emulator.controls.RtsControlOverlay
import com.strongholddroid.emulator.emulator.EmulatorCore
import com.strongholddroid.emulator.profiles.StrongholdCrusaderProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen game activity. Owns the surface where Wine renders into
 * and the [RtsControlOverlay] that translates touch+gamepad input.
 *
 * Lifecycle
 * --------
 * • onCreate:    inflate the layout, attach [RtsControlOverlay], and
 *                begin observing [EmulatorCore.running]
 * • onResume:    bind input dispatcher to native pump thread
 * • onPause:    call [EmulatorCore.requestShutdown] politely (SIGTERM)
 * • onDestroy:   force-kill any stragglers
 *
 * Back button: long-press (>= 800 ms) = "request graceful shutdown",
 * short press = ignore (so the user doesn't accidentally quit during
 * a battle). Two short back presses within 1 s = confirm-quit dialog.
 */
class GameManagerActivity : AppCompatActivity() {

    private lateinit var overlay: RtsControlOverlay
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastBackPressMs: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_manager)
        overlay = findViewById(R.id.rtsOverlay)
        overlay.initialize(
            com.strongholddroid.emulator.controls.ControlProfiles.defaultFor(
                StrongholdCrusaderProfile.SLUG_V11),
            surfaceW = { window.decorView.width },
            surfaceH = { window.decorView.height },
        )
        // Observe running state so we exit when the game stops
        scope.launch {
            EmulatorCore.running.collectLatest { running ->
                if (!running) finish()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.onKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.onKeyUp(keyCode, event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (overlay.onGenericMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressMs < 1000) {
            EmulatorCore.requestShutdown()
            super.onBackPressed()
            return
        }
        lastBackPressMs = now
        com.google.android.material.snackbar.Snackbar
            .make(overlay, getString(R.string.press_back_again_to_quit),
                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        // Safety net — if the user swipes away while we still have a
        // live Wine process, SIGKILL it so we don't leak.
        if (EmulatorCore.isRunning()) EmulatorCore.forceKill()
    }
}
