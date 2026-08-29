package com.strongholddroid.emulator.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.strongholddroid.emulator.R
import com.strongholddroid.emulator.profiles.GameProfile
import com.strongholddroid.emulator.profiles.StrongholdCrusaderProfile
import com.strongholddroid.emulator.storage.GameInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists the game profiles with their install state.
 *
 * v0.1.0 shipped NO install UI at all — GameInstaller existed but nothing
 * called it, so the user had no way to add the game.  This fragment now:
 *   • shows ✓ Installed / ✗ Not installed per profile
 *   • provides an "Install" button per profile (SAF folder picker)
 *   • gates the Launch button on the game actually being present
 *   • explains the XServer XSDL requirement before first launch
 */
class LibraryFragment : Fragment() {

    private val installer by lazy { GameInstaller(requireContext()) }
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var adapter: ProfileAdapter

    // SAF folder picker → GameInstaller.installFromFolder
    private val pickGameFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            installGameFrom(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_library, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.libraryRecycler)
        rv.layoutManager = LinearLayoutManager(context)
        adapter = ProfileAdapter(
            StrongholdCrusaderProfile.ALL,
            isInstalled = { installer.isInstalled(it) },
            onInstall = { pickGameFolder.launch(null) },
            onLaunch = { p -> confirmAndLaunch(p) },
        )
        rv.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        adapter.refreshInstalledStates()
    }

    private fun installGameFrom(uri: Uri) {
        val ctx = context ?: return
        // Keep read permission across reboots — the copy happens now, but
        // persisting costs nothing and helps a re-install later.
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val dialog = android.app.ProgressDialog(ctx).apply {
            setMessage(getString(R.string.installing_game))
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { installer.installFromFolder(uri) }
            }
            dialog.dismiss()
            result.fold(
                onSuccess = { profile ->
                    if (profile != null) {
                        Toast.makeText(
                            ctx,
                            getString(R.string.install_success, profile.displayName),
                            Toast.LENGTH_LONG
                        ).show()
                        adapter.refreshInstalledStates()
                    } else {
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle(R.string.install_failed_title)
                            .setMessage(R.string.install_not_detected)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                },
                onFailure = { t ->
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.install_failed_title)
                        .setMessage(getString(R.string.install_error, t.message ?: "?"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            )
        }
    }

    private fun confirmAndLaunch(profile: GameProfile) {
        val ctx = context ?: return
        // Explain the X server requirement — without it the game has no
        // display and dies silently (the v0.1.0 experience).
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val shown = prefs.getBoolean("xserver_hint_shown", false)
        if (!shown) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.xserver_hint_title)
                .setMessage(R.string.xserver_hint_message)
                .setPositiveButton(R.string.xserver_hint_continue) { _, _ ->
                    prefs.edit().putBoolean("xserver_hint_shown", true).apply()
                    (activity as? MainActivity)?.launchGame(profile.slug)
                }
                .setNeutralButton(R.string.xserver_hint_never) { _, _ ->
                    prefs.edit().putBoolean("xserver_hint_shown", true).apply()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            (activity as? MainActivity)?.launchGame(profile.slug)
        }
    }
}

class ProfileAdapter(
    private val items: List<GameProfile>,
    private val isInstalled: (GameProfile) -> Boolean,
    private val onInstall: (GameProfile) -> Unit,
    private val onLaunch: (GameProfile) -> Unit,
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    private val installedStates = items.map { isInstalled(it) }.toMutableList()

    fun refreshInstalledStates() {
        for (i in items.indices) {
            installedStates[i] = isInstalled(items[i])
        }
        notifyItemRangeChanged(0, items.size)
    }

    class VH(val binding: View) : RecyclerView.ViewHolder(binding)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val installed = installedStates[position]
        holder.binding.findViewById<TextView>(R.id.gameTitle).text = p.displayName
        holder.binding.findViewById<TextView>(R.id.gameSubtitle).text =
            "${p.versionString} • ${p.graphicsApi.name.removePrefix("DIRECT_X_").replace('_', ' ')}"

        val statusView = holder.binding.findViewById<TextView>(R.id.gameStatus)
        statusView.text = if (installed) {
            holder.binding.context.getString(R.string.status_installed)
        } else {
            holder.binding.context.getString(R.string.status_not_installed)
        }

        holder.binding.findViewById<Button>(R.id.installBtn).setOnClickListener { onInstall(p) }
        holder.binding.findViewById<Button>(R.id.launchBtn).apply {
            isEnabled = installed
            alpha = if (installed) 1f else 0.4f
            setOnClickListener { if (installed) onLaunch(p) }
        }
    }

    override fun getItemCount() = items.size
}
