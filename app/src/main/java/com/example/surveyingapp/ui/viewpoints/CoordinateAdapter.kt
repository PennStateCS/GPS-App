package com.example.surveyingapp.ui.viewpoints

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R
import com.example.surveyingapp.data.Coordinate
import com.example.surveyingapp.ui.settings.SettingsFragment

class CoordinateAdapter(
    private var coordinates: List<Coordinate>,
    private val onDelete: (String) -> Unit,
    private val onEdit: (Coordinate) -> Unit,
    private val context: Context
) : RecyclerView.Adapter<CoordinateAdapter.PointViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PointViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_point, parent, false)
        return PointViewHolder(view)
    }

    override fun onBindViewHolder(holder: PointViewHolder, position: Int) {
        val coordinate = coordinates[position]
        holder.title.text = coordinate.name

        // Set icon and apply the user-selected color
        val iconResId = holder.itemView.context.resources.getIdentifier(coordinate.icon, "drawable", holder.itemView.context.packageName)
        if (iconResId != 0) {
            holder.icon.setImageResource(iconResId)
        } else {
            holder.icon.setImageResource(R.drawable.ic_menu_camera) // fallback icon
        }

        // Apply the user-selected color to the icon
        holder.icon.setColorFilter(coordinate.color)

        // Check if coordinates should be shown
        val preferences = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val showCoordinates = preferences.getBoolean(SettingsFragment.PREF_SHOW_COORDINATES, false)
        val showElevation = preferences.getBoolean(SettingsFragment.PREF_SHOW_ELEVATION, false)

        if (showCoordinates) {
            holder.coordinates.visibility = View.VISIBLE
            if (showElevation) {
                holder.coordinates.text = String.format("%.6f, %.6f, %.2fm", coordinate.latitude, coordinate.longitude, coordinate.altitude)
            } else {
                holder.coordinates.text = String.format("%.6f, %.6f", coordinate.latitude, coordinate.longitude)
            }
        } else {
            holder.coordinates.visibility = View.GONE
        }

        // Set up delete button with confirmation dialog
        holder.delete.setOnClickListener {
            AlertDialog.Builder(holder.itemView.context)
                .setTitle("Delete Coordinate")
                .setMessage("Are you sure you want to delete \"${coordinate.name}\"? This action cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    onDelete(coordinate.id)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        // Set up edit button
        holder.edit.setOnClickListener {
            onEdit(coordinate)
        }
    }

    override fun getItemCount(): Int = coordinates.size

    fun updatePoints(newPoints: List<Coordinate>) {
        coordinates = newPoints
        notifyDataSetChanged()
    }

    class PointViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.point_icon)
        val title: TextView = itemView.findViewById(R.id.point_title)
        val coordinates: TextView = itemView.findViewById(R.id.point_coordinates)
        val delete: Button = itemView.findViewById(R.id.point_delete)
        val edit: Button = itemView.findViewById(R.id.point_edit)
    }
}
