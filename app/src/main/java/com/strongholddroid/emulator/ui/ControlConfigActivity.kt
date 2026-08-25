package com.strongholddroid.emulator.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.strongholddroid.emulator.R

/**
 * Tiny activity that hosts [ControlConfigFragment] inside the user's
 * prefered orientation (landscape) so that the bind-visualizer overlay
 * matches the actual game layout.
 */
class ControlConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control_config)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.control_config_container, ControlConfigFragment(), "cfg")
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    companion object {
        fun launch(ctx: Context) {
            ctx.startActivity(Intent(ctx, ControlConfigActivity::class.java))
        }
    }
}
