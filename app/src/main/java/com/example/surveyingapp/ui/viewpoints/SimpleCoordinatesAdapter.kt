package com.example.surveyingapp.ui.viewpoints

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R
import com.example.surveyingapp.data.Point
import com.example.surveyingapp.ui.settings.SettingsFragment

class SimpleCoordinatesAdapter(
    private val onEdit: (Point) -> Unit,
    private val onDelete: (Point) -> Unit
) : RecyclerView.Adapter<SimpleCoordinatesAdapter.Holder>() {
    private var items: List<Point> = emptyList()

    fun submit(list: List<Point>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_simple_coordinate, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val p = items[position]
        holder.name.text = p.name
        var showCoords = false
        var showElevation = false
        try {
            val prefs = holder.itemView.context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            showCoords = prefs.getBoolean(SettingsFragment.PREF_SHOW_COORDINATES, false)
            showElevation = prefs.getBoolean(SettingsFragment.PREF_SHOW_ELEVATION, false)
        } catch (e: Exception) {
            Log.e("SimpleCoordinatesAdapter", "Preference read failed: ${e.message}")
        }
        if (showCoords) {
            holder.coords.visibility = View.VISIBLE
            holder.coords.text = if (showElevation) {
                String.format("%.6f, %.6f, %.2fm", p.latitude, p.longitude, p.altitude)
            } else {
                String.format("%.6f, %.6f", p.latitude, p.longitude)
            }
        } else {
            holder.coords.visibility = View.GONE
        }
        val resId = holder.itemView.context.resources.getIdentifier(p.icon, "drawable", holder.itemView.context.packageName)
        if (resId != 0) holder.icon.setImageResource(resId) else holder.icon.setImageResource(R.drawable.ic_menu_camera)
        holder.icon.setColorFilter(p.color)
        holder.editBtn.setOnClickListener { onEdit(p) }
        holder.deleteBtn.setOnClickListener { onDelete(p) }
        val bgRes = if (position % 2 == 0) R.color.coordinate_row_even else R.color.coordinate_row_odd
        holder.itemView.setBackgroundResource(bgRes)
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.image_icon)
        val name: TextView = v.findViewById(R.id.text_name)
        val coords: TextView = v.findViewById(R.id.text_coords)
        val editBtn: ImageButton = v.findViewById(R.id.button_edit)
        val deleteBtn: ImageButton = v.findViewById(R.id.button_delete)
    }
}
