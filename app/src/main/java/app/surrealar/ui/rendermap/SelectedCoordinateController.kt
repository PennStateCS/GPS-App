package app.surrealar.ui.rendermap

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.displayIconKey
import app.surrealar.domain.model.linkedModelId
import app.surrealar.domain.repository.ModelRepository
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The selected map point and its card: the one owner of the selected coordinate id (session state in
 * [mapUiVm], referenced by id only), the card's text (position/height in the configured system, live
 * distance/bearing, saved capture quality), its icon and its buttons. Navigation and stakeout are the
 * host's: the card only calls back.
 */
internal class SelectedCoordinateController(
    private val context: () -> Context?,
    private val scope: () -> CoroutineScope,
    private val markers: CoordinateMarkerLayer,
    private val camera: MapCameraController,
    private val mapUiVm: MapUiStateViewModel,
    private val coordinateSystemService: CoordinateSystemService,
    private val modelRepository: ModelRepository,
    private val liveLatLng: () -> LatLng?,
    /** The selection changed (the drawer highlights the row). */
    private val onSelectionChanged: (String?) -> Unit,
    private val logMapEvent: (category: String, message: String) -> Unit,
    private val refreshMapDiag: () -> Unit,
) {
    var selectedCoordinateId: String? = null
        private set

    private var selectedCoordinateCard: View? = null
    private var imgSelectedIcon: ImageView? = null
    private var txtSelectedName: TextView? = null
    private var txtSelectedCoords: TextView? = null
    private var txtSelectedElevation: TextView? = null
    private var layoutSelectedLive: View? = null
    private var txtSelectedDistance: TextView? = null
    private var txtSelectedBearing: TextView? = null
    private var layoutSelectedCapture: View? = null
    private var txtSelectedSource: TextView? = null
    private var txtSelectedAccuracy: TextView? = null

    private fun requireContext(): Context = checkNotNull(context()) { "Fragment not attached to a context." }

    /** Restores the session's selection before the views are wired (the card re-shows on data load). */
    fun restoreState(id: String?) { selectedCoordinateId = id }

    fun bindViews(root: View, onStakeout: () -> Unit, onOpenAr: () -> Unit, onOpenDetails: () -> Unit) {
        selectedCoordinateCard = root.findViewById(R.id.card_selected_coordinate)
        imgSelectedIcon = root.findViewById(R.id.img_selected_icon)
        txtSelectedName = root.findViewById(R.id.txt_selected_name)
        txtSelectedCoords = root.findViewById(R.id.txt_selected_coords)
        txtSelectedElevation = root.findViewById(R.id.txt_selected_elevation)
        layoutSelectedLive = root.findViewById(R.id.layout_selected_live)
        txtSelectedDistance = root.findViewById(R.id.txt_selected_distance)
        txtSelectedBearing = root.findViewById(R.id.txt_selected_bearing)
        layoutSelectedCapture = root.findViewById(R.id.layout_selected_capture)
        txtSelectedSource = root.findViewById(R.id.txt_selected_source)
        txtSelectedAccuracy = root.findViewById(R.id.txt_selected_accuracy)

        root.findViewById<View>(R.id.btn_selected_close)?.setOnClickListener { dismissSelectedCoordinate() }
        root.findViewById<View>(R.id.btn_selected_center)?.setOnClickListener {
            selectedCoordinateId?.let { id ->
                markers.coordinate(id)?.let { c -> camera.centerOn(c.latitude, c.longitude) }
            }
        }
        root.findViewById<View>(R.id.btn_selected_stakeout)?.setOnClickListener { onStakeout() }
        root.findViewById<View>(R.id.btn_selected_ar)?.setOnClickListener { onOpenAr() }
        root.findViewById<View>(R.id.btn_selected_details)?.setOnClickListener { onOpenDetails() }
    }

    fun onDestroyView() {
        selectedCoordinateCard = null; imgSelectedIcon = null; txtSelectedName = null
        txtSelectedCoords = null; txtSelectedElevation = null; layoutSelectedLive = null
        txtSelectedDistance = null; txtSelectedBearing = null; layoutSelectedCapture = null
        txtSelectedSource = null; txtSelectedAccuracy = null
    }

    fun selectCoordinate(id: String) {
        val coord = markers.coordinate(id) ?: return
        selectedCoordinateId = id
        mapUiVm.update { it.copy(selectedCoordinateId = id) }
        onSelectionChanged(id)
        showSelectedCoordinateCard(coord)
        logMapEvent("MAP", "Map selected point opened")
        // Center map on selection, keeping zoom at least 16
        camera.centerOn(coord.latitude, coord.longitude)
    }

    /** Re-shows the selected-point card on view recreation WITHOUT moving the camera (camera is
     *  restored separately). Clears the selection if the coordinate no longer exists. */
    fun restoreSelectedCard() {
        val id = selectedCoordinateId ?: return
        val coord = markers.coordinate(id)
        if (coord == null) {
            dismissSelectedCoordinate()
            return
        }
        onSelectionChanged(id)
        showSelectedCoordinateCard(coord)
    }

    fun dismissSelectedCoordinate() {
        selectedCoordinateId = null
        mapUiVm.update { it.copy(selectedCoordinateId = null) }
        selectedCoordinateCard?.visibility = View.GONE
        onSelectionChanged(null)
        refreshMapDiag()   // keep the diagnostic snapshot's selected-active flag accurate (no log)
    }

    private fun showSelectedCoordinateCard(coord: Coordinate) {
        selectedCoordinateCard?.visibility = View.VISIBLE
        txtSelectedName?.text = coord.name.ifBlank { "—" }
        // Position and height in the system chosen under Settings → Coordinates.
        txtSelectedCoords?.text = coordinateSystemService.positionText(coord, latLonDecimals = 7)
        txtSelectedElevation?.text = "Elev: " + coordinateSystemService.heightText(coord)
        loadCoordinateIconIntoView(coord, imgSelectedIcon)

        // Live relationship (distance + bearing from the current position) — uses the existing map
        // math; hidden when there is no live fix. Does not affect stakeout.
        val live = liveLatLng()
        if (live != null) {
            val target = LatLng(coord.latitude, coord.longitude)
            val dist = calculateDistance(live, target)
            val bearing = calculateBearing(live, target)
            txtSelectedDistance?.text = "Distance: " + coordinateSystemService.distanceText(dist)
            // Azimuth (0–360° from true north) plus the surveyor's quadrant bearing, e.g.
            // "Azimuth: 45.3° (N 45°18′ E)".
            txtSelectedBearing?.text = "Azimuth: " + app.surrealar.util.AzimuthFormat.azimuthText(bearing, 1) +
                " (" + app.surrealar.util.AzimuthFormat.quadrantBearing(bearing) + ")"
            layoutSelectedLive?.visibility = View.VISIBLE
        } else {
            layoutSelectedLive?.visibility = View.GONE
        }

        // Capture-quality summary from the SAVED point metadata (not live receiver status).
        val sourceText = CoordinateText.captureSourceText(coord)
        val accText = coord.horizontalAccuracyM?.let {
            String.format(Locale.getDefault(), "Accuracy: ±%.2f m", it)
        }
        txtSelectedSource?.text = sourceText.orEmpty()
        txtSelectedSource?.visibility = if (sourceText != null) View.VISIBLE else View.INVISIBLE
        txtSelectedAccuracy?.text = accText.orEmpty()
        txtSelectedAccuracy?.visibility = if (accText != null) View.VISIBLE else View.INVISIBLE
        layoutSelectedCapture?.visibility =
            if (sourceText != null || accText != null) View.VISIBLE else View.GONE
    }

    private fun loadCoordinateIconIntoView(coord: Coordinate, iv: ImageView?) {
        iv ?: return
        val modelId = coord.linkedModelId
        if (modelId != null) {
            iv.setImageResource(R.drawable.ic_pin)
            scope().launch {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        val path = modelRepository.getModelById(modelId)?.thumbnailFilePath ?: return@withContext null
                        BitmapFactory.decodeFile(path)
                    } catch (_: Exception) { null }
                }
                if (bmp != null) { iv.clearColorFilter(); iv.setImageBitmap(bmp) }
            }
        } else {
            val iconName = coord.displayIconKey ?: ""
            @Suppress("DiscouragedApi")
            val resId = if (iconName.isNotBlank()) {
                requireContext().resources.getIdentifier(iconName, "drawable", requireContext().packageName)
            } else 0
            val drawable = (if (resId != 0) ContextCompat.getDrawable(requireContext(), resId)
                            else ContextCompat.getDrawable(requireContext(), R.drawable.ic_pin))?.mutate()
            try { drawable?.setColorFilter(coord.color, PorterDuff.Mode.SRC_IN) } catch (_: Exception) {}
            iv.setImageDrawable(drawable)
        }
    }
}
