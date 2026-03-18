package com.example.surveyingapp.ui.models

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
        private val btnDeleteModel: ImageButton = itemView.findViewById(R.id.btn_delete_model)

        /** Tracks the currently running preview load so it can be cancelled on rebind. */
        private var previewJob: Job? = null

        fun bind(model: Model) {
            textModelName.text = model.name
            textModelFilename.text = model.fileName
            textModelSize.text = model.getFormattedSize()
            textModelDate.text = formatDate(model.dateAdded)

            btnEditModel.setOnClickListener {
                onEditClick(model)
            }

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

            previewJob = coroutineScope.launch {
                try {
                    val preview = withContext(Dispatchers.IO) { resolvePreview(model) }
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
         * Returns a 128×128 preview bitmap for [model].
         * Priority:
         *  1. Saved thumbnail from [Model.thumbnailFilePath] (uses the shared LRU cache)
         *  2. Generated gradient circle as a fallback
         */
        private fun resolvePreview(model: Model): Bitmap? {
            // 1 — Real thumbnail from disk
            val thumbPath = model.thumbnailFilePath
            if (!thumbPath.isNullOrBlank()) {
                val cacheKey = "thumb:$thumbPath"

                // Always check the file exists first; if it does, load fresh and update cache
                val file = File(thumbPath)
                if (file.exists()) {
                    return try {
                        // Evict any stale entry so we always read the latest version
                        SimpleCoordinatesAdapter.evictThumbnail(thumbPath)
                        val bmp = BitmapFactory.decodeFile(thumbPath)
                        if (bmp != null) {
                            SimpleCoordinatesAdapter.putCache(cacheKey, bmp)
                        }
                        bmp
                    } catch (e: Exception) { null }
                }
            }

            // 2 — Generated gradient circle (no thumbnail yet)
            return generateFallbackPreview(model.filePath)
        }

        private fun generateFallbackPreview(filePath: String): Bitmap? {
            return try {
                val file = File(filePath)
                if (!file.exists()) return null

                val size = 128
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val hash = file.name.hashCode()
                val color1 = Color.HSVToColor(floatArrayOf((hash % 360).toFloat(), 0.7f, 0.9f))
                val color2 = Color.HSVToColor(floatArrayOf(((hash + 180) % 360).toFloat(), 0.7f, 0.6f))

                val paint = android.graphics.Paint().apply {
                    shader = android.graphics.RadialGradient(
                        size / 2f, size / 2f, size / 2f,
                        color1, color2,
                        android.graphics.Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(size / 2f, size / 2f, size / 2f * 0.8f, paint)

                val cubeSize = size / 4f
                val cubePaint = android.graphics.Paint().apply {
                    color = Color.WHITE
                    alpha = 200
                }
                canvas.drawRect(
                    size / 2f - cubeSize / 2f,
                    size / 2f - cubeSize / 2f,
                    size / 2f + cubeSize / 2f,
                    size / 2f + cubeSize / 2f,
                    cubePaint
                )
                bitmap
            } catch (e: Exception) { null }
        }

        private fun formatDate(timestamp: Long): String {
            val date = Date(timestamp)
            val now = Date()
            val diff = now.time - timestamp

            return when {
                diff < 24 * 60 * 60 * 1000 -> "Added today"
                diff < 7 * 24 * 60 * 60 * 1000 -> {
                    val days = (diff / (24 * 60 * 60 * 1000)).toInt()
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
