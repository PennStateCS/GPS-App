package com.example.surveyingapp.ui.rendermap

import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R
import com.example.surveyingapp.domain.repository.ModelRepository
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class CoordinateToggleItem(
    val id: String,
    val name: String,
    val checked: Boolean,
    /** Built-in icon key (drawable name) for non-model coordinates; "" when a model is linked. */
    val icon: String,
    /** Linked model id (new column or legacy icon), or null when no model is linked. */
    val modelId: String? = null,
    val color: Int,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    /** Pre-formatted compact metadata line (e.g. "Elev 432.7 m · Float · RS2+"); falls back to lat/lon. */
    val meta: String = ""
)

class CoordinateToggleAdapter(
    private val modelRepository: ModelRepository,
    private val onToggle: (id: String, checked: Boolean) -> Unit,
    private val onRowClick: (id: String) -> Unit = {}
) : RecyclerView.Adapter<CoordinateToggleAdapter.Holder>() {

    private var items: List<CoordinateToggleItem> = emptyList()
    private var selectedId: String? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun submit(newItems: List<CoordinateToggleItem>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == newItems[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newItems[n] &&
                old[o].id != selectedId  // force rebind if selected id changed
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    fun setSelectedId(id: String?) {
        val old = selectedId
        if (old == id) return
        selectedId = id
        old?.let { oldId -> items.indexOfFirst { it.id == oldId }.takeIf { it >= 0 }?.let { notifyItemChanged(it) } }
        id?.let { newId -> items.indexOfFirst { it.id == newId }.takeIf { it >= 0 }?.let { notifyItemChanged(it) } }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_coordinate_toggle, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], selectedId, scope, modelRepository, onToggle, onRowClick)
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        holder.cancelIconLoad()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val switch: MaterialSwitch = v.findViewById(R.id.switch_visible)
        val icon: ImageView = v.findViewById(R.id.image_icon)
        val name: TextView = v.findViewById(R.id.text_name)
        val subtitle: TextView = v.findViewById(R.id.text_subtitle)
        val rowBody: View = v.findViewById(R.id.coord_row_body)
        val accent: View = v.findViewById(R.id.row_accent)

        private var iconJob: Job? = null

        fun cancelIconLoad() {
            iconJob?.cancel()
            iconJob = null
        }

        fun bind(
            item: CoordinateToggleItem,
            selectedId: String?,
            scope: CoroutineScope,
            modelRepository: ModelRepository,
            onToggle: (String, Boolean) -> Unit,
            onRowClick: (String) -> Unit
        ) {
            name.text = item.name

            // Subtitle: compact surveyor metadata, falling back to lat/lon.
            val subtitleText = when {
                item.meta.isNotBlank() -> item.meta
                item.lat != 0.0 || item.lon != 0.0 -> String.format(Locale.US, "%.5f, %.5f", item.lat, item.lon)
                else -> ""
            }
            if (subtitleText.isNotBlank()) {
                subtitle.text = subtitleText
                subtitle.visibility = View.VISIBLE
            } else {
                subtitle.visibility = View.GONE
            }

            // Selection: subtle highlight on the row body + left accent bar + stronger title.
            val isSelected = item.id == selectedId
            val selectedBg = MaterialColors.getColor(
                itemView,
                com.google.android.material.R.attr.colorPrimaryContainer,
                android.graphics.Color.TRANSPARENT
            )
            rowBody.setBackgroundColor(if (isSelected) selectedBg else android.graphics.Color.TRANSPARENT)
            accent.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            name.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

            // Icon
            icon.clearColorFilter()
            icon.setImageResource(R.drawable.ic_section_location)
            icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            cancelIconLoad()

            when {
                item.modelId != null -> {
                    val modelId = item.modelId
                    iconJob = scope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            try {
                                val model = modelRepository.getModelById(modelId) ?: return@withContext null
                                val path = model.thumbnailFilePath ?: return@withContext null
                                if (!File(path).exists()) return@withContext null
                                BitmapFactory.decodeFile(path)
                            } catch (_: Exception) { null }
                        }
                        if (bmp != null) {
                            icon.clearColorFilter()
                            icon.setBackgroundColor(android.graphics.Color.WHITE)
                            icon.setImageBitmap(bmp)
                        }
                    }
                }

                item.icon.isNotBlank() -> {
                    @Suppress("DiscouragedApi")
                    val resId = itemView.context.resources.getIdentifier(
                        item.icon, "drawable", itemView.context.packageName
                    )
                    if (resId != 0) {
                        icon.setImageResource(resId)
                        icon.setColorFilter(item.color, PorterDuff.Mode.SRC_IN)
                    } else {
                        icon.setImageResource(R.drawable.ic_section_location)
                        icon.setColorFilter(item.color, PorterDuff.Mode.SRC_IN)
                    }
                }

                else -> {
                    icon.setImageResource(R.drawable.ic_section_location)
                    icon.setColorFilter(item.color, PorterDuff.Mode.SRC_IN)
                }
            }

            // Row body click → select coordinate (center map)
            rowBody.setOnClickListener { onRowClick(item.id) }

            // Switch → toggle visibility independently
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = item.checked
            switch.setOnCheckedChangeListener { _, isChecked -> onToggle(item.id, isChecked) }
        }
    }
}
