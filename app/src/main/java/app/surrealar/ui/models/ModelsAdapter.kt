package app.surrealar.ui.models

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
import app.surrealar.R
import androidx.core.content.ContextCompat
import app.surrealar.domain.model.Model
import app.surrealar.ui.models.readiness.ReadinessStyle
import app.surrealar.ui.models.readiness.Verdict
import app.surrealar.ui.viewpoints.SimpleCoordinatesAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView adapter for the imported-models list (thumbnail, name, metadata).
 */
class ModelsAdapter(
    private val onDeleteClick: (Model) -> Unit,
    private val onEditClick: (Model) -> Unit,
    private val onModelClick: (Model) -> Unit,
    private val onReadinessClick: (Model) -> Unit = onModelClick
) : ListAdapter<Model, ModelsAdapter.ModelViewHolder>(ModelDiffCallback()) {

    // Children cancelled when the list detaches (they used to outlive the screen).
    private val coroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    override fun onDetachedFromRecyclerView(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        coroutineScope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
    }

    fun cleanup() { coroutineScope.cancel() }

    /** Model id → coordinates linking it (for the "N linked" badge). */
    private var linkedCounts: Map<String, Int> = emptyMap()

    fun setLinkedCounts(counts: Map<String, Int>) {
        if (counts == linkedCounts) return
        val old = linkedCounts
        linkedCounts = counts
        currentList.forEachIndexed { i, m ->
            if (old[m.id] != counts[m.id]) notifyItemChanged(i, PAYLOAD_BADGES)
        }
    }

    /** Model id → AR readiness verdict (for the readiness chip). */
    private var readiness: Map<String, Verdict> = emptyMap()

    fun setReadiness(verdicts: Map<String, Verdict>) {
        if (verdicts == readiness) return
        val old = readiness
        readiness = verdicts
        currentList.forEachIndexed { i, m ->
            if (old[m.id] != verdicts[m.id]) notifyItemChanged(i, PAYLOAD_BADGES)
        }
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_BADGES }) {
            holder.bindLinkedBadge(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

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
        private val layoutBadges: View = itemView.findViewById(R.id.layout_badges)
        private val badgeLinked: TextView = itemView.findViewById(R.id.text_badge_linked)
        private val badgeReadiness: TextView = itemView.findViewById(R.id.text_badge_readiness)
        private val badgeGeo: TextView = itemView.findViewById(R.id.text_badge_geo)
        private val badgeProblem: TextView = itemView.findViewById(R.id.text_badge_problem)

        /** Tracks the currently running preview load so it can be cancelled on rebind. */
        private var previewJob: Job? = null

        fun bind(model: Model) {
            textModelName.text = model.name
            // A default name is just the file name again — show "GLB · 119.4 KB" instead.
            textModelFilename.text = if (ModelCardInfo.nameRepeatsFileName(model)) {
                ModelCardInfo.typeAndSizeLabel(model.fileName, model.fileSize)
            } else {
                model.fileName
            }
            textModelSize.text = model.getFormattedSize()
            textModelDate.text = formatDate(model.dateAdded)

            badgeGeo.visibility = if (ModelCardInfo.isGeoreferenced(model)) View.VISIBLE else View.GONE
            bindLinkedBadge(model)
            // "Invalid" is known now; "File missing" needs a disk check, done with the preview load.
            showProblemBadge(if (!model.isValid) "Invalid" else null)

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

        fun bindLinkedBadge(model: Model) {
            val n = linkedCounts[model.id] ?: 0
            if (n > 0) {
                badgeLinked.text = "$n linked"
                badgeLinked.visibility = View.VISIBLE
            } else {
                badgeLinked.visibility = View.GONE
            }
            bindReadinessBadge(model)
            layoutBadges.visibility =
                if (badgeLinked.visibility == View.VISIBLE || badgeGeo.visibility == View.VISIBLE ||
                    badgeReadiness.visibility == View.VISIBLE) View.VISIBLE
                else View.GONE
        }

        private fun bindReadinessBadge(model: Model) {
            val verdict = readiness[model.id]
            if (verdict == null) {
                badgeReadiness.visibility = View.GONE
                badgeReadiness.setOnClickListener(null)
                return
            }
            val ctx = itemView.context
            badgeReadiness.text = ReadinessStyle.verdictChip(verdict)
            badgeReadiness.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(ctx, ReadinessStyle.verdictColor(verdict)))
            badgeReadiness.contentDescription = "AR readiness: ${ReadinessStyle.verdictTitle(verdict)}. Tap for the checklist."
            badgeReadiness.setOnClickListener { onReadinessClick(model) }
            badgeReadiness.visibility = View.VISIBLE
        }

        private fun showProblemBadge(text: String?) {
            badgeProblem.text = text
            badgeProblem.visibility = if (text != null) View.VISIBLE else View.GONE
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
                    val fileExists = withContext(Dispatchers.IO) {
                        runCatching { File(model.filePath).exists() }.getOrDefault(true)
                    }
                    if (imageModelPreview.tag != model.id) return@launch
                    if (!fileExists) showProblemBadge("File missing")

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
                val bmp = app.surrealar.util.ThumbnailBitmaps.decodeSampled(thumbPath)
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

    private companion object {
        const val PAYLOAD_BADGES = "badges"
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
