package com.example.surveyingapp.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R

data class SettingsCategory(
    val id: Int,
    val title: String,
    val iconRes: Int
)

class SettingsCategoryAdapter(
    private val categories: List<SettingsCategory>,
    private val onCategorySelected: (SettingsCategory) -> Unit
) : RecyclerView.Adapter<SettingsCategoryAdapter.CategoryViewHolder>() {

    private var selectedPosition = 0

    class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.category_icon)
        val title: TextView = itemView.findViewById(R.id.category_title)
        val accent: View? = itemView.findViewById(R.id.category_accent)
        val body: View? = itemView.findViewById(R.id.category_body)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_settings_category, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        val ctx = holder.itemView.context
        val category = categories[position]
        holder.title.text = category.title
        holder.icon.setImageResource(category.iconRes)

        val isSelected = position == selectedPosition
        holder.accent?.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        // Preserve ripple: use background tint (if body is Material ripple capable) else fallback alpha
        holder.body?.let { body ->
            val color = if (isSelected) ContextCompat.getColor(ctx, R.color.dev_category_selected_bg) else ContextCompat.getColor(ctx, android.R.color.transparent)
            body.setBackgroundResource(R.drawable.dev_category_bg)
            body.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color))
        }
        val iconAlpha = if (isSelected) 1.0f else 0.7f
        holder.icon.alpha = iconAlpha
        holder.title.alpha = if (isSelected) 1.0f else 0.85f

        holder.itemView.contentDescription = if (isSelected) {
            ctx.getString(R.string.dev_category_selected_desc, category.title)
        } else category.title

        holder.body?.setOnClickListener { handleClick(holder, category) }
        holder.itemView.setOnClickListener { handleClick(holder, category) }
    }

    private fun handleClick(holder: CategoryViewHolder, category: SettingsCategory) {
        val adapterPosition = holder.adapterPosition
        if (adapterPosition != RecyclerView.NO_POSITION && adapterPosition != selectedPosition) {
            val old = selectedPosition
            selectedPosition = adapterPosition
            notifyItemChanged(old)
            notifyItemChanged(selectedPosition)
            onCategorySelected(category)
        }
    }

    override fun getItemCount() = categories.size

    fun selectCategory(position: Int) {
        if (position in categories.indices && position != selectedPosition) {
            val oldPosition = selectedPosition
            selectedPosition = position
            notifyItemChanged(oldPosition)
            notifyItemChanged(selectedPosition)
        }
    }
}
