package com.example.surveyingapp.ui.models

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R
import com.example.surveyingapp.domain.model.Model
import com.example.surveyingapp.ui.viewpoints.SimpleCoordinatesAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ModelsAdapter(
    private val onDeleteClick: (Model) -> Unit,
    private val onEditClick: (Model) -> Unit,
    private val onModelClick: (Model) -> Unit
) : ListAdapter<Model, ModelsAdapter.ModelViewHolder>(ModelDiffCallback()) {

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    fun cleanup() { coroutineScope.cancel() }

    /** Evict cached thumbnails for all current items, then notify so they reload from disk. */
    fun evictAndRefreshThumbnails() {
        currentList.forEach { model ->
            model.thumbnailFilePath?.let { SimpleCoordinatesAdapter.evictThumbnail(it) }
        }
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false)
        return ModelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ModelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageModelPreview: ImageView = itemView.findViewById(R.id.image_model_preview)
        private val imageModelPlaceholder: ImageView = itemView.findViewById(R.id.image_model_placeholder)
        private val progressPreview: ProgressBar = itemView.findViewById(R.id.progress_preview)
        private val textModelName: TextView = itemView.findViewById(R.id.text_model_name)
        private val textModelFilename: TextView = itemView.findViewById(R.id.text_model_filename)
        private val textModelSize: TextView = itemView.findViewById(R.id.text_model_size)
        private val textModelDate: TextView = itemView.findViewById(R.id.text_model_date)
        private val btnEditModel: ImageButton = itemView.findViewById(R.id.btn_edit_model)
        //private val btnRecaptureThumbnail: ImageButton = itemView.findViewById(R.id.btn_recapture_thumbnail)
        private val btnDeleteModel: ImageButton = itemView.findViewById(R.id.btn_delete_model)

        /** Tracks the currently running preview load so it can be cancelled on rebind. */
        private var previewJob: Job? = null

        fun bind(model: Model) {
            textModelName.text = model.name
            textModelFilename.text = model.fileName
            textModelSize.text = model.getFormattedSize()
            textModelDate.text = formatDate(model.dateAdded)

            // Apply solid icon colours via SRC_IN so the tint fully replaces
            // the drawable's own colour rather than blending on top of it.
            btnEditModel.imageTintList = ColorStateList.valueOf(Color.parseColor("#0D47A1"))
            btnEditModel.imageTintMode = PorterDuff.Mode.SRC_IN
            //btnRecaptureThumbnail.imageTintList = ColorStateList.valueOf(Color.parseColor("#E65100"))
            //btnRecaptureThumbnail.imageTintMode = PorterDuff.Mode.SRC_IN
            btnDeleteModel.imageTintList = ColorStateList.valueOf(Color.parseColor("#B71C1C"))
            btnDeleteModel.imageTintMode = PorterDuff.Mode.SRC_IN

            btnEditModel.setOnClickListener { onEditClick(model) }

            btnDeleteModel.setOnClickListener {
                onDeleteClick(model)
            }

            // Handle model click to view in 3D
            itemView.setOnClickListener {
                onModelClick(model)
            }

            // Load GLB preview
            loadGlbPreview(model)
        }

        private fun loadGlbPreview(model: Model) {
            // Cancel any in-flight load from a previous bind
            previewJob?.cancel()

            // Reset preview state
            imageModelPreview.visibility = View.GONE
            imageModelPlaceholder.visibility = View.VISIBLE
            progressPreview.visibility = View.GONE

            // Tag the view so we can detect a stale bind after the IO hop
            imageModelPreview.tag = model.id

            previewJob = coroutineScope.launch {
                try {
                    val preview = withContext(Dispatchers.IO) { resolvePreview(model) }
                    // Skip if the ViewHolder was rebound to a different model while loading
                    if (imageModelPreview.tag != model.id) return@launch
                    if (preview != null) {
                        imageModelPreview.setImageBitmap(preview)
                        imageModelPreview.visibility = View.VISIBLE
                        imageModelPlaceholder.visibility = View.GONE
                    } else {
                        imageModelPlaceholder.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    imageModelPlaceholder.visibility = View.VISIBLE
                } finally {
                    progressPreview.visibility = View.GONE
                }
            }
        }

        /**
         * Returns the saved thumbnail bitmap for [model], or null if none exists.
         * When null the XML placeholder (broken-image icon) is shown instead.
         */
        private fun resolvePreview(model: Model): Bitmap? {
            val thumbPath = model.thumbnailFilePath
            if (thumbPath.isNullOrBlank()) return null

            val cacheKey = "thumb:$thumbPath"
            SimpleCoordinatesAdapter.peekCache(cacheKey)?.let { return it }

            val file = File(thumbPath)
            if (!file.exists()) return null

            return try {
                val bmp = BitmapFactory.decodeFile(thumbPath)
                if (bmp != null) SimpleCoordinatesAdapter.putCache(cacheKey, bmp)
                bmp
            } catch (e: Exception) {
                null
            }
        }

        private fun formatDate(timestamp: Long): String {
            val date = Date(timestamp)
            val now = Date()
            val diff = now.time - timestamp

            return when {
                diff < 24L * 60 * 60 * 1000 -> "Added today"
                diff < 7L * 24 * 60 * 60 * 1000 -> {
                    val days = (diff / (24L * 60 * 60 * 1000)).toInt()
                    "Added ${days}d ago"
                }
                else -> {
                    val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                    "Added ${formatter.format(date)}"
                }
            }
        }
    }

    class ModelDiffCallback : DiffUtil.ItemCallback<Model>() {
        override fun areItemsTheSame(oldItem: Model, newItem: Model): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Model, newItem: Model): Boolean {
            return oldItem == newItem
        }
    }
}
