package app.surrealar.ui.rendermap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.util.diagnostics.MapRuntimeDiagnostics
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import io.github.pennstatecs.geokit.projection.Utm
import kotlin.math.cos
import kotlin.math.pow

/**
 * The map's drawn overlays besides the coordinate glyphs: the survey meter grid (Off → Auto → Fine →
 * Coarse, math in [MapGrid]), the point-label marker layer (Off → Name → Elevation → Distance, text in
 * [PointLabel]) with their Map Tools rows, and the (currently unused) boundary lines.
 *
 * Reads the coordinates and their visibility from [markers] and the live position from [liveLatLng];
 * holds no GoogleMap reference (taken from [map] per call). Grid/label modes are session state, stored
 * in [mapUiVm] when they change.
 */
internal class MapOverlayController(
    private val map: () -> GoogleMap?,
    private val context: () -> Context?,
    private val markers: CoordinateMarkerLayer,
    private val liveLatLng: () -> LatLng?,
    private val coordinateSystemService: CoordinateSystemService,
    private val coordSystemSettings: () -> CoordinateSystemSettings,
    private val mapUiVm: MapUiStateViewModel,
    private val logMapEvent: (category: String, message: String) -> Unit,
    private val showSnackbar: (String) -> Unit,
) {
    private companion object {
        // Point-label safeguards: cap labelled points, hide at low zoom, throttle distance refreshes.
        const val LABEL_MAX_COUNT = 60
        const val LABEL_MIN_ZOOM = 14f
        const val LABEL_DISTANCE_THROTTLE_MS = 2000L
    }

    // Survey meter grid: cycles Off → Auto → Fine → Coarse. Session state via MapUiState.
    var gridMode = MapGridMode.OFF
        private set
    var currentGridSpacingM: Double? = null
        private set
    private var mapGridRow: View? = null
    private var txtMapGrid: TextView? = null
    private val gridLines = mutableListOf<Polyline>()

    // Point labels (separate marker layer; glyph markers/clicks untouched). Session-only via MapUiState.
    var pointLabelMode = PointLabelMode.OFF
        private set
    private var mapLabelsRow: View? = null
    private var txtMapLabels: TextView? = null
    private val labelMarkers = mutableMapOf<String, Marker>()
    private val labelDescriptorCache = mutableMapOf<String, BitmapDescriptor>()
    private var lastDistanceLabelMs = 0L
    private val boundaryLines = mutableListOf<Polyline>()
    private var showBoundaries = false

    private fun requireContext(): Context = checkNotNull(context()) { "Fragment not attached to a context." }

    /** Restores the session's grid/label modes before the views and map are wired. */
    fun restoreModes(grid: MapGridMode, labels: PointLabelMode) {
        gridMode = grid
        pointLabelMode = labels
    }

    /** Wires the Grid and Labels rows of the Map Tools panel. */
    fun bindViews(root: View) {
        mapGridRow = root.findViewById(R.id.btnMapGrid)
        txtMapGrid = root.findViewById(R.id.txt_map_grid)
        mapGridRow?.setOnClickListener { cycleGrid() }
        updateGridButton()
        mapLabelsRow = root.findViewById(R.id.btnMapLabels)
        txtMapLabels = root.findViewById(R.id.txt_map_labels)
        mapLabelsRow?.setOnClickListener { cycleLabels() }
        updateLabelsButton()
    }

    /** Camera settled: Auto grid spacing adapts to zoom/pan; labels re-apply the zoom gate. */
    fun onCameraIdle() {
        if (gridMode != MapGridMode.OFF) redrawGrid()
        if (pointLabelMode != PointLabelMode.OFF) applyPointLabels()   // zoom-gate + reconcile
    }

    /** Settings → Coordinates changed: elevation labels follow the new height system/units. */
    fun onCoordinateSettingsChanged() {
        if (pointLabelMode == PointLabelMode.ELEVATION) runCatching { applyPointLabels() }
    }

    /** Distance labels follow the live position, throttled to avoid heavy marker rebuilds. */
    fun refreshDistanceLabelsIfDue() {
        if (pointLabelMode == PointLabelMode.DISTANCE) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastDistanceLabelMs >= LABEL_DISTANCE_THROTTLE_MS) {
                lastDistanceLabelMs = now
                try { applyPointLabels() } catch (e: Exception) { logFail("live.applyPointLabels", e) }
            }
        }
    }

    /**
     * The view (and its map) is going away: remove the grid lines and label markers, drop the label
     * cache and the toolbar row views. Modes are kept for the recreated view.
     */
    fun onDestroyView() {
        try { clearGrid(); removeAllPointLabels() } catch (e: Exception) { logFail("onDestroyView cleanup", e) }
        labelDescriptorCache.clear()
        boundaryLines.clear()
        mapGridRow = null; txtMapGrid = null; mapLabelsRow = null; txtMapLabels = null
    }

    // ── Grid / Boundary (unchanged internal logic) ─────────────────────────────

    /** Advances Off → Auto → Fine → Coarse → Off and redraws. */
    private fun cycleGrid() {
        gridMode = gridMode.next()
        mapUiVm.update { it.copy(gridMode = gridMode) }
        redrawGrid()
        logMapEvent("MAP", "Map grid mode changed mode=${gridMode.prefKey}")
        showSnackbar("Grid: ${MapGrid.buttonLabel(gridMode, currentGridSpacingM)}")
    }

    private fun clearGrid() {
        gridLines.forEach { try { it.remove() } catch (_: Exception) {} }
        gridLines.clear()
    }

    /**
     * Map scale at the camera centre in metres per **dp** — Google Web-Mercator formula. Android map
     * tiles are 256 dp at zoom 0, so 156543.03392·cos(lat)/2^zoom is per dp, not per physical pixel.
     */
    private fun metersPerDp(): Double {
        val cam = map()?.cameraPosition ?: return Double.NaN
        return 156543.03392 * cos(Math.toRadians(cam.target.latitude)) / 2.0.pow(cam.zoom.toDouble())
    }

    /**
     * Resolves the spacing for the current mode, redraws the meter grid, and refreshes the button.
     * Called on grid cycle and on camera-idle (so Auto spacing adapts to zoom).
     */
    fun redrawGrid() {
        clearGrid()
        if (gridMode == MapGridMode.OFF) {
            currentGridSpacingM = null
            updateGridButton()
            return
        }
        val auto = MapGrid.autoSpacingMeters(metersPerDp())
        val spacing = MapGrid.spacingForMode(gridMode, auto)
        currentGridSpacingM = spacing
        if (spacing != null) drawMeterGrid(spacing)
        updateGridButton()
        // Update the diagnostic snapshot summary (no per-redraw log — camera-idle is frequent).
        val zoom = map()?.cameraPosition?.zoom ?: 0f
        MapRuntimeDiagnostics.update {
            it.copy(lastGridSummary = MapRuntimeDiagnostics.gridSummary(
                gridMode.label, currentGridSpacingM?.let { m -> MapGrid.formatSpacing(m) }, gridLines.size, zoom))
        }
    }

    /**
     * Draws a UTM-aligned metre grid: vertical lines on round easting multiples, horizontal lines on
     * round northing multiples of [spacingMeters], using geokit-core's [Utm] (forward +
     * inverse). Lines are capped per axis for performance/readability. Straight polylines between the
     * converted endpoints are an acceptable approximation at survey zoom levels (single UTM zone).
     */
    private fun drawMeterGrid(spacingMeters: Double) {
        val map = map() ?: return
        try {
            val bounds = map.projection.visibleRegion.latLngBounds
            val sw = Utm.forward(bounds.southwest.latitude, bounds.southwest.longitude)
            val ne = Utm.forward(bounds.northeast.latitude, bounds.northeast.longitude)
            // Use the centre zone/hemisphere so all lines convert consistently within one zone.
            val centre = Utm.forward(
                (bounds.southwest.latitude + bounds.northeast.latitude) / 2.0,
                (bounds.southwest.longitude + bounds.northeast.longitude) / 2.0,
            )
            val minE = minOf(sw.eastingM, ne.eastingM); val maxE = maxOf(sw.eastingM, ne.eastingM)
            val minN = minOf(sw.northingM, ne.northingM); val maxN = maxOf(sw.northingM, ne.northingM)

            val color = ContextCompat.getColor(requireContext(), R.color.map_grid_line)
            fun line(a: LatLng, b: LatLng) {
                try { gridLines.add(map.addPolyline(PolylineOptions().add(a, b).color(color).width(1.5f).zIndex(0f))) } catch (_: Exception) {}
            }

            // Vertical lines (constant easting).
            var e = Math.ceil(minE / spacingMeters) * spacingMeters
            var n = 0
            while (e <= maxE && n < MapGrid.MAX_LINES_PER_AXIS) {
                val (lat1, lon1) = Utm.inverse(e, minN, centre.zone, centre.hemisphere)
                val (lat2, lon2) = Utm.inverse(e, maxN, centre.zone, centre.hemisphere)
                line(LatLng(lat1, lon1), LatLng(lat2, lon2))
                e += spacingMeters; n++
            }
            // Horizontal lines (constant northing).
            var north = Math.ceil(minN / spacingMeters) * spacingMeters
            n = 0
            while (north <= maxN && n < MapGrid.MAX_LINES_PER_AXIS) {
                val (lat1, lon1) = Utm.inverse(minE, north, centre.zone, centre.hemisphere)
                val (lat2, lon2) = Utm.inverse(maxE, north, centre.zone, centre.hemisphere)
                line(LatLng(lat1, lon1), LatLng(lat2, lon2))
                north += spacingMeters; n++
            }
        } catch (e: Exception) { logFail("drawMeterGrid", e) }
    }

    /** Updates the Grid toolbar row label (e.g. "Auto 10 m"), active state, and content description. */
    private fun updateGridButton() {
        txtMapGrid?.text = MapGrid.buttonLabel(gridMode, currentGridSpacingM)
        mapGridRow?.contentDescription = MapGrid.contentDescription(gridMode, currentGridSpacingM)
        mapGridRow?.isActivated = gridMode != MapGridMode.OFF
    }

    // ── Point labels (separate marker layer) ────────────────────────────────────

    /** Advances Off → Name → Elevation → Distance → Off. */
    private fun cycleLabels() {
        pointLabelMode = pointLabelMode.next()
        mapUiVm.update { it.copy(pointLabelMode = pointLabelMode) }
        labelDescriptorCache.clear()
        updateLabelsButton()
        applyPointLabels()
        logMapEvent("MAP", "Map point labels changed mode=${pointLabelMode.prefKey}")
        showSnackbar("Labels: ${pointLabelMode.label}")
    }

    private fun updateLabelsButton() {
        txtMapLabels?.text = PointLabel.buttonLabel(pointLabelMode)
        mapLabelsRow?.contentDescription = PointLabel.contentDescription(pointLabelMode)
        mapLabelsRow?.isActivated = pointLabelMode != PointLabelMode.OFF
    }

    private fun removeAllPointLabels() {
        labelMarkers.values.forEach { try { it.remove() } catch (_: Exception) {} }
        labelMarkers.clear()
    }

    /**
     * Reconciles the label-marker layer with the current mode + visible coordinates. Safeguards:
     * only visible points, capped at [LABEL_MAX_COUNT], hidden below [LABEL_MIN_ZOOM]. Label markers
     * carry the same coordinate-id tag as their glyph, so a tap still opens the selected-point card.
     * Glyph/selected/stakeout/current-location markers are never touched here.
     */
    fun applyPointLabels() {
        val map = map() ?: return
        if (pointLabelMode == PointLabelMode.OFF) {
            removeAllPointLabels()
            MapRuntimeDiagnostics.update { it.copy(lastLabelsSummary = MapRuntimeDiagnostics.labelsSummary("Off", 0, 0, null)) }
            return
        }
        if ((map.cameraPosition?.zoom ?: 0f) < LABEL_MIN_ZOOM) {
            removeAllPointLabels()
            MapRuntimeDiagnostics.update { it.copy(lastLabelsSummary = MapRuntimeDiagnostics.labelsSummary(pointLabelMode.label, 0, 0, "lowZoom")) }
            return
        }

        val live = liveLatLng()
        val distanceMode = pointLabelMode == PointLabelMode.DISTANCE
        val labelled = HashSet<String>()
        var count = 0
        for ((id, coord) in markers.coordinates) {
            if (count >= LABEL_MAX_COUNT) break
            if (markers.visibility[id] != true) continue
            val dist = if (distanceMode && live != null)
                calculateDistance(live, LatLng(coord.latitude, coord.longitude)) else null
            // Elevation in the configured height reference and units (Settings → Coordinates).
            val elevText = if (pointLabelMode == PointLabelMode.ELEVATION) runCatching {
                coordinateSystemService.convertCoordinate(coord, coordSystemSettings())
                    .let { p -> p.height?.let { app.surrealar.coords.CoordinateFormat.height(p) } }
            }.getOrNull() else null
            val text = PointLabel.labelText(pointLabelMode, coord.name, coord.altitude, dist, elevText,
                distanceText = dist?.let { coordinateSystemService.distanceText(it) }) ?: continue
            val descriptor = buildLabelDescriptor(text, cache = !distanceMode) ?: continue
            val pos = LatLng(coord.latitude, coord.longitude)
            val existing = labelMarkers[id]
            if (existing == null) {
                map.addMarker(
                    MarkerOptions().position(pos).icon(descriptor)
                        .anchor(0.5f, 0f)   // label hangs just below the point/glyph tip
                        .zIndex(0.2f)
                )?.let { it.tag = id; labelMarkers[id] = it }
            } else {
                try { existing.position = pos; existing.setIcon(descriptor) } catch (_: Exception) {}
            }
            labelled.add(id)
            count++
        }
        // Drop labels for points no longer labelled (hidden/deleted/over cap/zoomed out).
        labelMarkers.keys.filter { it !in labelled }.forEach { labelMarkers.remove(it)?.remove() }
        val visibleCount = markers.visibility.visibleCount
        val reason = if (distanceMode && live == null) "noLiveFix" else if (visibleCount > labelled.size) "cap/visibility" else null
        MapRuntimeDiagnostics.update {
            it.copy(lastLabelsSummary = MapRuntimeDiagnostics.labelsSummary(
                pointLabelMode.label, labelled.size, (visibleCount - labelled.size).coerceAtLeast(0), reason))
        }
    }

    /** Renders label text (1–2 lines) into a small bitmap with a translucent halo for readability. */
    private fun buildLabelDescriptor(text: String, cache: Boolean): BitmapDescriptor? {
        if (cache) labelDescriptorCache[text]?.let { return it }
        return try {
            val density = requireContext().resources.displayMetrics.density
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = androidx.core.content.ContextCompat.getColor(requireContext(), app.surrealar.R.color.map_label_text)
                textSize = 12f * density
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val lines = text.split("\n")
            val pad = 6f * density
            val fm = textPaint.fontMetrics
            val lineH = fm.descent - fm.ascent
            val width = (lines.maxOf { textPaint.measureText(it) } + pad * 2).toInt().coerceAtLeast(1)
            val height = (lineH * lines.size + pad * 2).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = androidx.core.content.ContextCompat.getColor(requireContext(), app.surrealar.R.color.map_label_bg)
            }
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 6f * density, 6f * density, bgPaint)
            var y = pad - fm.ascent
            for (line in lines) { canvas.drawText(line, width / 2f, y, textPaint); y += lineH }
            BitmapDescriptorFactory.fromBitmap(bmp).also { if (cache) labelDescriptorCache[text] = it }
        } catch (e: Exception) { logFail("buildLabelDescriptor", e); null }
    }

    @Suppress("unused")   // not wired to any control yet (kept from the original fragment)
    private fun toggleBoundaryLines() {
        showBoundaries = !showBoundaries
        if (showBoundaries) drawBoundaryLines()
        else { boundaryLines.forEach { it.remove() }; boundaryLines.clear() }
    }

    private fun drawBoundaryLines() {
        try {
            val coords = markers.coordinates.values.filter { markers.visibility[it.id] == true }.sortedBy { it.timestamp }
            if (coords.size < 2) return
            for (i in 0 until coords.size - 1) {
                val s = LatLng(coords[i].latitude, coords[i].longitude)
                val e = LatLng(coords[i + 1].latitude, coords[i + 1].longitude)
                try { map()?.addPolyline(PolylineOptions().add(s, e).color(0xFF2196F3.toInt()).width(3f).geodesic(true))
                    ?.let { boundaryLines.add(it) } } catch (_: Exception) {}
            }
            if (coords.size >= 3) {
                val s = LatLng(coords.last().latitude, coords.last().longitude)
                val e = LatLng(coords.first().latitude, coords.first().longitude)
                try { map()?.addPolyline(PolylineOptions().add(s, e).color(0xFF2196F3.toInt()).width(3f).geodesic(true)
                    .pattern(listOf(Dash(20f), Gap(10f))))?.let { boundaryLines.add(it) } } catch (_: Exception) {}
            }
        } catch (e: Exception) { logFail("drawBoundaryLines", e) }
    }
}
