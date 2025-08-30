package com.example.surveyingapp.ui.viewpoints

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R
import com.example.surveyingapp.data.Coordinate
import com.example.surveyingapp.ui.settings.SettingsFragment
import com.google.android.material.card.MaterialCardView

/**
 * RecyclerView Adapter for displaying coordinate points in a list.
 *
 * RecyclerView is Android's efficient way to display large lists by recycling views.
 * The Adapter pattern connects your data to the UI views.
 *
 * Key concepts:
 * - ViewHolder pattern: Holds references to views to avoid expensive findViewById calls
 * - onCreateViewHolder: Creates new view holders when needed
 * - onBindViewHolder: Binds data to existing view holders
 * - Higher-order functions: onEdit and onDelete are callback functions
 */
class SimpleCoordinatesAdapter(
    private val onClick: (Coordinate) -> Unit // new item click callback
) : RecyclerView.Adapter<SimpleCoordinatesAdapter.Holder>() {

    // The list of coordinate points to display
    private var items: List<Coordinate> = emptyList()
    private var selectedId: String? = null

    /**
     * Updates the list with new data and refreshes the display.
     * Called when the database data changes.
     */
    fun submit(list: List<Coordinate>) {
        // preserve selection if still present
        val currentSel = selectedId
        items = list
        if (currentSel != null && list.none { it.id == currentSel }) {
            selectedId = null
        }
        notifyDataSetChanged()  // Tells RecyclerView to refresh all visible items
    }

    /**
     * Creates a new ViewHolder when RecyclerView needs one.
     * This happens when scrolling reveals new items.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        // Inflate the layout for a single list item
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_simple_coordinate, parent, false)
        return Holder(v)
    }

    /**
     * Returns the total number of items in the list.
     * RecyclerView uses this to know how many items to display.
     */
    override fun getItemCount(): Int = items.size

    /**
     * Binds data to a ViewHolder - this is where the magic happens!
     * Called every time an item becomes visible on screen.
     */
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val p = items[position]  // Get the coordinate point for this position

        // Set the name text
        holder.name.text = p.name

        // Check user preferences to see what information to display
        var showCoords = false
        var showElevation = false
        try {
            val prefs = holder.itemView.context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            showCoords = prefs.getBoolean(SettingsFragment.PREF_SHOW_COORDINATES, false)
            showElevation = prefs.getBoolean(SettingsFragment.PREF_SHOW_ELEVATION, false)
        } catch (e: Exception) {
            Log.e("SimpleCoordinatesAdapter", "Preference read failed: ${e.message}")
        }

        // Show/hide coordinates based on user preferences
        if (showCoords) {
            holder.coords.visibility = View.VISIBLE
            holder.coords.text = if (showElevation) {
                // Show latitude, longitude, and elevation
                String.format("%.6f, %.6f, %.2fm", p.latitude, p.longitude, p.altitude)
            } else {
                // Show only latitude and longitude
                String.format("%.6f, %.6f", p.latitude, p.longitude)
            }
        } else {
            holder.coords.visibility = View.GONE  // Hide coordinates completely
        }

        // Set the icon for this coordinate point
        val resId = holder.itemView.context.resources.getIdentifier(p.icon, "drawable", holder.itemView.context.packageName)
        if (resId != 0) {
            holder.icon.setImageResource(resId)  // Use the specified icon
        } else {
            holder.icon.setImageResource(R.drawable.ic_menu_camera)  // Fallback icon
        }
        holder.icon.setColorFilter(p.color)  // Apply the coordinate's color to the icon

        // Set up click listeners for the action buttons
        holder.itemView.setOnClickListener {
            onClick(p)
        }

        // Temporarily disable selection highlighting to isolate freezing issue
        /*
        // Selection highlighting (stroke color change)
        val card = holder.itemView as? MaterialCardView
        if (card != null) {
            val ctx = card.context
            val sel = p.id == selectedId
            val colorRes = if (sel) R.color.coordinate_card_stroke_selected else R.color.coordinate_card_stroke_normal
            card.strokeColor = ContextCompat.getColor(ctx, colorRes)
        }
        */

        // Remove old alternating background resource application since card provides surface; could keep subtle alt if desired
        // (Optional) If you want alternating subtle backgrounds plus stroke, comment back in with card.setCardBackgroundColor(...)
    }

    /**
     * ViewHolder class that holds references to the views in each list item.
     *
     * This prevents the need to call findViewById repeatedly, which is expensive.
     * The ViewHolder pattern is a key optimization in RecyclerView.
     */
    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.image_icon)        // The coordinate point icon
        val name: TextView = v.findViewById(R.id.text_name)          // The coordinate point name
        val coords: TextView = v.findViewById(R.id.text_coords)      // The coordinate values (lat/lon)
    }

    fun setSelectedId(newId: String?) {
        if (selectedId == newId) return
        val oldId = selectedId
        selectedId = newId
        oldId?.let { id ->
            val oldPos = items.indexOfFirst { it.id == id }
            if (oldPos >= 0) notifyItemChanged(oldPos)
        }
        newId?.let { id ->
            val newPos = items.indexOfFirst { it.id == id }
            if (newPos >= 0) notifyItemChanged(newPos)
        }
    }
}
