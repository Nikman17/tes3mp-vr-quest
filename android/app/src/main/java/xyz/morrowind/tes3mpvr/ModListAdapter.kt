package xyz.morrowind.tes3mpvr

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

class ModListAdapter(
    private var mods: MutableList<CfgManager.ModEntry>,
    private val onModChanged: () -> Unit
) : RecyclerView.Adapter<ModListAdapter.ModViewHolder>() {

    class ModViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(android.R.id.checkbox)
        val textView: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_multiple_choice, parent, false)
        return ModViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModViewHolder, position: Int) {
        val mod = mods[position]
        holder.textView.text = mod.filename
        holder.checkBox.isChecked = mod.enabled
        
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            mod.enabled = isChecked
            onModChanged()
        }
    }

    override fun getItemCount() = mods.size

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(mods, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(mods, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        onModChanged()
    }
    
    fun updateData(newMods: List<CfgManager.ModEntry>) {
        mods.clear()
        mods.addAll(newMods)
        notifyDataSetChanged()
    }
    
    fun getItems() = mods.toList()
}
