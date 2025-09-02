package com.example.surveyingapp.ui.models

import android.graphics.Bitmap
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ModelsAdapter(
    private val onDeleteClick: (Model) -> Unit,
    private val onEditClick: (Model) -> Unit
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

            // Load GLB preview
            loadGlbPreview(model)
        }

        private fun loadGlbPreview(model: Model) {
            // Reset preview state
            imageModelPreview.visibility = View.GONE
            imageModelPlaceholder.visibility = View.VISIBLE
            progressPreview.visibility = View.VISIBLE

            coroutineScope.launch {
                try {
                    val preview = generateGlbPreview(model.filePath)
                    if (preview != null) {
                        imageModelPreview.setImageBitmap(preview)
                        imageModelPreview.visibility = View.VISIBLE
                        imageModelPlaceholder.visibility = View.GONE
                    } else {
                        // Keep placeholder visible if preview generation fails
                        imageModelPlaceholder.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    // Keep placeholder visible on error
                    imageModelPlaceholder.visibility = View.VISIBLE
                } finally {
                    progressPreview.visibility = View.GONE
                }
            }
        }

        private suspend fun generateGlbPreview(filePath: String): Bitmap? = withContext(Dispatchers.IO) {
            try {
                // For now, generate a simple colored preview based on file properties
                // In a full implementation, you would use a 3D rendering library like SceneView
                val file = File(filePath)
                if (!file.exists()) return@withContext null

                // Generate a simple geometric preview as placeholder
                val size = 128
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                // Create a simple gradient based on file name hash for uniqueness
                val hash = file.name.hashCode()
                val color1 = Color.HSVToColor(floatArrayOf((hash % 360).toFloat(), 0.7f, 0.9f))
                val color2 = Color.HSVToColor(floatArrayOf(((hash + 180) % 360).toFloat(), 0.7f, 0.6f))

                // Draw a simple gradient circle as preview
                val paint = android.graphics.Paint().apply {
                    shader = android.graphics.RadialGradient(
                        size / 2f, size / 2f, size / 2f,
                        color1, color2,
                        android.graphics.Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(size / 2f, size / 2f, size / 2f * 0.8f, paint)

                // Add a small 3D cube icon in the center
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
            } catch (e: Exception) {
                null
            }
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
