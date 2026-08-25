package com.strongholddroid.emulator.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.strongholddroid.emulator.R

/**
 * Hosts the top-level Settings preference tree. Mostly a thin wrapper
 * so that we have a back-able activity for the Settings fragment.
 *
 * The real work happens in [SettingsFragment.PrefsFragment] (which lives
 * inside [SettingsFragment] for the tabbed version of the page).
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment.PrefsFragment(), "settings")
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
