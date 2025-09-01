package com.example.surveyingapp.ui.viewpoints

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.ui.settings.SettingsFragment
import java.util.Locale

/**
 * RecyclerView Adapter for displaying coordinate points in a list.
 *
 * RecyclerView is Android's efficient way to display large lists by recycling views.
 * The Adapter pattern connects your data to the UI views.
 */
class SimpleCoordinatesAdapter(
    private val onClick: (Coordinate) -> Unit, // item click callback
    private val onDelete: (Coordinate) -> Unit // delete callback
) : RecyclerView.Adapter<SimpleCoordinatesAdapter.Holder>() {

    init { setHasStableIds(true) }

    // The list of coordinate points to display
    private var items: List<Coordinate> = emptyList()
    private var selectedId: String? = null

    /**
     * Updates the list with new data and refreshes the display.
     * Called when the database data changes.
     */
    fun submit(list: List<Coordinate>) {
        val old = items
        val newList = list.toList()
        // Fast path if first load
        if (old.isEmpty()) {
            items = newList
            notifyItemRangeInserted(0, newList.size)
            return
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition].id == newList[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val o = old[oldItemPosition]
                val n = newList[newItemPosition]
                return o == n // data class equality
            }
        })
        items = newList
        diff.dispatchUpdatesTo(this)
    }

    /**
     * Creates a new ViewHolder when RecyclerView needs one.
     * This happens when scrolling reveals new items.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        // Inflate the layout for a single list item
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_simple_coordinate, parent, false)
        // Inject accent view if layout (old cached version) missing it
        if (v.findViewById<View>(R.id.selection_accent) == null) {
            val container = v as? ViewGroup
            if (container != null) {
                val accent = View(parent.context)
                accent.id = R.id.selection_accent
                val widthPx = (parent.resources.displayMetrics.density * 4).toInt().coerceAtLeast(2)
                accent.layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT)
                accent.setBackgroundColor(ContextCompat.getColor(parent.context, R.color.dev_category_selected_accent))
                // Insert at start
                container.addView(accent, 0)
            }
        }
        return Holder(v)
    }

    /**
     * Returns the total number of items in the list.
     * RecyclerView uses this to know how many items to display.
     */
    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

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
                String.format(Locale.US, "%.6f, %.6f, %.2fm", p.latitude, p.longitude, p.altitude)
            } else {
                // Show only latitude and longitude
                String.format(Locale.US, "%.6f, %.6f", p.latitude, p.longitude)
            }
        } else {
            holder.coords.visibility = View.GONE  // Hide coordinates completely
        }
        // Icon mapping without reflection
        val resId = when (p.icon) {
            "ic_pin" -> R.drawable.ic_pin
            "ic_home", "ic_menu_slideshow" -> R.drawable.ic_home
            "ic_star", "ic_menu_gallery" -> R.drawable.ic_star
            "ic_circle" -> R.drawable.ic_circle
            "ic_square" -> R.drawable.ic_square
            "ic_triangle" -> R.drawable.ic_triangle
            "ic_diamond" -> R.drawable.ic_diamond
            else -> R.drawable.ic_pin
        }
        holder.icon.setImageResource(resId)
        holder.icon.setColorFilter(p.color)  // Apply the coordinate's color to the icon

        // Set up click listeners for the action buttons
        holder.itemView.setOnClickListener {
            Log.d("SimpleCoordinatesAdapter", "Item view clicked for coordinate: ${p.id}")
            onClick(p)
        }

        holder.itemView.findViewById<View>(R.id.coordinate_row_body)?.setOnClickListener {
            Log.d("SimpleCoordinatesAdapter", "Row body clicked for coordinate: ${p.id}")
            onClick(p)
        }
        // Delete button
        val deleteBtn = holder.itemView.findViewById<View>(R.id.button_delete)
        if (deleteBtn != null) {
            deleteBtn.setOnClickListener {
                Log.d("SimpleCoordinatesAdapter", "Delete clicked for coordinate: ${p.id}")
                onDelete(p)
            }
        } else {
            Log.w("SimpleCoordinatesAdapter", "Delete button not found in layout (pos=$position id=${p.id})")
        }

        val accent = holder.itemView.findViewById<View>(R.id.selection_accent)
        val selected = p.id == selectedId
        if (accent != null) {
            accent.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        } else {
            Log.w("SimpleCoordinatesAdapter", "Accent view still missing after injection attempt")
        }
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
        Log.d("SimpleCoordinatesAdapter", "Selection change: old=$selectedId new=$newId")
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

    fun positionOf(id: String): Int = items.indexOfFirst { it.id == id }
    fun idAt(position: Int): String? = if (position in items.indices) items[position].id else null
}
