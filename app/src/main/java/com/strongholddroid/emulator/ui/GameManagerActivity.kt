package com.strongholddroid.emulator.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.strongholddroid.emulator.R
import com.strongholddroid.emulator.controls.RtsControlOverlay
import com.strongholddroid.emulator.emulator.EmulatorCore
import com.strongholddroid.emulator.emulator.EnvironmentBuilder
import com.strongholddroid.emulator.emulator.WineLog
import com.strongholddroid.emulator.profiles.StrongholdCrusaderProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen game activity.
 *
 * v0.1.0 behaviour: it observed only EmulatorCore.running and finished
 * immediately when the launch failed — the user saw a black flash and
 * nothing else ("the emulator does nothing when I press play").  Now it:
 *
 *   • shows the live launch phase (extracting → prefix → starting → …)
 *   • on failure, shows the error message + the wine log tail with
 *     Retry / Back actions
 *   • while running, tells the user to look at the X server app screen
 *     and offers a Stop button
 *
 * The game itself renders into the external X server (XServer XSDL),
 * not into this activity's surface — the SurfaceView stays as the
 * future home for an embedded X server.
 */
class GameManagerActivity : AppCompatActivity() {

    private lateinit var overlay: RtsControlOverlay
    private lateinit var statusText: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var runningHint: TextView
    private lateinit var stopBtn: Button
    private lateinit var logBtn: Button
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastBackPressMs: Long = 0
    private var failureShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_manager)
        overlay = findViewById(R.id.rtsOverlay)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.launchProgress)
        runningHint = findViewById(R.id.runningHint)
        stopBtn = findViewById(R.id.stopBtn)
        logBtn = findViewById(R.id.logBtn)

        overlay.initialize(
            com.strongholddroid.emulator.controls.ControlProfiles.defaultFor(
                StrongholdCrusaderProfile.SLUG_V11),
            surfaceW = { window.decorView.width },
            surfaceH = { window.decorView.height },
        )

        stopBtn.setOnClickListener {
            EmulatorCore.requestShutdown()
            finish()
        }
        logBtn.setOnClickListener { showWineLog() }

        // Observe the launch phase — the single source of truth for the UI.
        scope.launch {
            EmulatorCore.phase.collectLatest { phase ->
                renderPhase(phase)
            }
        }
    }

    private fun renderPhase(phase: EmulatorCore.LaunchPhase) {
        progressBar.visibility = View.VISIBLE
        runningHint.visibility = View.GONE
        stopBtn.visibility = View.GONE
        when (phase) {
            is EmulatorCore.LaunchPhase.Idle ->
                statusText.setText(R.string.phase_idle)
            is EmulatorCore.LaunchPhase.ExtractingRuntime ->
                statusText.setText(R.string.phase_extracting)
            is EmulatorCore.LaunchPhase.InitializingPrefix ->
                statusText.setText(R.string.phase_prefix)
            is EmulatorCore.LaunchPhase.Starting ->
                statusText.setText(R.string.phase_starting)
            is EmulatorCore.LaunchPhase.Running -> {
                statusText.setText(R.string.phase_running)
                progressBar.visibility = View.GONE
                runningHint.visibility = View.VISIBLE
                stopBtn.visibility = View.VISIBLE
            }
            is EmulatorCore.LaunchPhase.Exited -> {
                statusText.text = getString(R.string.phase_idle)
                progressBar.visibility = View.GONE
            }
            is EmulatorCore.LaunchPhase.Failed -> {
                progressBar.visibility = View.GONE
                if (!failureShown) {
                    failureShown = true
                    showFailureDialog(phase.message)
                }
            }
        }
    }

    private fun showFailureDialog(message: String) {
        val logTail = WineLog.readTail(this, 40)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.launch_failed_title)
            .setMessage(getString(R.string.phase_failed) + ":\n\n" + message +
                "\n\n────────\n" + logTail)
            .setPositiveButton(R.string.view_wine_log) { _, _ -> showWineLog() }
            .setNegativeButton(android.R.string.ok) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showWineLog() {
        val log = WineLog.readTail(this, 100)
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            text = log
            setTextIsSelectable(true)
            setPadding(32, 24, 32, 24)
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE)
        }
        scroll.addView(tv)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wine_log_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
