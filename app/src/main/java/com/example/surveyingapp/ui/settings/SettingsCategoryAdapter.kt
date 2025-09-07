package com.example.surveyingapp.ui.settings

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R

// Represents a sidebar/settings section. Consider moving to its own file if reused elsewhere.
data class SettingsCategory(
    val id: Int,          // Stable logical id (could be leveraged with setHasStableIds())
    val title: String,
    val iconRes: Int
)

class SettingsCategoryAdapter(
    categories: List<SettingsCategory>,
    private val onCategorySelected: (SettingsCategory) -> Unit
) : RecyclerView.Adapter<SettingsCategoryAdapter.CategoryViewHolder>() {

    /** Internal copy so callers don’t mutate our list behind the adapter’s back. */
    private val items = categories.toMutableList()

    // Track selection by stable category id (avoids position drift if ordering changes)
    private var selectedCategoryId: Long? = items.firstOrNull()?.id?.toLong()

    init { setHasStableIds(true) }

    class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.category_icon)
        val title: TextView = itemView.findViewById(R.id.category_title)
        val accent: View? = itemView.findViewById(R.id.category_accent)
        val body: View? = itemView.findViewById(R.id.category_body)
    }

    override fun getItemId(position: Int): Long = items[position].id.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_settings_category, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        val ctx = holder.itemView.context
        val category = items[position]
        val isSelected = category.id.toLong() == selectedCategoryId

        // Content
        holder.title.text = category.title
        holder.icon.setImageResource(category.iconRes)

        // Visual state
        holder.itemView.isSelected = isSelected
        holder.itemView.isActivated = isSelected

        holder.accent?.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

        holder.body?.let { body ->
            // Ensure the background is set (shape should be tintable)
            body.setBackgroundResource(R.drawable.dev_category_bg)
            val tintColor = if (isSelected)
                ContextCompat.getColor(ctx, R.color.dev_category_selected_bg)
            else
                ContextCompat.getColor(ctx, android.R.color.transparent)
            body.backgroundTintList = ColorStateList.valueOf(tintColor)
        }

        holder.icon.alpha = if (isSelected) 1.0f else 0.7f
        holder.title.alpha = if (isSelected) 1.0f else 0.85f

        holder.itemView.contentDescription =
            if (isSelected) ctx.getString(R.string.dev_category_selected_desc) + ": " + category.title
            else category.title

        // Single click listener (prefer body if present else root)
        (holder.body ?: holder.itemView).setOnClickListener {
            handleClick(category)
        }
    }

    private fun handleClick(category: SettingsCategory) {
        val newId = category.id.toLong()
        if (newId == selectedCategoryId) return

        val oldId = selectedCategoryId
        selectedCategoryId = newId

        // Minimize rebinds: only refresh the two affected rows
        oldId?.let { old ->
            val oldIdx = items.indexOfFirst { it.id.toLong() == old }
            if (oldIdx >= 0) notifyItemChanged(oldIdx)
        }
        val newIdx = items.indexOfFirst { it.id.toLong() == newId }
        if (newIdx >= 0) notifyItemChanged(newIdx)

        onCategorySelected(category)
    }

    override fun getItemCount(): Int = items.size

    // ----- Public helpers -----

    /** Programmatically set selection by category id. */
    fun setSelectedCategoryId(id: Int) {
        val newId = id.toLong()
        if (newId == selectedCategoryId) return
        val old = selectedCategoryId
        selectedCategoryId = newId

        old?.let { oldId ->
            val oldIdx = items.indexOfFirst { it.id.toLong() == oldId }
            if (oldIdx >= 0) notifyItemChanged(oldIdx)
        }
        val newIdx = items.indexOfFirst { it.id.toLong() == newId }
        if (newIdx >= 0) notifyItemChanged(newIdx)
    }

    /** Read the currently selected id (or null if none). */
    fun getSelectedCategoryId(): Int? = selectedCategoryId?.toInt()

    /**
     * Update the category list efficiently. Keeps selection by id when possible.
     * Safe to call with the same or different content/order.
     */
    fun updateCategories(newCategories: List<SettingsCategory>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newCategories.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                items[oldItemPosition].id == newCategories[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                items[oldItemPosition] == newCategories[newItemPosition]
        })
        items.clear()
        items.addAll(newCategories)
        // If the selected id no longer exists, clear selection
        selectedCategoryId?.let { sel ->
            if (items.none { it.id.toLong() == sel }) selectedCategoryId = items.firstOrNull()?.id?.toLong()
        }
        diff.dispatchUpdatesTo(this)
    }
}
