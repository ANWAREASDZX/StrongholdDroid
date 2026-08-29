package com.strongholddroid.emulator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.SeekBarPreference
import com.strongholddroid.emulator.R

/**
 * Settings page — wraps a [PreferenceFragmentCompat] with categories for:
 *   • Graphics backend (DXVK / wined3d / gl4es)
 *   • Performance (target FPS, dynamic resolution)
 *   • Controls (mouse sensitivity, edge scroll, gesture bindings)
 *   • Diagnostics (verify binaries, box64 self-test, dump logcat)
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.beginTransaction()
            .replace(R.id.settings_container, PrefsFragment())
            .commit()
    }

    class PrefsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("diag_verify_binaries")?.setOnPreferenceClickListener {
                val mgr = com.strongholddroid.emulator.StrongholdDroidApp.containerManager
                val issues = mgr.verifyRuntime()
                val msg = if (issues.isEmpty()) "All binaries present ✓"
                          else issues.joinToString("\n") { it.toString() }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Verify runtime")
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }

            findPreference<Preference>("diag_box64_selftest")?.setOnPreferenceClickListener {
                val ts = com.strongholddroid.emulator.emulator.Box64Launcher.selfTest(requireContext())
                val msg = if (ts < 0) "Self-test binary missing"
                          else "Self-test: ${ts}ms (healthy < 2000)"
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("box64 self-test")
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
        }
    }
}
