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
import app.surrealar.util.DiagnosticsLogger
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detail view for a single saved coordinate: formatted position/quality/model info with edit actions.
 * Presentation only; formatting must not mutate the stored survey position.
 */
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
    private var badgeRtk: com.google.android.material.chip.Chip? = null
    private var badgeAccuracy: com.google.android.material.chip.Chip? = null
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
    private var cardProjectionRows: LinearLayout? = null
    private var cardGnssRows: LinearLayout? = null
    private var gnssTiles: LinearLayout? = null
    private var locationTiles: LinearLayout? = null
    /** Dynamic body of the Location card (Reference system / Projected coordinates / Altitude details). */
    private var locationSections: LinearLayout? = null
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
    private var badgeSource: com.google.android.material.chip.Chip? = null
    private var badgeExtra: com.google.android.material.chip.Chip? = null

    // Location card copy actions (the projected/CRS rows are now built dynamically into locationSections)
    private var btnCopyLatLng: View? = null
    private var btnCopyUtm: View? = null
    /** Resolved (zone, easting, northing) for the current coordinate — stored values or a UTM
     *  derivation from lat/lon. Non-null only when all three are available, gating Copy UTM. */
    private var copyableUtm: Triple<String, Double, Double>? = null

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
        cardProjectionRows= v.findViewById(R.id.card_projection_rows)
        cardGnssRows      = v.findViewById(R.id.card_gnss_rows)
        gnssTiles         = v.findViewById(R.id.gnss_tiles)
        locationTiles     = v.findViewById(R.id.location_tiles)
        locationSections  = v.findViewById(R.id.location_sections)
        cardCaptureRows   = v.findViewById(R.id.card_capture_rows)
        cardAveragingRows = v.findViewById(R.id.card_averaging_rows)
        cardMotionRows    = v.findViewById(R.id.card_motion_rows)

        badgeSource            = v.findViewById(R.id.badge_source)
        badgeExtra             = v.findViewById(R.id.badge_extra)
        btnCopyLatLng          = v.findViewById(R.id.btn_copy_latlng)
        btnCopyUtm             = v.findViewById(R.id.btn_copy_utm)
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
            // Copies the resolved UTM (stored or derived) shown in the Projected coordinates section.
            copyableUtm?.let { (zone, e, n) ->
                val text = "$zone ${String.format(Locale.US, "%.3f", e)} ${String.format(Locale.US, "%.3f", n)}"
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("UTM", text))
                Toast.makeText(requireContext(), "UTM coordinates copied", Toast.LENGTH_SHORT).show()
            }
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
        if (container.childCount > 0) container.addView(makeDivider())
        val row = inflater.inflate(R.layout.item_coord_detail_row, container, false)
        row.findViewById<TextView>(R.id.row_label).text = label
        row.findViewById<TextView>(R.id.row_value).text = value
        container.addView(row)
    }

    /** A single label/value detail line. */
    private data class DetailRow(val label: String, val value: String)

    /** True on sw600dp+ tablets, where opt-in cards lay their rows out in two columns. */
    private val twoColumnRows: Boolean
        get() = resources.configuration.smallestScreenWidthDp >= 600

    /**
     * Adds a stacked label-over-value cell (full width) — the same look as the two-column tablet
     * cells, used by the Location card so its fields match the Capture Source card.
     */
    private fun addStackedDetailRow(container: LinearLayout?, label: String, value: String) {
        container ?: return
        val cell = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_coord_detail_cell, container, false)
        // The cell layout is sized for the two-column grid (0dp/weight 1); make it full width here.
        cell.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        cell.findViewById<TextView>(R.id.cell_label).text = label
        cell.findViewById<TextView>(R.id.cell_value).text = value
        container.addView(cell)
    }

    /** A thin, low-contrast divider used between detail rows. */
    private fun makeDivider(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            .also { it.setMargins(20.dp, 0, 20.dp, 0) }
        setBackgroundColor(
            ContextCompat.getColor(requireContext(), com.google.android.material.R.color.material_on_surface_stroke)
        )
        alpha = 0.12f
    }

    /**
     * Renders [rows] into [container]: one row per line on phones, or two cells per line on sw600dp+
     * tablets. Phone output is identical to repeated [addDetailRow] calls. Order is preserved; an odd
     * final row keeps the left column and leaves the right half empty.
     */
    private fun renderDetailRows(container: LinearLayout?, rows: List<DetailRow>) {
        container ?: return
        if (!twoColumnRows) {
            rows.forEach { addDetailRow(container, it.label, it.value) }
            return
        }
        val inflater = LayoutInflater.from(requireContext())
        rows.chunked(2).forEachIndexed { idx, pair ->
            if (idx > 0) container.addView(makeDivider())
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            pair.forEach { dr ->
                val cell = inflater.inflate(R.layout.item_coord_detail_cell, rowLayout, false)
                cell.findViewById<TextView>(R.id.cell_label).text = dr.label
                cell.findViewById<TextView>(R.id.cell_value).text = dr.value
                rowLayout.addView(cell)
            }
            if (pair.size == 1) {
                rowLayout.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            container.addView(rowLayout)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ── State: empty ───────────────────────────────────────────────────────────

    private fun showEmpty() {
        textName?.text = "—"
        textEmpty?.visibility = View.VISIBLE
        rowBadges?.visibility = View.GONE
        listOf(cardSummary, cardModel, cardLocation, cardProjection, cardGnss, cardCapture,
            cardAveraging, cardMotion, cardMap).forEach { it?.visibility = View.GONE }
    }

    // ── State: bound ───────────────────────────────────────────────────────────

    private fun bindCoordinate(c: Coordinate) {
        lastCoordinate = c
        textEmpty?.visibility = View.GONE
        textName?.text = c.name.ifBlank { "—" }

        // Clear all dynamic containers before re-populating
        listOf(cardSummaryRows, locationSections, cardProjectionRows, cardGnssRows,
            cardCaptureRows, cardAveragingRows, cardMotionRows)
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
    }

    private fun captureMethodLabel(m: String?): String? = CoordinateDetailFormatter.captureMethodLabel(m)

    /** Captured timestamp (to the second), e.g. "06/27/2026 9:10:47 pm". */
    private fun capturedDateTime(ms: Long): String =
        SimpleDateFormat("MM/dd/yyyy h:mm:ss a", Locale.US).format(Date(ms)).lowercase(Locale.US)

    /** Date + time for audit rows (Created / Last updated), to the minute. */
    private fun formatDateTime(ms: Long): String =
        SimpleDateFormat("MM/dd/yyyy h:mm a", Locale.US).format(Date(ms)).lowercase(Locale.US)

    /** Adds a small secondary heading (e.g. "Record history") into a dynamic card-rows container. */
    private fun addCardSubheading(container: LinearLayout?, label: String) {
        container ?: return
        val tv = TextView(requireContext()).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            val tv2 = android.util.TypedValue()
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv2, true)
            setTextColor(tv2.data)
            setPadding(20.dp, 12.dp, 20.dp, 2.dp)
        }
        container.addView(tv)
    }

    private fun bindSummaryCard(c: Coordinate) {
        // Note shown as flowing text; point code/type as compact rows
        val note = c.note?.takeIf { it.isNotBlank() }
        textSummaryNote?.visibility = if (note != null) View.VISIBLE else View.GONE
        textSummaryNote?.text = note ?: ""

        cardSummary?.visibility = if (note != null) View.VISIBLE else View.GONE
    }

    /**
     * Returns the single dynamic body for the Location card.
     *
     * Some intermediate layout attempts left a static "Geographic position" block in the
     * Location card outside of [R.id.location_sections]. That caused duplicate geographic rows when
     * this fragment also rendered the new projected-position rows. Normalize the card body here by
     * keeping only the Lat/Lng/Alt tile row, one dynamic [location_sections] container, and the copy
     * button row. Anything between the tile row and copy row that is not the dynamic container is
     * removed before binding. This makes the Kotlin drop-in safe even when XML variants are stale.
     */
    private fun ensureLocationSectionsContainer(): LinearLayout? {
        val cardBody = (cardLocation as? ViewGroup)?.getChildAt(0) as? LinearLayout ?: return locationSections
        val tileRow = locationTiles
        val copyRow = btnCopyLatLng?.parent as? View

        val tileIndex = tileRow?.let { cardBody.indexOfChild(it) }?.takeIf { it >= 0 } ?: 1
        val copyIndexBeforeCleanup = copyRow?.let { cardBody.indexOfChild(it) }?.takeIf { it >= 0 } ?: cardBody.childCount

        var target = locationSections?.takeIf { it.parent === cardBody }

        // Remove stale/static location detail views between the primary tiles and the copy row.
        // Keep the existing location_sections container if it is already present as a direct child.
        for (i in (copyIndexBeforeCleanup - 1) downTo (tileIndex + 1)) {
            val child = cardBody.getChildAt(i)
            if (child === target) continue
            cardBody.removeViewAt(i)
        }

        if (target == null) {
            target = LinearLayout(requireContext()).apply {
                id = R.id.location_sections
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val insertIndex = ((tileRow?.let { cardBody.indexOfChild(it) } ?: 1) + 1)
                .coerceIn(0, cardBody.childCount)
            cardBody.addView(target, insertIndex)
        } else {
            val desiredIndex = ((tileRow?.let { cardBody.indexOfChild(it) } ?: 1) + 1)
                .coerceIn(0, cardBody.childCount - 1)
            val currentIndex = cardBody.indexOfChild(target)
            if (currentIndex >= 0 && currentIndex != desiredIndex) {
                cardBody.removeView(target)
                cardBody.addView(target, desiredIndex.coerceIn(0, cardBody.childCount))
            }
        }

        target.visibility = View.VISIBLE
        locationSections = target
        return target
    }

    private fun bindLocationCard(c: Coordinate) {
        // Latitude / Longitude / Altitude shown as scannable value tiles at the top (unchanged).
        renderLocationTiles(c)

        // Be defensive: earlier layout iterations moved this container into an include. If the
        // include is missing/stale on a tester build, create a fresh container before the copy-row
        // instead of silently rendering only the old geographic text.
        val container = ensureLocationSectionsContainer()
        container?.removeAllViews()
        container?.visibility = View.VISIBLE

        // Resolve the projected position: prefer stored fields, fall back to deriving UTM from
        // lat/lon with the app's existing UtmConverter (display fallback — no EPSG is invented).
        // Per-field fallback so partially-stored data still fills in cleanly.
        val derived = if (c.latitude.isFinite() && c.longitude.isFinite() && c.latitude in -80.0..84.0)
            runCatching { UtmConverter.latLonToUtm(c.latitude, c.longitude) }.getOrNull() else null
        val utmZone  = c.utmZone?.takeIf { it.isNotBlank() } ?: derived?.utmZone
        val easting  = c.easting  ?: derived?.easting
        val northing = c.northing ?: derived?.northing
        val naText = "Not available"

        // Keep the visible Location card focused on values users act on. Latitude/longitude are
        // already WGS84 geographic coordinates in this app, so the repeated "Geographic position"
        // row adds noise here. Preserve CRS details in export/diagnostics/advanced views, but make
        // the main card show the projected position compactly as plain label/value fields.
        if (utmZone != null || easting != null || northing != null) {
            renderPlainFieldRow(container, listOf(
                DetailRow("Projected position", utmZone?.let { "UTM Zone $it" } ?: naText),
                DetailRow("Easting", easting?.let { String.format(Locale.US, "%.3f m", it) } ?: naText),
                DetailRow("Northing", northing?.let { String.format(Locale.US, "%.3f m", it) } ?: naText),
            ))
        }

        // Copy UTM is enabled only when a full, copyable UTM triple is available.
        copyableUtm = if (utmZone != null && easting != null && northing != null)
            Triple(utmZone, easting, northing) else null
        btnCopyUtm?.visibility = if (copyableUtm != null) View.VISIBLE else View.GONE

        // Altitude details are supporting values. Keep them compact and only show them when present.
        val altRows = mutableListOf<DetailRow>()
        c.altitudeMsl?.let { altRows += DetailRow("Altitude (MSL)", fmtM2(it)) }
        c.geoidSeparationM?.let { altRows += DetailRow("Geoid separation", fmtM3(it)) }
        // Antenna/pole offset applied at capture — shown so the user can see what it was set to.
        c.antennaHeightM?.takeIf { it != 0.0 }?.let { altRows += DetailRow("Antenna height", fmtM2(it)) }
        if (altRows.isNotEmpty()) {
            renderStackedColumns(container, altRows)
        }

        cardLocation?.visibility = View.VISIBLE
    }

    /**
     * Renders [rows] as label-above-value fields: stacked full-width on phones, two stacked cells
     * per line on sw600dp+ tablets (reusing [R.layout.item_coord_detail_cell]). Matches the
     * formatting used by the other coordinate-detail cards.
     */
    private fun renderStackedColumns(container: LinearLayout?, rows: List<DetailRow>) {
        container ?: return
        if (!twoColumnRows) {
            rows.forEach { addStackedDetailRow(container, it.label, it.value) }
            return
        }
        val inflater = LayoutInflater.from(requireContext())
        rows.chunked(2).forEach { pair ->
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            pair.forEach { dr ->
                val cell = inflater.inflate(R.layout.item_coord_detail_cell, rowLayout, false)
                cell.findViewById<TextView>(R.id.cell_label).text = dr.label
                cell.findViewById<TextView>(R.id.cell_value).text = dr.value
                rowLayout.addView(cell)
            }
            if (pair.size == 1) {
                rowLayout.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            container.addView(rowLayout)
        }
    }

    /**
     * Renders a compact row of plain label/value fields. On tablets this supports three equal
     * columns, which fits Projected position + Easting + Northing without making Easting/Northing
     * look like primary Lat/Lng/Alt tiles. Phones stack the fields using the same label-over-value
     * convention as the rest of the detail page.
     */
    private fun renderPlainFieldRow(container: LinearLayout?, rows: List<DetailRow>) {
        container ?: return
        if (!twoColumnRows) {
            rows.forEach { addStackedDetailRow(container, it.label, it.value) }
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        val rowLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 4.dp }
        }
        rows.forEach { dr ->
            val cell = inflater.inflate(R.layout.item_coord_detail_cell, rowLayout, false)
            cell.findViewById<TextView>(R.id.cell_label).text = dr.label
            cell.findViewById<TextView>(R.id.cell_value).text = dr.value
            rowLayout.addView(cell)
        }
        container.addView(rowLayout)
    }

    private fun bindProjectionCard(c: Coordinate) {
        // CRS/projection content is now embedded in the Location card's collapsible section.
        cardProjection?.visibility = View.GONE
    }

    private fun providerLabel(p: String?): String? = CoordinateDetailFormatter.providerLabel(p)

    private fun bindGnssCard(c: Coordinate) {
        // Scannable quality tiles at the top (Fix / accuracy / satellites). The detail rows below show
        // only supporting values NOT already on a tile, so nothing is duplicated.
        renderGnssTiles(c)

        // Plain-language quality summary (adds the grade context the tiles don't show).
        var hasContent = (gnssTiles?.childCount ?: 0) > 0
        CoordinateDetailFormatter.surveyQualitySummaryText(c.rtkStatus, c.horizontalAccuracyM, c.hdop)
            ?.let { addStackedDetailRow(cardGnssRows, "Quality", it); hasContent = true }

        // Corrections (left column on tablet) — none of these appear on a tile.
        val corrections = mutableListOf<DetailRow>()
        CoordinateDetailFormatter.correctionFreshnessText(c.correctionAgeS)
            ?.let { corrections += DetailRow("Correction age", it) }
        c.correctionStationId?.takeIf { it.isNotBlank() }?.let { corrections += DetailRow("Station ID", it) }
        c.correctionSource?.takeIf { it.isNotBlank() }?.let { corrections += DetailRow("Correction source", it) }

        // Precision details (right column on tablet) — DOP/multipath are not on tiles.
        val precision = mutableListOf<DetailRow>()
        c.hdop?.let { precision += DetailRow("HDOP", fmtDop(it)) }
        c.vDop?.let { precision += DetailRow("VDOP", fmtDop(it)) }
        c.pDop?.let { precision += DetailRow("PDOP", fmtDop(it)) }
        c.multipathIndex?.let { precision += DetailRow("Multipath index", fmtDop(it)) }

        renderRowGroups(cardGnssRows, listOf(
            DetailGroup("Corrections", corrections),
            DetailGroup("Precision details", precision),
        ))
        if (corrections.isNotEmpty() || precision.isNotEmpty()) hasContent = true

        cardGnss?.visibility = if (hasContent) View.VISIBLE else View.GONE
    }

    /** A titled group of detail rows. */
    private data class DetailGroup(val heading: String, val rows: List<DetailRow>)

    /**
     * Renders titled [groups] (each = a "Record history"-style heading + its rows). On phone the
     * groups stack; on sw600dp+ exactly two non-empty groups sit side by side. Empty groups are skipped.
     */
    private fun renderRowGroups(container: LinearLayout?, groups: List<DetailGroup>) {
        container ?: return
        val visible = groups.filter { it.rows.isNotEmpty() }
        if (visible.isEmpty()) return
        if (twoColumnRows && visible.size == 2) {
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            visible.forEachIndexed { i, g ->
                val col = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .also { if (i > 0) it.marginStart = 8.dp }
                }
                addCardSubheading(col, g.heading)
                g.rows.forEach { addStackedDetailRow(col, it.label, it.value) }
                rowLayout.addView(col)
            }
            container.addView(rowLayout)
        } else {
            visible.forEach { g ->
                addCardSubheading(container, g.heading)
                g.rows.forEach { addStackedDetailRow(container, it.label, it.value) }
            }
        }
    }

    /** Latitude / Longitude / Altitude tiles at the top of the Location card. */
    private fun renderLocationTiles(c: Coordinate) {
        val container = locationTiles ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val tiles = listOf(
            DetailRow("Latitude", "${fmt6(c.latitude)}°"),
            DetailRow("Longitude", "${fmt6(c.longitude)}°"),
            DetailRow("Altitude", fmtM2(c.altitude)),
        )
        tiles.forEach { t ->
            val tile = inflater.inflate(R.layout.item_gnss_stat_tile, container, false)
            tile.findViewById<TextView>(R.id.tile_label).text = t.label
            tile.findViewById<TextView>(R.id.tile_value).text = t.value
            container.addView(tile)
        }
        container.visibility = View.VISIBLE
    }

    /** Populates the scannable Survey/GNSS stat tiles; a tile appears only when its value is present. */
    private fun renderGnssTiles(c: Coordinate) {
        val container = gnssTiles ?: return
        container.removeAllViews()
        val tiles = mutableListOf<DetailRow>()
        CoordinateDetailFormatter.fixTileValue(c.rtkStatus)?.let { tiles += DetailRow("Fix", it) }
        if (showAccuracyIndicators) {
            CoordinateDetailFormatter.accuracyTileText(c.horizontalAccuracyM)?.let { tiles += DetailRow("Horizontal accuracy", it) }
            CoordinateDetailFormatter.accuracyTileText(c.verticalAccuracyM)?.let { tiles += DetailRow("Vertical accuracy", it) }
        }
        CoordinateDetailFormatter.satellitesTileText(c.satsUsed, c.satsVisible)?.let { tiles += DetailRow("Satellites", it) }
        if (tiles.isEmpty()) { container.visibility = View.GONE; return }
        val inflater = LayoutInflater.from(requireContext())
        tiles.forEach { t ->
            val tile = inflater.inflate(R.layout.item_gnss_stat_tile, container, false)
            tile.findViewById<TextView>(R.id.tile_label).text = t.label
            tile.findViewById<TextView>(R.id.tile_value).text = t.value
            container.addView(tile)
        }
        container.visibility = View.VISIBLE
    }

    private fun bindCaptureCard(c: Coordinate) {
        // Source device and capture method first (most user-relevant)
        val rows = mutableListOf<DetailRow>()
        c.sourceDevice?.takeIf { it.isNotBlank() }?.let { rows += DetailRow("Source device", it) }
        captureMethodLabel(c.captureMethod)?.let { rows += DetailRow("Capture method", it) }
        // Provider only when informative (not "other")
        providerLabel(c.provider)?.let { rows += DetailRow("Provider", it) }
        // Single "Captured" row replaces the old duplicate Date + Time rows.
        if (c.timestamp > 0L) rows += DetailRow("Captured", capturedDateTime(c.timestamp))
        c.timestampSource?.takeIf { it.isNotBlank() }?.let { rows += DetailRow("Time source", it) }
        c.appVersion?.takeIf { it.isNotBlank() }?.let { rows += DetailRow("App version", it) }
        renderDetailRows(cardCaptureRows, rows)

        // Record history — shown only when Created/Last-updated add information beyond Captured.
        val history = CoordinateDetailFormatter.recordHistoryVisibility(c.timestamp, c.createdAt, c.updatedAt)
        if (history.anyShown) {
            addCardSubheading(cardCaptureRows, "Record history")
            val hist = mutableListOf<DetailRow>()
            if (history.showCreated) hist += DetailRow("Created", formatDateTime(c.createdAt))
            if (history.showUpdated) hist += DetailRow("Last updated", formatDateTime(c.updatedAt))
            renderDetailRows(cardCaptureRows, hist)
        }

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
        val rows = mutableListOf<DetailRow>()
        c.averagedSamples?.takeIf { it > 0 }?.let { rows += DetailRow("Samples averaged", it.toString()) }
        c.averageDurationMs?.let {
            rows += DetailRow("Averaging duration", String.format(Locale.US, "%.1f s", it / 1000.0))
        }
        // Calculated capture rate (display-only; only when both inputs are available).
        CoordinateDetailFormatter.captureRateText(c.averagedSamples, c.averageDurationMs)
            ?.let { rows += DetailRow("Capture rate", it) }
        c.stdLatM?.let { rows += DetailRow("Std dev (lat)", fmtM3(it)) }
        c.stdLonM?.let { rows += DetailRow("Std dev (lon)", fmtM3(it)) }
        c.stdAltM?.let { rows += DetailRow("Std dev (alt)", fmtM3(it)) }
        renderDetailRows(cardAveragingRows, rows)
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

        // Placement note: source, AR visibility, and any per-coordinate placement overrides.
        val placementSource = when (lastCoordinate?.captureMethod?.lowercase(Locale.US)) {
            "model_embedded" -> "Placement: Model embedded location"
            else             -> "Placement: Uses saved coordinate"
        }
        val lines = mutableListOf(placementSource)
        lastCoordinate?.let { c ->
            lines += if (c.renderEnabled) "AR: Visible" else "AR: Hidden"
            CoordinateDetailFormatter.modelPlacementSummaryText(
                c.modelScale, c.modelYawDeg, c.modelPitchDeg, c.modelRollDeg,
                c.modelVerticalOffsetM, c.modelOriginOffsetXM, c.modelOriginOffsetYM, c.modelOriginOffsetZM
            )?.let { lines += it }
        }
        textModelPlacement?.text = lines.joinToString("\n")
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
                } catch (e: Exception) {
                    DiagnosticsLogger.w("MODEL", "lookup failed for modelId=$modelId: ${e.javaClass.simpleName} ${e.message}")
                    null
                }
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
            DiagnosticsLogger.i("MODEL", "linked modelId=$modelId to coordinateId=${updated.id} " +
                "name=\"${updated.name}\" useModelLocation=$useModelLocation")
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
        val priorModelId = coord.modelId
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.updateCoordinate(updated)
            lastCoordinate = updated
            bindCoordinate(updated)
            DiagnosticsLogger.i("MODEL", "unlinked modelId=$priorModelId from coordinateId=${updated.id}")
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
    private fun applyBadge(chip: com.google.android.material.chip.Chip?, state: BadgeUi?) {
        chip ?: return
        if (state == null) { chip.visibility = View.GONE; return }
        chip.text = state.text
        chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(state.colorArgb)
        // Only the fix/accuracy badges carry an accessibility description; leave the others' as-is
        // (matches the previous per-badge behavior — source/extra never set contentDescription).
        state.contentDescription?.let { chip.contentDescription = it }
        chip.visibility = View.VISIBLE
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
