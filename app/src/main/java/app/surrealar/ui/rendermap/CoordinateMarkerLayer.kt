package app.surrealar.ui.rendermap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.core.content.ContextCompat
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.displayIconKey
import app.surrealar.domain.model.linkedModelId
import app.surrealar.domain.repository.ModelRepository
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The user's show/hide choice per coordinate id (marker visibility). One instance per fragment, so the
 * choices survive map view recreation (returning from Details/AR). Ids of deleted points are kept and
 * still counted, exactly as before the RenderMapFragment split.
 */
internal class CoordinateVisibility {
    private val visibilityMap = mutableMapOf<String, Boolean>()

    /** The stored choice for [id]; a never-seen id becomes visible (the default). */
    fun getOrInit(id: String): Boolean = visibilityMap.getOrPut(id) { true }

    /** The stored choice, or null for an id never seen. */
    operator fun get(id: String): Boolean? = visibilityMap[id]

    operator fun set(id: String, visible: Boolean) { visibilityMap[id] = visible }

    fun isVisible(id: String): Boolean = visibilityMap[id] == true

    /** Number of ids currently shown (including ids of deleted points that were last shown). */
    val visibleCount: Int get() = visibilityMap.count { it.value }
}

/**
 * Saved-coordinate glyph markers on the map: the single owner of the loaded coordinates (by id), their
 * markers, the per-id visibility choice and the marker icon cache (`icon:$name:$color` /
 * `model:$id:$path`). Icons for model-linked points are the model thumbnail framed as a card.
 *
 * Holds no GoogleMap/View reference: the map comes from [map] on each call. [onDestroyView] drops the
 * markers (they die with the map) and the loaded coordinates; [visibility] is kept.
 */
internal class CoordinateMarkerLayer(
    private val map: () -> GoogleMap?,
    private val context: () -> Context?,
    private val scope: () -> CoroutineScope,
    private val modelRepository: ModelRepository,
) {
    val visibility = CoordinateVisibility()
    private val markerMap = mutableMapOf<String, Marker>()
    private val coordinateMap = mutableMapOf<String, Coordinate>()

    // Marker descriptor cache — keyed by "icon:$name:$color" or "model:$id:$path"
    // Read and written from both Dispatchers.IO (model thumbnails) and Main (drawable icons): a plain
    // map is not safe for that. Same contents and lifetime (cleared in onDestroyView), no ordering used.
    private val markerDescriptorCache = java.util.concurrent.ConcurrentHashMap<String, BitmapDescriptor>()

    /** Loaded coordinates by id (display datum, WGS84), in load order. */
    val coordinates: Map<String, Coordinate> get() = coordinateMap

    fun coordinate(id: String): Coordinate? = coordinateMap[id]

    /** Shows/hides one point's marker (drawer checkbox). */
    fun setVisible(id: String, visible: Boolean) {
        visibility[id] = visible
        markerMap[id]?.isVisible = visible
    }

    /** Applies [rule] to every loaded coordinate (Show all / Hide all / Show selected only). */
    fun setAllVisible(rule: (String) -> Boolean) {
        coordinateMap.keys.forEach { id ->
            val visible = rule(id)
            visibility[id] = visible
            try { markerMap[id]?.isVisible = visible } catch (_: Exception) {}
        }
    }

    /** Positions of the markers that are currently shown (recenter / fit). */
    fun visibleMarkerPositions(): List<LatLng> = markerMap.entries
        .filter { (id, _) -> visibility[id] == true }
        .map { it.value.position }

    /** Removes every marker and forgets all loaded coordinates (the database is empty). */
    fun clear() {
        markerMap.values.forEach { it.remove() }
        markerMap.clear()
        coordinateMap.clear()
    }

    /** Removes markers for coordinates that were deleted; returns their ids. */
    fun removeMissing(newIds: Set<String>): List<String> {
        val removedIds = markerMap.keys.filter { it !in newIds }
        removedIds.forEach { id -> markerMap.remove(id)?.remove(); coordinateMap.remove(id) }
        return removedIds
    }

    /**
     * Adds or updates the marker for [p] and records it as loaded. Returns the point's visibility, or
     * null when the map is gone (the point is then skipped, as before).
     */
    fun upsert(p: Coordinate): Boolean? {
        val ll = LatLng(p.latitude, p.longitude)
        val visible = visibility.getOrInit(p.id)
        val prev = coordinateMap[p.id]
        val existingMarker = markerMap[p.id]

        if (existingMarker != null) {
            // Update position/title only when changed
            if (prev == null || prev.latitude != p.latitude || prev.longitude != p.longitude) {
                try { existingMarker.position = ll } catch (_: Exception) {}
            }
            if (prev == null || prev.name != p.name) {
                try { existingMarker.title = p.name } catch (_: Exception) {}
            }
            // Rebuild icon only when icon or color changed
            if (prev == null || prev.icon != p.icon || prev.color != p.color) {
                scope().launch {
                    val descriptor = buildMarkerDescriptor(p.linkedModelId, p.displayIconKey, p.color)
                    if (descriptor != null) existingMarker.setIcon(descriptor)
                }
            }
        } else {
            val marker = (map() ?: return null).addMarker(MarkerOptions().position(ll).title(p.name))
            if (marker != null) {
                marker.isVisible = visible
                marker.tag = p.id
                markerMap[p.id] = marker
                scope().launch {
                    val descriptor = buildMarkerDescriptor(p.linkedModelId, p.displayIconKey, p.color)
                    if (descriptor != null) marker.setIcon(descriptor)
                }
            }
        }

        coordinateMap[p.id] = p
        return visible
    }

    /**
     * The map and its markers are destroyed with the view: drop the stale marker references, the
     * loaded coordinates and the icon cache so a recreated view rebuilds everything on the fresh map.
     * [visibility] (the user's show/hide choices, keyed by id) is intentionally preserved.
     */
    fun onDestroyView() {
        markerDescriptorCache.clear()
        markerMap.clear()
        coordinateMap.clear()
    }

    // ── Marker icon building ───────────────────────────────────────────────────

    private suspend fun buildMarkerDescriptor(modelId: String?, iconKey: String?, colorInt: Int): BitmapDescriptor? {
        val ctx = context() ?: return null
        if (modelId != null) {
            return withContext(Dispatchers.IO) {
                try {
                    val model = modelRepository.getModelById(modelId) ?: return@withContext null
                    val thumbPath = model.thumbnailFilePath ?: return@withContext null
                    val cacheKey = "model:$modelId:$thumbPath"
                    markerDescriptorCache[cacheKey]?.let { return@withContext it }
                    val thumbBmp = BitmapFactory.decodeFile(thumbPath) ?: return@withContext null
                    val descriptor = withContext(Dispatchers.Main) { buildModelMarkerBitmap(thumbBmp) }
                    markerDescriptorCache[cacheKey] = descriptor
                    descriptor
                } catch (e: Exception) { logFail("marker model thumbnail load", e); null }
            }
        }
        if (iconKey.isNullOrBlank()) return null
        val cacheKey = "icon:$iconKey:$colorInt"
        markerDescriptorCache[cacheKey]?.let { return it }
        @Suppress("DiscouragedApi")
        val resId = ctx.resources.getIdentifier(iconKey, "drawable", ctx.packageName)
        if (resId == 0) return null
        val d = ContextCompat.getDrawable(ctx, resId) ?: return null
        val size = ctx.dpToPx(32f)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        try { @Suppress("DEPRECATION") d.mutate().setColorFilter(colorInt, PorterDuff.Mode.SRC_ATOP) } catch (_: Exception) {}
        d.draw(canvas)
        val descriptor = BitmapDescriptorFactory.fromBitmap(bmp)
        markerDescriptorCache[cacheKey] = descriptor
        return descriptor
    }

    private fun buildModelMarkerBitmap(thumb: Bitmap): BitmapDescriptor {
        val ctx = checkNotNull(context()) { "Fragment not attached to a context." }
        val density = ctx.resources.displayMetrics.density
        val markerPx = (56 * density).toInt()
        val borderPx = (2 * density)
        val radiusPx = (6 * density)
        val out = Bitmap.createBitmap(markerPx, markerPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        canvas.drawRoundRect(RectF(0f, 0f, markerPx.toFloat(), markerPx.toFloat()), radiusPx, radiusPx, bgPaint)
        canvas.drawBitmap(thumb, null, RectF(borderPx, borderPx, markerPx - borderPx, markerPx - borderPx),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0x55000000; strokeWidth = borderPx
        }
        canvas.drawRoundRect(RectF(0f, 0f, markerPx.toFloat(), markerPx.toFloat()), radiusPx, radiusPx, strokePaint)
        thumb.recycle()
        return BitmapDescriptorFactory.fromBitmap(out)
    }
}
