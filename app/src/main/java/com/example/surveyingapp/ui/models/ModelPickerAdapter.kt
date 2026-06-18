package com.example.surveyingapp.ui.models

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import java.util.Date
import java.util.Locale

class ModelPickerAdapter(private val onModelClick: (Model) -> Unit) : ListAdapter<Model, ModelPickerAdapter.ModelViewHolder>(ModelDiffCallback()) {

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    fun cleanup() { coroutineScope.cancel() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model_picker, parent, false)
        return ModelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ModelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardModel: View = itemView.findViewById(R.id.card_model)
        private val imageModelPreview: ImageView = itemView.findViewById(R.id.image_model_preview)
        private val imageModelPlaceholder: ImageView = itemView.findViewById(R.id.image_model_placeholder)
        private val textModelName: TextView = itemView.findViewById(R.id.text_model_name)
        private val textModelFilename: TextView = itemView.findViewById(R.id.text_model_filename)
        private val textModelSize: TextView = itemView.findViewById(R.id.text_model_size)
        private val textModelDate: TextView = itemView.findViewById(R.id.text_model_date)

        // Tracks the currently running preview so it can be cancelled on rebind
        private var previewJob: Job? = null

        fun bind(model: Model) {
            textModelName.text = model.name
            textModelFilename.text = model.fileName
            textModelSize.text = model.getFormattedSize()
            textModelDate.text = formatDate(model.dateAdded)

            itemView.setOnClickListener { onModelClick(model) }
            cardModel.setOnClickListener { onModelClick(model) }

            loadThumbnail(model)
        }

        private fun loadThumbnail(model: Model) {
            previewJob?.cancel()
            imageModelPreview.visibility = View.GONE
            imageModelPlaceholder.visibility = View.VISIBLE
            imageModelPreview.tag = model.id

            previewJob = coroutineScope.launch {
                val bmp = withContext(Dispatchers.IO) { decodeThumbnail(model.thumbnailFilePath) }
                if (imageModelPreview.tag != model.id) return@launch
                if (bmp != null) {
                    imageModelPreview.setImageBitmap(bmp)
                    imageModelPreview.visibility = View.VISIBLE
                    imageModelPlaceholder.visibility = View.GONE
                } else {
                    imageModelPlaceholder.visibility = View.VISIBLE
                }
            }
        }

        private fun decodeThumbnail(path: String?): Bitmap? {
            if (path.isNullOrBlank()) return null
            val cacheKey = "thumb:$path"
            SimpleCoordinatesAdapter.peekCache(cacheKey)?.let { return it }
            return try {
                val file = File(path)
                if (!file.exists()) return null
                val bmp = BitmapFactory.decodeFile(path) ?: return null
                SimpleCoordinatesAdapter.putCache(cacheKey, bmp)
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