package com.strongholddroid.emulator.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.strongholddroid.emulator.R

/**
 * On-screen editor for the [com.strongholddroid.emulator.controls.ControlProfile]
 * — lets the user rebind gamepad buttons, change gesture mappings, and
 * tune mouse sensitivity. The actual control visualizer (a transparent
 * preview showing where buttons and gestures land) lives in
 * [RtsControlOverlay]; this fragment is the *editor*.
 *
 * For the prototype we render a flat list of bindable controls; tapping
 * one opens a small dialog with a few preset options.
 */
class ControlConfigFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_control_config, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.bindingsRecycler)
        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = BindingsAdapter(BINDABLE_ITEMS)
    }

    companion object {
        private val BINDABLE_ITEMS = listOf(
            "Mouse sensitivity",
            "Edge scroll",
            "Drag lock",
            "Right-click gesture",
            "Zoom gesture",
            "Pan gesture",
            "Rotate gesture",
            "Gamepad: A",
            "Gamepad: B",
            "Gamepad: X",
            "Gamepad: Y",
            "Gamepad: L1",
            "Gamepad: R1",
            "Gamepad: L2",
            "Gamepad: R2",
            "Gamepad: Select",
            "Gamepad: Start",
            "Gamepad: L-stick click",
            "Gamepad: R-stick click",
            "Gamepad: D-pad up",
            "Gamepad: D-pad down",
            "Gamepad: D-pad left",
            "Gamepad: D-pad right",
        )
    }
}

class BindingsAdapter(private val items: List<String>) :
    RecyclerView.Adapter<BindingsAdapter.VH>() {

    class VH(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name = items[position]
        holder.view.findViewById<android.widget.TextView>(android.R.id.text1).text = name
        holder.view.findViewById<android.widget.TextView>(android.R.id.text2).text = "Tap to rebind…"
        holder.view.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(holder.view.context)
                .setTitle(name)
                .setItems(arrayOf("Default", "Disable", "Custom…")) { _, _ -> }
                .show()
        }
    }

    override fun getItemCount() = items.size
}
