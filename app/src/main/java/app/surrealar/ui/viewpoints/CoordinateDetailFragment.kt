package app.surrealar.ui.viewpoints

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.surrealar.R
import android.app.Activity
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.fragment.NavHostFragment
import app.surrealar.domain.model.CaptureMethod
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.CoordinateModelLink
import app.surrealar.domain.model.displayIconKey
import app.surrealar.domain.model.hasLinkedModel
import app.surrealar.domain.model.linkedModelId
import app.surrealar.domain.repository.CoordinateDisplaySettingsRepository
import app.surrealar.domain.repository.ModelRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import app.surrealar.domain.model.EmbeddedModelLocation
import app.surrealar.domain.model.Model
import app.surrealar.domain.model.ModelLocationConfidence
import app.surrealar.ui.map.MapThemeHelper
import app.surrealar.ui.models.ModelPickerActivity
import app.surrealar.ui.models.ModelViewerActivity
import app.surrealar.util.GlbGeoreferenceDetector
import app.surrealar.util.UtmConverter
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.snackbar.Snackbar
import java.io.File
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import app.surrealar.SurveyingAppEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class CoordinateDetailFragment : Fragment() {

    companion object {
        private const val ARG_ID = "arg_id"
        fun newInstance(id: String): CoordinateDetailFragment = CoordinateDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }

    @Inject lateinit var modelRepository: ModelRepository
    @Inject lateinit var settingsRepository: CoordinateDisplaySettingsRepository

    private lateinit var viewModel: CoordinatesViewModel
    private var currentId: String? = null

    // Header
    private var textName: TextView? = null
    private var rowBadges: View? = null
    private var badgeRtk: TextView? = null
    private var badgeAccuracy: TextView? = null
    private var textEmpty: TextView? = null

    // "Show list" header control (tablet two-pane; visible only when the list pane is collapsed)
    private var btnShowList: View? = null
    private var showListVisible: Boolean = false
    private var onShowListClick: (() -> Unit)? = null

    // Card views
    private var cardSummary: View? = null
    private var textSummaryNote: TextView? = null
    private var cardModel: View? = null
    private var cardLocation: View? = null
    private var cardProjection: View? = null
    private var cardGnss: View? = null
    private var cardCapture: View? = null
    private var cardAveraging: View? = null
    private var cardMotion: View? = null
    private var cardMap: View? = null

    // Card body containers (rows inflated into these)
    private var cardSummaryRows: LinearLayout? = null
    private var cardLocationRows: LinearLayout? = null
    private var cardProjectionRows: LinearLayout? = null
    private var cardGnssRows: LinearLayout? = null
    private var cardCaptureRows: LinearLayout? = null
    private var cardAveragingRows: LinearLayout? = null
    private var cardMotionRows: LinearLayout? = null

    // Linked Model card
    private var modelLinkedContainer: View? = null
    private var modelEmptyContainer: View? = null
    private var modelThumbnail: ShapeableImageView? = null
    private var modelName: TextView? = null
    private var modelMeta: TextView? = null
    private var modelStatus: TextView? = null
    private var btnViewModel: MaterialButton? = null
    private var btnOpenInAr: MaterialButton? = null
    private var btnChangeModel: MaterialButton? = null
    private var btnRemoveLink: MaterialButton? = null
    private var btnSelectModel: MaterialButton? = null

    // Map
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var lastCoordinate: Coordinate? = null

    private var showAccuracyIndicators: Boolean = true

    // Additional badge views
    private var badgeSource: TextView? = null
    private var badgeExtra: TextView? = null

    // Location card additions
    private var rowDistance: View? = null
    private var textDistance: TextView? = null
    private var crsSectionContainer: View? = null
    private var btnToggleCrs: View? = null
    private var btnCopyLatLng: View? = null
    private var btnCopyUtm: View? = null
    private var cardCrsRows: LinearLayout? = null
    private var crsExpanded = false
    private var distanceJob: Job? = null

    // Capture quality note
    private var textCaptureQualityNote: TextView? = null

    // Model placement note
    private var textModelPlacement: TextView? = null

    // Launches the model picker for Select / Change Model
    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val id = data.getStringExtra(ModelPickerActivity.EXTRA_SELECTED_MODEL_ID)
                ?: return@registerForActivityResult
            onModelChosen(id)
        }
    }

    // ── Format helpers ─────────────────────────────────────────────────────────

    // Display formatting/labels live in CoordinateDetailFormatter (pure + unit-tested). These thin
    // delegates keep the existing call sites unchanged.
    private fun fmt6(v: Double) = CoordinateDetailFormatter.fmt6(v)
    private fun fmtM2(v: Double) = CoordinateDetailFormatter.fmtM2(v)
    private fun fmtM3(v: Double) = CoordinateDetailFormatter.fmtM3(v)
    private fun fmtDop(v: Double) = CoordinateDetailFormatter.fmtDop(v)
    private fun rtkLabel(s: String?) = CoordinateDetailFormatter.rtkLabel(s)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_coordinate_detail, container, false)

        textName          = v.findViewById(R.id.text_name)
        btnShowList       = v.findViewById(R.id.btn_show_list)
        rowBadges         = v.findViewById(R.id.row_badges)
        badgeRtk          = v.findViewById(R.id.badge_rtk)
        badgeAccuracy     = v.findViewById(R.id.badge_accuracy)
        textEmpty         = v.findViewById(R.id.text_empty)

        cardSummary       = v.findViewById(R.id.card_summary)
        textSummaryNote   = v.findViewById(R.id.text_summary_note)
        cardModel         = v.findViewById(R.id.card_model)
        cardLocation      = v.findViewById(R.id.card_location)
        cardProjection    = v.findViewById(R.id.card_projection)
        cardGnss          = v.findViewById(R.id.card_gnss)
        cardCapture       = v.findViewById(R.id.card_capture)
        cardAveraging     = v.findViewById(R.id.card_averaging)
        cardMotion        = v.findViewById(R.id.card_motion)
        cardMap           = v.findViewById(R.id.card_map)

        modelLinkedContainer = v.findViewById(R.id.model_linked_container)
        modelEmptyContainer  = v.findViewById(R.id.model_empty_container)
        modelThumbnail       = v.findViewById(R.id.model_thumbnail)
        modelName            = v.findViewById(R.id.model_name)
        modelMeta            = v.findViewById(R.id.model_meta)
        modelStatus          = v.findViewById(R.id.model_status)
        btnViewModel         = v.findViewById(R.id.btn_view_model)
        btnOpenInAr          = v.findViewById(R.id.btn_open_in_ar)
        btnChangeModel       = v.findViewById(R.id.btn_change_model)
        btnRemoveLink        = v.findViewById(R.id.btn_remove_link)
        btnSelectModel       = v.findViewById(R.id.btn_select_model)

        btnChangeModel?.setOnClickListener { launchModelPicker() }
        btnSelectModel?.setOnClickListener { launchModelPicker() }
        btnRemoveLink?.setOnClickListener { confirmRemoveLink() }

        cardSummaryRows   = v.findViewById(R.id.card_summary_rows)
        cardLocationRows  = v.findViewById(R.id.card_location_rows)
        cardProjectionRows= v.findViewById(R.id.card_projection_rows)
        cardGnssRows      = v.findViewById(R.id.card_gnss_rows)
        cardCaptureRows   = v.findViewById(R.id.card_capture_rows)
        cardAveragingRows = v.findViewById(R.id.card_averaging_rows)
        cardMotionRows    = v.findViewById(R.id.card_motion_rows)

        badgeSource            = v.findViewById(R.id.badge_source)
        badgeExtra             = v.findViewById(R.id.badge_extra)
        rowDistance            = v.findViewById(R.id.row_distance)
        textDistance           = v.findViewById(R.id.text_distance)
        crsSectionContainer    = v.findViewById(R.id.crs_section_container)
        btnToggleCrs           = v.findViewById(R.id.btn_toggle_crs)
        btnCopyLatLng          = v.findViewById(R.id.btn_copy_latlng)
        btnCopyUtm             = v.findViewById(R.id.btn_copy_utm)
        cardCrsRows            = v.findViewById(R.id.card_crs_rows)
        textCaptureQualityNote = v.findViewById(R.id.text_capture_quality_note)
        textModelPlacement     = v.findViewById(R.id.text_model_placement)

        btnCopyLatLng?.setOnClickListener {
            lastCoordinate?.let { c ->
                val text = "${fmt6(c.latitude)}, ${fmt6(c.longitude)}"
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Lat/Lon", text))
                Toast.makeText(requireContext(), "Coordinates copied", Toast.LENGTH_SHORT).show()
            }
        }

        btnCopyUtm?.setOnClickListener {
            lastCoordinate?.let { c ->
                val zone = c.utmZone ?: return@let
                val e = c.easting ?: return@let
                val n = c.northing ?: return@let
                val text = "$zone ${String.format(Locale.US, "%.3f", e)} ${String.format(Locale.US, "%.3f", n)}"
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("UTM", text))
                Toast.makeText(requireContext(), "UTM coordinates copied", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleCrs?.setOnClickListener {
            crsExpanded = !crsExpanded
            applyCrsExpansion()
        }

        mapView = v.findViewById(R.id.mapView)
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync { map ->
            googleMap = map
            map.uiSettings.apply {
                isMapToolbarEnabled = false
                isZoomControlsEnabled = false
            }
            MapThemeHelper.applyTheme(requireContext(), map, map.mapType)
            lastCoordinate?.let { updateMapMarker(it) }
        }

        v.findViewById<View>(R.id.btn_copy_coordinates)?.setOnClickListener {
            lastCoordinate?.let { c ->
                val text = "${fmt6(c.latitude)}, ${fmt6(c.longitude)}"
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Coordinates", text))
                Toast.makeText(requireContext(), "Coordinates copied", Toast.LENGTH_SHORT).show()
            }
        }

        return v
    }

    override fun onResume()    { super.onResume();    mapView?.onResume() }
    override fun onPause()     { super.onPause();     mapView?.onPause() }
    override fun onDestroy()   { super.onDestroy();   mapView?.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[CoordinatesViewModel::class.java]

        view.findViewById<View>(R.id.btn_edit_coordinate)?.setOnClickListener { launchEditDialog() }
        currentId = arguments?.getString(ARG_ID)

        // Pull the current collapse state from the parent two-pane host (if any) so the
        // header's show-list button reflects it as soon as the view is created.
        (parentFragment as? CoordinatesFragment)?.onDetailViewReady(this) ?: applyShowListControl()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.coordinateDisplaySettings.collect { settings ->
                    showAccuracyIndicators = settings.showAccuracyIndicators
                    lastCoordinate?.let { bindCoordinate(it) } ?: loadCurrentId()
                }
            }
        }
    }

    // ── Show-list header control ─────────────────────────────────────────────────

    /**
     * Configures the header's "show list" button. Called by the two-pane host
     * ([CoordinatesFragment]) to reflect whether the left list pane is collapsed.
     * Safe to call before or after the view exists.
     */
    fun setShowListControl(visible: Boolean, onClick: () -> Unit) {
        showListVisible = visible
        onShowListClick = onClick
        applyShowListControl()
    }

    private fun applyShowListControl() {
        val btn = btnShowList ?: return
        btn.visibility = if (showListVisible) View.VISIBLE else View.GONE
        btn.setOnClickListener { onShowListClick?.invoke() }
    }

    // ── Data loading ───────────────────────────────────────────────────────────

    private fun loadCurrentId() {
        val id = currentId
        if (id.isNullOrBlank()) { showEmpty(); return }
        viewLifecycleOwner.lifecycleScope.launch {
            val coord = viewModel.getById(id)
            if (coord == null) showEmpty() else bindCoordinate(coord)
        }
    }

    fun updateId(newId: String) {
        Log.d("CoordinateDetailFragment", "updateId: $currentId → $newId")
        if (newId == currentId) return
        currentId = newId
        loadCurrentId()
    }

    // ── Dynamic row helper ─────────────────────────────────────────────────────

    /**
     * Inflates the `item_coord_detail_row` layout into [container] with the given label/value.
     * A thin divider is prepended before each row after the first.
     */
    private fun addDetailRow(container: LinearLayout?, label: String, value: String) {
        container ?: return
        val inflater = LayoutInflater.from(requireContext())
        if (container.childCount > 0) {
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.setMargins(20.dp, 0, 20.dp, 0) }
                setBackgroundColor(
                    ContextCompat.getColor(requireContext(),
                        com.google.android.material.R.color.material_on_surface_stroke)
                )
                alpha = 0.12f
            }
            container.addView(divider)
        }
        val row = inflater.inflate(R.layout.item_coord_detail_row, container, false)
        row.findViewById<TextView>(R.id.row_label).text = label
        row.findViewById<TextView>(R.id.row_value).text = value
        container.addView(row)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ── State: empty ───────────────────────────────────────────────────────────

    private fun showEmpty() {
        distanceJob?.cancel(); distanceJob = null
        rowDistance?.visibility = View.GONE
        textName?.text = "—"
        textEmpty?.visibility = View.VISIBLE
        rowBadges?.visibility = View.GONE
        listOf(cardSummary, cardModel, cardLocation, cardProjection, cardGnss, cardCapture,
               cardAveraging, cardMotion, cardMap).forEach { it?.visibility = View.GONE }
    }

    // ── State: bound ───────────────────────────────────────────────────────────

    private fun bindCoordinate(c: Coordinate) {
        distanceJob?.cancel(); distanceJob = null
        rowDistance?.visibility = View.GONE

        lastCoordinate = c
        textEmpty?.visibility = View.GONE
        textName?.text = c.name.ifBlank { "—" }

        // Clear all dynamic containers before re-populating
        listOf(cardSummaryRows, cardLocationRows, cardProjectionRows, cardGnssRows,
               cardCaptureRows, cardAveragingRows, cardMotionRows, cardCrsRows)
            .forEach { it?.removeAllViews() }

        bindSummaryCard(c)
        bindModelCard(c)
        bindLocationCard(c)
        bindProjectionCard(c)
        bindGnssCard(c)
        bindCaptureCard(c)
        bindAveragingCard(c)
        bindMotionCard(c)
        bindMapCard(c)
        bindBadges(c)
        startDistanceBearing(c)
    }

    private fun captureMethodLabel(m: String?): String? = CoordinateDetailFormatter.captureMethodLabel(m)

    private fun bindSummaryCard(c: Coordinate) {
        // Note shown as flowing text; point code/type as compact rows
        val note = c.note?.takeIf { it.isNotBlank() }
        textSummaryNote?.visibility = if (note != null) View.VISIBLE else View.GONE
        textSummaryNote?.text = note ?: ""

        cardSummary?.visibility = if (note != null) View.VISIBLE else View.GONE
    }

    private fun bindLocationCard(c: Coordinate) {
        addDetailRow(cardLocationRows, "Latitude",  "${fmt6(c.latitude)}°")
        addDetailRow(cardLocationRows, "Longitude", "${fmt6(c.longitude)}°")
        addDetailRow(cardLocationRows, "Altitude (ellipsoidal)", fmtM2(c.altitude))
        c.altitudeMsl?.let { addDetailRow(cardLocationRows, "Altitude (MSL)", fmtM2(it)) }
        c.geoidSeparationM?.let { addDetailRow(cardLocationRows, "Geoid separation", fmtM3(it)) }

        // CRS rows go into the collapsible section inside the same card
        val hasCrs = c.easting != null || c.northing != null || c.utmZone != null || c.crsEpsg != null
        if (hasCrs) {
            c.easting?.let  { addDetailRow(cardCrsRows, "Easting",  String.format(Locale.US, "%.3f m", it)) }
            c.northing?.let { addDetailRow(cardCrsRows, "Northing", String.format(Locale.US, "%.3f m", it)) }
            c.utmZone?.takeIf { it.isNotBlank() }?.let { addDetailRow(cardCrsRows, "UTM zone", it) }
            c.crsEpsg?.let { addDetailRow(cardCrsRows, "CRS (EPSG)", it.toString()) }
            val hasUtm = c.easting != null && c.northing != null && c.utmZone != null
            btnCopyUtm?.visibility = if (hasUtm) View.VISIBLE else View.GONE
        } else {
            btnCopyUtm?.visibility = View.GONE
        }
        btnToggleCrs?.visibility = if (hasCrs) View.VISIBLE else View.GONE
        if (hasCrs) applyCrsExpansion() else crsSectionContainer?.visibility = View.GONE

        cardLocation?.visibility = View.VISIBLE
    }

    private fun applyCrsExpansion() {
        crsSectionContainer?.visibility = if (crsExpanded) View.VISIBLE else View.GONE
        (btnToggleCrs as? com.google.android.material.button.MaterialButton)?.text =
            if (crsExpanded) "Hide CRS / UTM" else "Show CRS / UTM"
    }

    private fun bindProjectionCard(c: Coordinate) {
        // CRS/projection content is now embedded in the Location card's collapsible section.
        cardProjection?.visibility = View.GONE
    }

    private fun providerLabel(p: String?): String? = CoordinateDetailFormatter.providerLabel(p)

    private fun bindGnssCard(c: Coordinate) {
        // Provider moved to capture card; only GNSS quality fields here
        c.rtkStatus?.takeIf { it.isNotBlank() }?.let {
            addDetailRow(cardGnssRows, "RTK status", rtkLabel(it))
        }
        c.hdop?.let    { addDetailRow(cardGnssRows, "HDOP", fmtDop(it)) }
        c.vDop?.let    { addDetailRow(cardGnssRows, "VDOP", fmtDop(it)) }
        c.pDop?.let    { addDetailRow(cardGnssRows, "PDOP", fmtDop(it)) }
        if (showAccuracyIndicators) {
            c.horizontalAccuracyM?.let { addDetailRow(cardGnssRows, "Horiz. accuracy", fmtM3(it)) }
            c.verticalAccuracyM?.let   { addDetailRow(cardGnssRows, "Vert. accuracy",  fmtM3(it)) }
        }
        val used = c.satsUsed
        val visible = c.satsVisible
        when {
            used != null && visible != null && used <= visible ->
                addDetailRow(cardGnssRows, "Satellites", "$used used / $visible visible")
            used != null && visible != null -> {
                addDetailRow(cardGnssRows, "Satellites used", "$used")
                addDetailRow(cardGnssRows, "Satellites visible", "$visible")
            }
            used != null    -> addDetailRow(cardGnssRows, "Satellites used", "$used")
            visible != null -> addDetailRow(cardGnssRows, "Satellites visible", "$visible")
        }
        c.correctionSource?.takeIf { it.isNotBlank() }?.let {
            val age = c.correctionAgeS?.let { s -> String.format(Locale.US, " (%.1f s old)", s) } ?: ""
            addDetailRow(cardGnssRows, "Correction", it + age)
        }
        c.correctionStationId?.takeIf { it.isNotBlank() }?.let {
            addDetailRow(cardGnssRows, "Station ID", it)
        }
        c.multipathIndex?.let {
            addDetailRow(cardGnssRows, "Multipath index", fmtDop(it))
        }
        if (cardGnssRows?.childCount ?: 0 > 0) cardGnss?.visibility = View.VISIBLE
        else cardGnss?.visibility = View.GONE
    }

    private fun bindCaptureCard(c: Coordinate) {
        // Source device and capture method first (most user-relevant)
        c.sourceDevice?.takeIf { it.isNotBlank() }?.let { addDetailRow(cardCaptureRows, "Source device", it) }
        captureMethodLabel(c.captureMethod)?.let { addDetailRow(cardCaptureRows, "Capture method", it) }
        // Provider only when informative (not "other")
        providerLabel(c.provider)?.let { addDetailRow(cardCaptureRows, "Provider", it) }
        val dateObj = Date(c.timestamp)
        addDetailRow(cardCaptureRows, "Date", SimpleDateFormat("MM/dd/yyyy", Locale.US).format(dateObj))
        addDetailRow(cardCaptureRows, "Time", SimpleDateFormat("h:mm:ss a", Locale.US).format(dateObj).lowercase(Locale.US))
        c.timestampSource?.takeIf { it.isNotBlank() }?.let { addDetailRow(cardCaptureRows, "Time source", it) }
        c.appVersion?.takeIf { it.isNotBlank() }?.let { addDetailRow(cardCaptureRows, "App version", it) }

        // Quality/accuracy context note
        val note = when (c.captureMethod?.lowercase(Locale.US)) {
            "internal_gps", "averaged" ->
                "Captured with Internal GPS. Accuracy may be lower than with an external GNSS receiver."
            else -> null
        }
        if (note != null) {
            textCaptureQualityNote?.text = note
            textCaptureQualityNote?.visibility = View.VISIBLE
        } else {
            textCaptureQualityNote?.visibility = View.GONE
        }

        cardCapture?.visibility = View.VISIBLE
    }

    private fun bindAveragingCard(c: Coordinate) {
        val hasData = (c.averagedSamples ?: 0) > 0 || c.averageDurationMs != null ||
            c.stdLatM != null || c.stdLonM != null || c.stdAltM != null
        if (!hasData) { cardAveraging?.visibility = View.GONE; return }
        c.averagedSamples?.takeIf { it > 0 }?.let {
            addDetailRow(cardAveragingRows, "Samples averaged", it.toString())
        }
        c.averageDurationMs?.let {
            val secs = it / 1000.0
            addDetailRow(cardAveragingRows, "Averaging duration", String.format(Locale.US, "%.1f s", secs))
        }
        c.stdLatM?.let  { addDetailRow(cardAveragingRows, "Std dev (lat)",  fmtM3(it)) }
        c.stdLonM?.let  { addDetailRow(cardAveragingRows, "Std dev (lon)",  fmtM3(it)) }
        c.stdAltM?.let  { addDetailRow(cardAveragingRows, "Std dev (alt)",  fmtM3(it)) }
        cardAveraging?.visibility = View.VISIBLE
    }

    private fun bindMotionCard(c: Coordinate) {
        val hasData = c.speedMps != null || c.courseDeg != null
        if (!hasData) { cardMotion?.visibility = View.GONE; return }
        c.speedMps?.let  { addDetailRow(cardMotionRows, "Speed",  String.format(Locale.US, "%.2f m/s", it)) }
        c.courseDeg?.let { addDetailRow(cardMotionRows, "Course", String.format(Locale.US, "%.1f°", it)) }
        cardMotion?.visibility = View.VISIBLE
    }

    private fun bindMapCard(c: Coordinate) {
        cardMap?.visibility = View.VISIBLE
        updateMapMarker(c)
    }

    // ── Linked Model card ────────────────────────────────────────────────────────

    private fun bindModelCard(c: Coordinate) {
        cardModel?.visibility = View.VISIBLE
        val modelId = c.linkedModelId
        if (modelId == null) {
            showModelEmptyState()
            return
        }
        // Resolve model + file existence off the main thread, then render.
        viewLifecycleOwner.lifecycleScope.launch {
            val model = withContext(Dispatchers.IO) {
                try {
                    modelRepository.getModelById(modelId)
                } catch (e: Exception) {
                    Log.w("CoordinateDetailFragment", "Failed to load linked model $modelId", e)
                    null
                }
            }
            if (!isAdded || lastCoordinate?.linkedModelId != modelId) return@launch
            val fileExists = model != null && withContext(Dispatchers.IO) {
                try { model.filePath.isNotBlank() && File(model.filePath).exists() } catch (_: Exception) { false }
            }
            showModelLinkedState(model, fileExists)
        }
    }

    private fun showModelEmptyState() {
        modelLinkedContainer?.visibility = View.GONE
        modelEmptyContainer?.visibility = View.VISIBLE
    }

    private fun showModelLinkedState(model: Model?, fileExists: Boolean) {
        modelEmptyContainer?.visibility = View.GONE
        modelLinkedContainer?.visibility = View.VISIBLE

        modelName?.text = model?.name ?: "Model unavailable"
        modelMeta?.text = model?.let { buildModelMeta(it) } ?: ""
        modelMeta?.visibility = if (model == null) View.GONE else View.VISIBLE

        // Placement note
        val placementNote = when (lastCoordinate?.captureMethod?.lowercase(Locale.US)) {
            "model_embedded" -> "Placement: Model embedded location"
            else             -> "Placement: Uses saved coordinate"
        }
        textModelPlacement?.text = placementNote
        textModelPlacement?.visibility = View.VISIBLE

        val missing = model == null || !fileExists
        modelStatus?.visibility = if (missing) View.VISIBLE else View.GONE
        if (missing) modelStatus?.text = getString(R.string.model_file_missing)

        // Thumbnail (or default model icon)
        loadModelThumbnail(model, fileExists)

        // View Model only when the file is actually present
        btnViewModel?.visibility = if (model != null && fileExists) View.VISIBLE else View.GONE
        btnViewModel?.setOnClickListener { if (model != null) openModelViewer(model) }
        btnOpenInAr?.setOnClickListener { openInAr() }
    }

    private fun buildModelMeta(model: Model): String {
        val parts = mutableListOf<String>()
        model.getFileExtension().takeIf { it.isNotBlank() }?.let { parts += it.uppercase(Locale.US) }
        if (model.fileSize > 0) parts += model.getFormattedSize()
        parts += "Added ${model.getFormattedDateAdded()}"
        return parts.joinToString("  ·  ")
    }

    private fun loadModelThumbnail(model: Model?, fileExists: Boolean) {
        val iv = modelThumbnail ?: return
        val thumbPath = model?.thumbnailFilePath
        if (model == null || thumbPath.isNullOrBlank()) { setDefaultThumbnail(iv); return }
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val f = File(thumbPath)
                    if (f.exists()) BitmapFactory.decodeFile(thumbPath) else null
                } catch (_: Exception) { null }
            }
            if (!isAdded) return@launch
            if (bmp != null) {
                androidx.core.widget.ImageViewCompat.setImageTintList(iv, null)
                iv.setImageBitmap(bmp)
            } else {
                setDefaultThumbnail(iv)
            }
        }
    }

    private fun setDefaultThumbnail(iv: ImageView) {
        iv.setImageResource(R.drawable.ic_models_24)
        val tint = com.google.android.material.color.MaterialColors.getColor(
            iv, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.GRAY
        )
        androidx.core.widget.ImageViewCompat.setImageTintList(
            iv, android.content.res.ColorStateList.valueOf(tint)
        )
    }

    // ── Model actions ─────────────────────────────────────────────────────────

    private fun launchModelPicker() {
        modelPickerLauncher.launch(ModelPickerActivity.newIntent(requireContext(), "Choose a Model"))
    }

    /** Handles a model chosen from the picker: detect embedded location, then link. */
    private fun onModelChosen(modelId: String) {
        if (lastCoordinate == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val model = withContext(Dispatchers.IO) {
                try {
                    modelRepository.getModelById(modelId)
                } catch (_: Exception) { null }
            }
            // Prefer the origin captured at import (reprojection erases the in-file signal);
            // fall back to detection for models imported before it was stored.
            val embedded = if (model?.embeddedLatitude != null && model.embeddedLongitude != null) {
                EmbeddedModelLocation(
                    latitude = model.embeddedLatitude,
                    longitude = model.embeddedLongitude,
                    altitudeMeters = model.embeddedAltitudeM,
                    confidence = ModelLocationConfidence.HIGH,
                    source = "GLB_POSITION_WGS_LIKE"
                )
            } else withContext(Dispatchers.IO) {
                val fp = model?.filePath
                if (!fp.isNullOrBlank()) GlbGeoreferenceDetector.detect(File(fp)) else null
            }
            if (!isAdded) return@launch
            if (embedded != null) showModelLocationDialog(embedded, modelId)
            else linkModel(modelId, embedded = null, useModelLocation = false)
        }
    }

    private fun showModelLocationDialog(embedded: EmbeddedModelLocation, modelId: String) {
        val altStr = embedded.altitudeMeters?.let { String.format(Locale.US, "%.2f m", it) } ?: "—"
        val confidenceNote = when (embedded.confidence) {
            ModelLocationConfidence.HIGH   -> ""
            ModelLocationConfidence.MEDIUM -> "\n(Confidence: medium)"
            ModelLocationConfidence.LOW    -> "\n(Confidence: low)"
        }
        val message = String.format(
            Locale.US,
            "This model appears to contain an embedded location:\n\n" +
                "Latitude:  %.7f\nLongitude: %.7f\nAltitude:  %s%s\n\n" +
                "How would you like to place it?",
            embedded.latitude, embedded.longitude, altStr, confidenceNote
        )
        if (!isAdded || activity == null) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Model Location Detected")
            .setMessage(message)
            .setPositiveButton("Use Model Location") { _, _ ->
                linkModel(modelId, embedded, useModelLocation = true)
            }
            .setNegativeButton("Use Current Coordinate") { _, _ ->
                linkModel(modelId, embedded, useModelLocation = false)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun linkModel(modelId: String, embedded: EmbeddedModelLocation?, useModelLocation: Boolean) {
        val coord = lastCoordinate ?: return
        val updated = if (useModelLocation && embedded != null) {
            val lat = embedded.latitude
            val lon = embedded.longitude
            val alt = embedded.altitudeMeters ?: coord.altitude
            val utm = try { UtmConverter.latLonToUtm(lat, lon) } catch (_: Exception) { null }
            // Position now comes from the model file, so mark provenance accordingly.
            coord.copy(
                icon = CoordinateModelLink.toLegacyIcon(modelId),
                modelId = modelId,
                iconKey = null,
                latitude = lat, longitude = lon, altitude = alt,
                provider = "model",
                captureMethod = CaptureMethod.MODEL_EMBEDDED.storageValue,
                updatedAt = System.currentTimeMillis(),
                easting = utm?.easting, northing = utm?.northing, utmZone = utm?.utmZone
            )
        } else {
            coord.copy(
                icon = CoordinateModelLink.toLegacyIcon(modelId),
                modelId = modelId,
                iconKey = null,
                updatedAt = System.currentTimeMillis()
            )
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.updateCoordinate(updated)
            lastCoordinate = updated
            bindCoordinate(updated)
            showSnackbar(if (useModelLocation) "Model linked · coordinate moved to model location" else "Model linked")
        }
    }

    private fun confirmRemoveLink() {
        val coord = lastCoordinate ?: return
        if (!coord.hasLinkedModel) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove linked model?")
            .setMessage("This unlinks the model from this coordinate. The model file is not deleted.")
            .setPositiveButton("Remove Link") { _, _ -> removeLink() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeLink() {
        val coord = lastCoordinate ?: return
        // Clearing the model link also clears every per-coordinate placement override so a future
        // re-link starts from the model's defaults rather than stale values.
        val updated = coord.copy(
            icon = "", modelId = null, iconKey = null, updatedAt = System.currentTimeMillis(),
            modelScale = null, modelYawDeg = null, modelPitchDeg = null, modelRollDeg = null,
            modelVerticalOffsetM = null,
            modelOriginOffsetXM = null, modelOriginOffsetYM = null, modelOriginOffsetZM = null
        )
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.updateCoordinate(updated)
            lastCoordinate = updated
            bindCoordinate(updated)
            showSnackbar("Model link removed")
        }
    }

    private fun openModelViewer(model: Model) {
        try {
            startActivity(ModelViewerActivity.newIntent(requireContext(), model.filePath, model.name))
        } catch (e: Exception) {
            Log.w("CoordinateDetailFragment", "openModelViewer failed", e)
            showSnackbar("Unable to open model")
        }
    }

    private fun openInAr() {
        try {
            val navHost = requireActivity().supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
            val nav = navHost?.navController
            if (nav != null) nav.navigate(R.id.nav_open_in_ar)
            else showSnackbar("Open the AR tab to view models")
        } catch (e: Exception) {
            Log.w("CoordinateDetailFragment", "openInAr failed", e)
            showSnackbar("Unable to open AR")
        }
    }

    private fun showSnackbar(msg: String) {
        view?.let { Snackbar.make(it, msg, Snackbar.LENGTH_SHORT).show() }
    }

    // ── Badges ─────────────────────────────────────────────────────────────────

    private fun bindBadges(c: Coordinate) {
        // Badge label/color/visibility decisions live in CoordinateDetailUiMapper (pure + tested);
        // this method only applies the resulting state to the TextViews.
        val badges = CoordinateDetailUiMapper.badges(c, showAccuracyIndicators)
        applyBadge(badgeSource, badges.source)
        applyBadge(badgeRtk, badges.fix)
        applyBadge(badgeExtra, badges.extra)
        applyBadge(badgeAccuracy, badges.accuracy)
        rowBadges?.visibility = if (badges.anyVisible) View.VISIBLE else View.GONE
    }

    /** Renders a single badge: applies [state] to [tv], or hides it when [state] is null. */
    private fun applyBadge(tv: TextView?, state: BadgeUi?) {
        tv ?: return
        if (state == null) { tv.visibility = View.GONE; return }
        tv.text = state.text
        tv.backgroundTintList = android.content.res.ColorStateList.valueOf(state.colorArgb)
        // Only the fix/accuracy badges carry an accessibility description; leave the others' as-is
        // (matches the previous per-badge behavior — source/extra never set contentDescription).
        state.contentDescription?.let { tv.contentDescription = it }
        tv.visibility = View.VISIBLE
    }

    // ── Map ────────────────────────────────────────────────────────────────────

    private fun updateMapMarker(c: Coordinate) {
        val map = googleMap ?: return
        val latLng = LatLng(c.latitude, c.longitude)
        map.clear()
        val marker = map.addMarker(MarkerOptions().position(latLng).title(c.name))
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
        viewLifecycleOwner.lifecycleScope.launch {
            val descriptor = buildMarkerDescriptor(c.linkedModelId, c.displayIconKey, c.color)
            if (descriptor != null) marker?.setIcon(descriptor)
        }
    }

    private suspend fun buildMarkerDescriptor(modelId: String?, iconKey: String?, colorInt: Int): BitmapDescriptor? {
        val ctx = context ?: return null
        if (modelId != null) {
            return withContext(Dispatchers.IO) {
                try {
                    val model = modelRepository.getModelById(modelId) ?: return@withContext null
                    val thumbPath = model.thumbnailFilePath
                    if (thumbPath.isNullOrBlank()) return@withContext null
                    val thumbBmp = BitmapFactory.decodeFile(thumbPath) ?: return@withContext null
                    withContext(Dispatchers.Main) { buildModelMarkerBitmap(thumbBmp, ctx) }
                } catch (e: Exception) {
                    Log.w("CoordinateDetailFragment", "Failed to load model thumbnail for marker", e)
                    null
                }
            }
        }
        if (iconKey.isNullOrBlank()) return null
        val resId = ctx.resources.getIdentifier(iconKey, "drawable", ctx.packageName)
        if (resId == 0) return null
        val d = ContextCompat.getDrawable(ctx, resId) ?: return null
        val size = (32 * resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        try { d.mutate().setColorFilter(colorInt, PorterDuff.Mode.SRC_IN) } catch (_: Exception) {}
        d.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private fun buildModelMarkerBitmap(thumb: Bitmap, ctx: Context): BitmapDescriptor {
        val density = ctx.resources.displayMetrics.density
        val markerPx = (56 * density).toInt()
        val borderPx = (2  * density)
        val radiusPx = (6  * density)
        val out = Bitmap.createBitmap(markerPx, markerPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        canvas.drawRoundRect(RectF(0f, 0f, markerPx.toFloat(), markerPx.toFloat()), radiusPx, radiusPx, bgPaint)
        val dst = RectF(borderPx, borderPx, markerPx - borderPx, markerPx - borderPx)
        canvas.drawBitmap(thumb, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x55000000
            strokeWidth = borderPx
        }
        canvas.drawRoundRect(RectF(0f, 0f, markerPx.toFloat(), markerPx.toFloat()), radiusPx, radiusPx, strokePaint)
        thumb.recycle()
        return BitmapDescriptorFactory.fromBitmap(out)
    }

    // ── Distance / bearing from live GNSS fix ─────────────────────────────────

    private fun haversineDistanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val phi1 = Math.toRadians(lat1); val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1); val dL = Math.toRadians(lon2 - lon1)
        val sinDPhi = Math.sin(dPhi / 2); val sinDL = Math.sin(dL / 2)
        val a = sinDPhi * sinDPhi + Math.cos(phi1) * Math.cos(phi2) * sinDL * sinDL
        return r * 2 * Math.asin(Math.sqrt(a))
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1); val phi2 = Math.toRadians(lat2)
        val dL = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dL) * Math.cos(phi2)
        val x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dL)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    private fun cardinalDir(deg: Double) = CoordinateDetailFormatter.cardinalDir(deg)

    private fun fmtDistance(m: Double) = CoordinateDetailFormatter.fmtDistance(m)

    private fun startDistanceBearing(c: Coordinate) {
        val switchboard = try {
            EntryPointAccessors.fromApplication(
                requireContext().applicationContext,
                SurveyingAppEntryPoint::class.java
            ).fixSwitchboard()
        } catch (_: Exception) { return }

        distanceJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                switchboard.fixes.collectLatest { fix ->
                    val dist = haversineDistanceM(c.latitude, c.longitude, fix.latDeg, fix.lonDeg)
                    val bearing = bearingDeg(c.latitude, c.longitude, fix.latDeg, fix.lonDeg)
                    rowDistance?.visibility = View.VISIBLE
                    textDistance?.text = "${fmtDistance(dist)} ${cardinalDir(bearing)}"
                }
            }
        }
    }

    // ── Edit dialog ────────────────────────────────────────────────────────────

    fun launchEditDialog() {
        val current = lastCoordinate ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val models = try {
                modelRepository.getAllModels().first()
            } catch (_: Exception) { emptyList() }
            EditCoordinateDialogFragment(current, models) { updated ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.updateCoordinate(updated)
                    lastCoordinate = updated
                    bindCoordinate(updated)
                }
            }.show(parentFragmentManager, "edit_coordinate_dialog")
        }
    }
}
