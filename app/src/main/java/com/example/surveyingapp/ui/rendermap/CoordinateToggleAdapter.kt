package com.example.surveyingapp.ui.rendermap

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R

data class CoordinateToggleItem(val id: String, val name: String, val checked: Boolean)

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
        holder.box.setOnCheckedChangeListener(null)
        holder.box.isChecked = item.checked
        val clickListener = View.OnClickListener {
            val newChecked = !holder.box.isChecked
            holder.box.isChecked = newChecked
            onToggle(item.id, newChecked)
        }
        holder.itemView.setOnClickListener(clickListener)
        holder.name.setOnClickListener(clickListener)
        holder.box.setOnCheckedChangeListener { _, isChecked -> onToggle(item.id, isChecked) }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val box: CheckBox = v.findViewById(R.id.check_visible)
        val name: TextView = v.findViewById(R.id.text_name)
    }
}

