package com.example.surveyingapp.ui.rendermap

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R

data class CoordinateToggleItem(val id: String, val name: String, val checked: Boolean, val icon: String, val color: Int)

class CoordinateToggleAdapter(
    private val onToggle: (id: String, checked: Boolean) -> Unit
) : RecyclerView.Adapter<CoordinateToggleAdapter.Holder>() {

    private var items: List<CoordinateToggleItem> = emptyList()

    fun submit(newItems: List<CoordinateToggleItem>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition].id == newItems[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition] == newItems[newItemPosition]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_coordinate_toggle, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.name

        // Set the icon dynamically using resource identifier (same approach as map markers)
        val context = holder.itemView.context
        val iconRes = context.resources.getIdentifier(item.icon, "drawable", context.packageName)
        if (iconRes != 0) {
            holder.icon.setImageResource(iconRes)
        } else {
            // Fallback to a default icon if the resource isn't found
            holder.icon.setImageResource(R.drawable.ic_section_location)
        }

        // Apply the correct color to the icon
        holder.icon.setColorFilter(item.color, PorterDuff.Mode.SRC_IN)

        holder.switch.setOnCheckedChangeListener(null)
        holder.switch.isChecked = item.checked
        val clickListener = View.OnClickListener {
            val newChecked = !holder.switch.isChecked
            holder.switch.isChecked = newChecked
            onToggle(item.id, newChecked)
        }
        holder.itemView.setOnClickListener(clickListener)
        holder.name.setOnClickListener(clickListener)
        holder.switch.setOnCheckedChangeListener { _, isChecked -> onToggle(item.id, isChecked) }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val switch: Switch = v.findViewById(R.id.switch_visible)
        val icon: ImageView = v.findViewById(R.id.image_icon)
        val name: TextView = v.findViewById(R.id.text_name)
    }
}
