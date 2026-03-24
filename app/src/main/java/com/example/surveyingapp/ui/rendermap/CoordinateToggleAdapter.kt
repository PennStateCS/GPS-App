package com.example.surveyingapp.ui.rendermap

import android.graphics.BitmapFactory
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
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.ModelRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CoordinateToggleItem(val id: String, val name: String, val checked: Boolean, val icon: String, val color: Int)

class CoordinateToggleAdapter(
    private val onToggle: (id: String, checked: Boolean) -> Unit
) : RecyclerView.Adapter<CoordinateToggleAdapter.Holder>() {

    private var items: List<CoordinateToggleItem> = emptyList()
    private val scope = CoroutineScope(Dispatchers.Main)

    fun submit(newItems: List<CoordinateToggleItem>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == newItems[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newItems[n]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_coordinate_toggle, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item, scope, onToggle)
    }

    // Cancel any in-flight icon load when a view is recycled.
    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        holder.cancelIconLoad()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val switch: Switch = v.findViewById(R.id.switch_visible)
        val icon: ImageView = v.findViewById(R.id.image_icon)
        val name: TextView = v.findViewById(R.id.text_name)

        private var iconJob: Job? = null

        fun cancelIconLoad() {
            iconJob?.cancel()
            iconJob = null
        }

        fun bind(item: CoordinateToggleItem, scope: CoroutineScope, onToggle: (String, Boolean) -> Unit) {
            name.text = item.name

            // Reset icon to default while loading
            icon.clearColorFilter()
            icon.setImageResource(R.drawable.ic_section_location)
            icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)

            cancelIconLoad()

            when {
                // ── Model thumbnail icon ───────────────────────────────────
                item.icon.startsWith("model:") -> {
                    val modelId = item.icon.removePrefix("model:")
                    iconJob = scope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            try {
                                val db = AppDatabase.getDatabase(itemView.context)
                                val repo = ModelRepositoryImpl(db.modelDao())
                                val model = repo.getModelById(modelId) ?: return@withContext null
                                val path = model.thumbnailFilePath ?: return@withContext null
                                if (!File(path).exists()) return@withContext null
                                BitmapFactory.decodeFile(path)
                            } catch (_: Exception) { null }
                        }
                        if (bmp != null) {
                            icon.clearColorFilter()
                            // White background so transparent PNG is visible
                            icon.setBackgroundColor(android.graphics.Color.WHITE)
                            icon.setImageBitmap(bmp)
                        }
                        // else: leave the default icon in place
                    }
                }

                // ── Built-in drawable icon ─────────────────────────────────
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

                // ── No icon key ────────────────────────────────────────────
                else -> {
                    icon.setImageResource(R.drawable.ic_section_location)
                    icon.setColorFilter(item.color, PorterDuff.Mode.SRC_IN)
                }
            }

            switch.setOnCheckedChangeListener(null)
            switch.isChecked = item.checked
            val clickListener = View.OnClickListener {
                val newChecked = !switch.isChecked
                switch.isChecked = newChecked
                onToggle(item.id, newChecked)
            }
            itemView.setOnClickListener(clickListener)
            name.setOnClickListener(clickListener)
            switch.setOnCheckedChangeListener { _, isChecked -> onToggle(item.id, isChecked) }
        }
    }
}
