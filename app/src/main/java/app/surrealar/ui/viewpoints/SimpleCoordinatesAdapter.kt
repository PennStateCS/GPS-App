package app.surrealar.ui.viewpoints

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.surrealar.R
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.displayIconKey
import app.surrealar.domain.model.linkedModelId
import com.google.android.material.color.MaterialColors
import java.io.File
import java.util.Locale

/**
 * RecyclerView Adapter for displaying coordinate points in a list.
 *
 * RecyclerView is Android's efficient way to display large lists by recycling views.
 * The Adapter pattern connects your data to the UI views.
 */
class SimpleCoordinatesAdapter(
    private val onClick: (Coordinate) -> Unit, // item click callback
    private val onDelete: (Coordinate) -> Unit, // delete callback
    private val onEdit: (Coordinate) -> Unit = {} // edit callback
) : RecyclerView.Adapter<SimpleCoordinatesAdapter.Holder>() {

    companion object {
        /** Target icon size in the list (px). Bitmaps are scaled to this before caching. */
        private const val ICON_SIZE_PX = 96

        /**
         * Process-wide LRU bitmap cache shared across all list instances.
         * Key: either "asset:<name>" or "thumb:<absolutePath>".
         * Size: 4 MB — enough for ~170 × 96×96 ARGB_8888 bitmaps.
         */
        private val bitmapCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        /** Evict all cached thumbnails for a model (call when a thumbnail is updated/deleted). */
        fun evictThumbnail(thumbPath: String) = bitmapCache.remove("thumb:$thumbPath")

        /** Clear the whole cache (e.g. on low-memory). */
        fun clearCache() = bitmapCache.evictAll()

        /** Read a cached bitmap without side effects — returns null on miss. */
        fun peekCache(key: String): Bitmap? = bitmapCache.get(key)

        /** Insert a bitmap into the shared cache. */
        fun putCache(key: String, bmp: Bitmap) { bitmapCache.put(key, bmp) }
    }

    init { setHasStableIds(true) }

    // The list of coordinate points to display
    private var items: List<Coordinate> = emptyList()
    private var selectedId: String? = null

    /** Maps model DB id → thumbnail file path (null if no thumbnail). */
    private var thumbnailMap: Map<String, String?> = emptyMap()
    /** Maps model DB id → model display name. */
    private var modelNameMap: Map<String, String> = emptyMap()

    fun setThumbnailMap(map: Map<String, String?>) {
        thumbnailMap = map
        notifyItemRangeChanged(0, items.size)
    }

    fun setModelNameMap(nameMap: Map<String, String>) {
        modelNameMap = nameMap
    }

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

        // Subtitle: context-aware (model name, source, RTK quality, lat/lon)
        holder.coords.visibility = View.VISIBLE
        holder.coords.text = buildSubtitle(p)

        // Trailing indicator when a 3D model is linked to this coordinate
        val linkedModelId = p.linkedModelId
        val iconKey = p.displayIconKey ?: ""
        holder.modelLink.visibility = if (linkedModelId != null) View.VISIBLE else View.GONE

        // Icon: handle both built-in drawables and model thumbnails
        when {
            linkedModelId != null -> {
                // DB model icon — load thumbnail from disk if available
                val thumbPath = thumbnailMap[linkedModelId]
                val bmp = loadThumbnailCached(thumbPath) ?: makePlaceholderBitmap(p.name)
                holder.icon.clearColorFilter()
                holder.icon.setImageBitmap(bmp)
            }
            else -> {
                // Try loading as a built-in asset image first (e.g. "transformer", "hydrant")
                val assetBmp = tryLoadAssetCached(holder.itemView, iconKey)
                if (assetBmp != null) {
                    holder.icon.clearColorFilter()
                    holder.icon.setImageBitmap(assetBmp)
                    holder.icon.setColorFilter(p.color)
                } else {
                    // Fall back to drawable resource
                    val resId = when (iconKey) {
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
                    holder.icon.setColorFilter(p.color)
                }
            }
        }

        // Row body tap = select
        holder.itemView.setOnClickListener { onClick(p) }
        holder.itemView.findViewById<View>(R.id.coordinate_row_body)?.setOnClickListener { onClick(p) }

        // Overflow three-dot menu → Edit / Delete
        holder.itemView.findViewById<ImageButton>(R.id.btn_overflow)?.setOnClickListener { anchor ->
            PopupMenu(anchor.context, anchor).apply {
                menuInflater.inflate(R.menu.menu_coordinate_overflow, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_edit_coordinate   -> { onEdit(p); true }
                        R.id.action_delete_coordinate -> { onDelete(p); true }
                        else -> false
                    }
                }
                show()
            }
        }

        val accent = holder.itemView.findViewById<View>(R.id.selection_accent)
        val rowRoot = holder.itemView.findViewById<View>(R.id.coordinate_row_root)
        val rowBody = holder.itemView.findViewById<View>(R.id.coordinate_row_body)
        val selected = p.id == selectedId

        // Accent bar
        accent?.visibility = if (selected) View.VISIBLE else View.INVISIBLE

        // Full row: colorPrimaryContainer bg when selected so the highlight extends
        // across the model-link icon and overflow button too.
        val ctx = rowBody?.context ?: holder.itemView.context
        if (selected) {
            val bg = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorPrimaryContainer, Color.TRANSPARENT)
            rowRoot?.setBackgroundColor(bg)
            // Remove ripple from body so it doesn't overdraw the selection colour.
            rowBody?.setBackgroundColor(Color.TRANSPARENT)
            val fg = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOnPrimaryContainer, Color.BLACK)
            holder.name.setTextColor(fg)
            holder.coords.setTextColor(fg and 0x00FFFFFF or (0xB2 shl 24))
        } else {
            rowRoot?.setBackgroundColor(Color.TRANSPARENT)
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            if (rowBody != null) {
                if (tv.resourceId != 0) rowBody.setBackgroundResource(tv.resourceId)
                else rowBody.setBackgroundColor(Color.TRANSPARENT)
            }
            val onSurface = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            val onSurfaceVar = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
            holder.name.setTextColor(onSurface)
            holder.coords.setTextColor(onSurfaceVar)
        }

        if (accent == null) {
            Log.w("SimpleCoordinatesAdapter", "Accent view still missing after injection attempt")
        }
    }

    // ── Subtitle builder ──────────────────────────────────────────────────────

    private fun buildSubtitle(p: Coordinate): String {
        val latLon = String.format(Locale.US, "%.6f, %.6f", p.latitude, p.longitude)
        val linkedModelId = p.linkedModelId
        return when {
            linkedModelId != null -> {
                val name = modelNameMap[linkedModelId]?.takeIf { it.isNotBlank() } ?: "Model linked"
                "$name · $latLon"
            }
            p.provider.lowercase(Locale.US).contains("rs2") ||
            p.captureMethod?.lowercase(Locale.US) == "external_gnss" ||
            p.captureMethod?.lowercase(Locale.US) == "rtk_receiver" -> {
                val src = p.sourceDevice?.takeIf { it.isNotBlank() } ?: "RS2+"
                val quality = when (p.rtkStatus?.uppercase(Locale.US)) {
                    "FIX", "FIXED" -> "Fixed"
                    "FLOAT"        -> "Float"
                    "DGPS"         -> "DGPS"
                    "SINGLE", "GPS", "AUTONOMOUS" -> "Single"
                    else           -> null
                }
                if (quality != null) "$src · $quality · $latLon" else "$src · $latLon"
            }
            else -> {
                val src = when (p.captureMethod?.lowercase(Locale.US)) {
                    "internal_gps" -> "Internal GPS"
                    "averaged"     -> "Averaged"
                    "manual"       -> "Manual"
                    "imported"     -> "Imported"
                    "map_tap"      -> "Map tap"
                    "model_embedded" -> "Model"
                    else           -> null
                }
                if (src != null) "$src · $latLon" else latLon
            }
        }
    }

    // ── Cached helpers ────────────────────────────────────────────────────────

    /**
     * Loads a model thumbnail from disk, scaling it to [ICON_SIZE_PX] and caching the
     * result.  Returns null when the path is blank or the file does not exist.
     */
    private fun loadThumbnailCached(thumbPath: String?): Bitmap? {
        if (thumbPath.isNullOrBlank()) return null
        val cacheKey = "thumb:$thumbPath"
        bitmapCache.get(cacheKey)?.let { return it }

        val file = File(thumbPath)
        if (!file.exists()) return null

        return try {
            val raw = BitmapFactory.decodeFile(thumbPath) ?: return null
            val scaled = raw.toIconSize()
            if (scaled !== raw) raw.recycle()
            bitmapCache.put(cacheKey, scaled)
            scaled
        } catch (e: Exception) {
            Log.w("SimpleCoordinatesAdapter", "Failed to decode thumbnail: $thumbPath", e)
            null
        }
    }

    /**
     * Loads a PNG from `assets/model_images/<name>.png`, scaling it to [ICON_SIZE_PX] and
     * caching the result.  Returns null when the asset does not exist.
     */
    private fun tryLoadAssetCached(view: View, name: String): Bitmap? {
        if (name.isBlank()) return null
        val cacheKey = "asset:$name"
        bitmapCache.get(cacheKey)?.let { return it }

        return try {
            val raw = view.context.assets.open("model_images/$name.png")
                .use { BitmapFactory.decodeStream(it) } ?: return null
            val scaled = raw.toIconSize()
            if (scaled !== raw) raw.recycle()
            bitmapCache.put(cacheKey, scaled)
            scaled
        } catch (_: Exception) { null }
    }

    /** Scale this bitmap to [ICON_SIZE_PX]×[ICON_SIZE_PX] only if it is larger than that. */
    private fun Bitmap.toIconSize(): Bitmap =
        if (width <= ICON_SIZE_PX && height <= ICON_SIZE_PX) this
        else scale(ICON_SIZE_PX, ICON_SIZE_PX, filter = true)

    /**
     * Generates a grey square with the first letter of [label].
     * The result is *not* inserted into the LRU cache because placeholder bitmaps are
     * cheap and coordinate names are not shared across rows.
     */
    private fun makePlaceholderBitmap(label: String): Bitmap {
        val size = ICON_SIZE_PX
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#BDBDBD")
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.color = Color.WHITE
        paint.textSize = size * 0.5f
        paint.textAlign = Paint.Align.CENTER
        val initial = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val yPos = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(initial, size / 2f, yPos, paint)
        return bmp
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    /**
     * ViewHolder class that holds references to the views in each list item.
     */
    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.image_icon)        // The coordinate point icon
        val name: TextView = v.findViewById(R.id.text_name)          // The coordinate point name
        val coords: TextView = v.findViewById(R.id.text_coords)      // The coordinate values (lat/lon)
        val modelLink: ImageView = v.findViewById(R.id.image_model_link) // Linked-model indicator
    }

    // ── Selection ─────────────────────────────────────────────────────────────

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
