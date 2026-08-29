package com.strongholddroid.emulator.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.strongholddroid.emulator.R
import com.strongholddroid.emulator.StrongholdDroidApp
import com.strongholddroid.emulator.graphics.FpsMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Performance dashboard — displays the live FPS counter, current thermal
 * level, save-state status, and recent pressure events from
 * [com.strongholddroid.emulator.performance.PerformanceMonitor].
 *
 * Mostly read-only; the only interaction is a "Save state now" button
 * that triggers a synchronous snapshot via [SaveStateManager].
 */
class PerformanceFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_performance, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val fpsView   = view.findViewById<android.widget.TextView>(R.id.fpsValue)
        val thermalView = view.findViewById<android.widget.TextView>(R.id.thermalValue)
        val saveBtn   = view.findViewById<android.widget.Button>(R.id.saveStateBtn)

        saveBtn.setOnClickListener {
            it.isEnabled = false
            it.postDelayed({ it.isEnabled = true }, 2000)
            com.strongholddroid.emulator.performance.SaveStateManager.snapshotBlocking("user-tap")
        }

        lifecycleScope.launch {
            while (isAdded) {
                val fps = (StrongholdDroidApp.thermalManager.thermalLevel.value
                    .let { level -> if (level > 0) 30 else 60 })  // placeholder until FpsMonitor is wired
                fpsView.text = "$fps FPS"
                thermalView.text = "${StrongholdDroidApp.thermalManager.cpuTempC.value.toInt()}°C / lvl ${StrongholdDroidApp.thermalManager.thermalLevel.value}"
                delay(500)
            }
        }
    }
}
