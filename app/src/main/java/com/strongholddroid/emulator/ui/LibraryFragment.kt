package com.strongholddroid.emulator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.strongholddroid.emulator.R
import com.strongholddroid.emulator.StrongholdDroidApp
import com.strongholddroid.emulator.profiles.GameProfile
import com.strongholddroid.emulator.profiles.StrongholdCrusaderProfile

/**
 * Lists the installed game profiles. Tapping a profile calls
 * [MainActivity.launchGame] which starts the EmulatorService and the
 * GameManagerActivity.
 *
 * For the prototype the library always shows the three bundled
 * Stronghold Crusader profiles. The user can install game files via
 * Settings → Library → Install (which invokes the GameInstaller).
 */
class LibraryFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_library, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.libraryRecycler)
        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = ProfileAdapter(StrongholdCrusaderProfile.ALL) { p ->
            (activity as? MainActivity)?.launchGame(p.slug)
        }
    }
}

class ProfileAdapter(
    private val items: List<GameProfile>,
    private val onLaunch: (GameProfile) -> Unit,
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    class VH(val binding: View) : RecyclerView.ViewHolder(binding)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.binding.findViewById<android.widget.TextView>(R.id.gameTitle).text = p.displayName
        holder.binding.findViewById<android.widget.TextView>(R.id.gameSubtitle).text =
            "${p.versionString} • ${p.graphicsApi.name}"
        holder.binding.findViewById<android.widget.Button>(R.id.launchBtn).setOnClickListener {
            onLaunch(p)
        }
    }

    override fun getItemCount() = items.size
}
