package com.strongholddroid.emulator.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.strongholddroid.emulator.R
import com.strongholddroid.emulator.StrongholdDroidApp
import com.strongholddroid.emulator.emulator.EmulatorService
import com.strongholddroid.emulator.profiles.StrongholdCrusaderProfile
import kotlinx.coroutines.launch

/**
 * Top-level landing page for the app. Three tabs:
 *   1. Library       — shows installed SC profiles; tap to launch
 *   2. Performance   — current FPS / thermal / save-state info
 *   3. Settings      — opens the SettingsActivity
 *
 * We use a ViewPager2 (one fragment per tab) because the user typically
 * flips between Library and Performance during a session — single
 * Activity/Fragment navigation would force us to recreate the FPS chart
 * every time the user opened Settings and came back.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var tabs: TabLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        pager = findViewById(R.id.pager)
        tabs = findViewById(R.id.tabs)
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> LibraryFragment()
                1 -> PerformanceFragment()
                else -> SettingsFragment()
            }
        }
        TabLayoutMediator(tabs, pager) { tab, pos ->
            tab.text = when (pos) {
                0 -> getString(R.string.tab_library)
                1 -> getString(R.string.tab_performance)
                else -> getString(R.string.tab_settings)
            }
        }.attach()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(android.content.Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_about -> { showAbout(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAbout() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_text,
                com.strongholddroid.emulator.BuildConfig.VERSION_NAME))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Called from LibraryFragment's "Launch" button. */
    fun launchGame(profileSlug: String, saveSlot: Int = -1) {
        lifecycleScope.launch {
            StrongholdDroidApp.instance.let {
                EmulatorService.start(it, profileSlug, saveSlot)
            }
        }
        startActivity(android.content.Intent(this, GameManagerActivity::class.java))
    }
}
