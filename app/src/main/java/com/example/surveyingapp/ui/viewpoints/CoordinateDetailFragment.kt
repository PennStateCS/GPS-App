package com.example.surveyingapp.ui.viewpoints

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
import com.example.surveyingapp.R
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.ModelRepositoryImpl
import com.example.surveyingapp.domain.model.Coordinate
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

class CoordinateDetailFragment : Fragment() {

    companion object {
        private const val ARG_ID = "arg_id"
        fun newInstance(id: String): CoordinateDetailFragment = CoordinateDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }

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
    private var cardLocation: View? = null
    private var cardProjection: View? = null
    private var cardGnss: View? = null
    private var cardCapture: View? = null
    private var cardAveraging: View? = null
    private var cardNotes: View? = null
    private var cardMap: View? = null

    // Card body containers (rows inflated into these)
    private var cardLocationRows: LinearLayout? = null
    private var cardProjectionRows: LinearLayout? = null
    private var cardGnssRows: LinearLayout? = null
    private var cardCaptureRows: LinearLayout? = null
    private var cardAveragingRows: LinearLayout? = null
    private var cardNotesRows: LinearLayout? = null

    // Map
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var lastCoordinate: Coordinate? = null

    private var showAccuracyIndicators: Boolean = true

    // ── Format helpers ─────────────────────────────────────────────────────────

    private fun fmt6(v: Double) = String.format(Locale.US, "%.6f", v)
    private fun fmtM2(v: Double) = String.format(Locale.US, "%.2f m", v)
    private fun fmtM3(v: Double) = String.format(Locale.US, "%.3f m", v)
    private fun fmtDop(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun rtkLabel(s: String?) = when (s?.uppercase(Locale.US)) {
        "FIX"    -> "Fixed (RTK)"
        "FLOAT"  -> "Float (RTK)"
        "DGPS"   -> "DGPS"
        "SINGLE" -> "Single"
        else     -> s ?: "--"
    }

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

        cardLocation      = v.findViewById(R.id.card_location)
        cardProjection    = v.findViewById(R.id.card_projection)
        cardGnss          = v.findViewById(R.id.card_gnss)
        cardCapture       = v.findViewById(R.id.card_capture)
        cardAveraging     = v.findViewById(R.id.card_averaging)
        cardNotes         = v.findViewById(R.id.card_notes)
        cardMap           = v.findViewById(R.id.card_map)

        cardLocationRows  = v.findViewById(R.id.card_location_rows)
        cardProjectionRows= v.findViewById(R.id.card_projection_rows)
        cardGnssRows      = v.findViewById(R.id.card_gnss_rows)
        cardCaptureRows   = v.findViewById(R.id.card_capture_rows)
        cardAveragingRows = v.findViewById(R.id.card_averaging_rows)
        cardNotesRows     = v.findViewById(R.id.card_notes_rows)

        mapView = v.findViewById(R.id.mapView)
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync { map ->
            googleMap = map
            map.uiSettings.apply {
                isMapToolbarEnabled = false
                isZoomControlsEnabled = false
            }
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
        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory(requireActivity().application)
        ).get(CoordinatesViewModel::class.java)

        view.findViewById<View>(R.id.btn_edit_coordinate)?.setOnClickListener { launchEditDialog() }
        currentId = arguments?.getString(ARG_ID)

        // Pull the current collapse state from the parent two-pane host (if any) so the
        // header's show-list button reflects it as soon as the view is created.
        (parentFragment as? CoordinatesFragment)?.onDetailViewReady(this) ?: applyShowListControl()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SurveyingApp.settingsRepo.coordinateDisplaySettings.collect { settings ->
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
     * Inflates [item_coord_detail_row] into [container] with the given label/value.
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
        textName?.text = "—"
        textEmpty?.visibility = View.VISIBLE
        rowBadges?.visibility = View.GONE
        listOf(cardLocation, cardProjection, cardGnss, cardCapture,
               cardAveraging, cardNotes, cardMap).forEach { it?.visibility = View.GONE }
    }

    // ── State: bound ───────────────────────────────────────────────────────────

    private fun bindCoordinate(c: Coordinate) {
        lastCoordinate = c
        textEmpty?.visibility = View.GONE
        textName?.text = c.name.ifBlank { "—" }

        // Clear all dynamic containers before re-populating
        listOf(cardLocationRows, cardProjectionRows, cardGnssRows,
               cardCaptureRows, cardAveragingRows, cardNotesRows)
            .forEach { it?.removeAllViews() }

        bindLocationCard(c)
        bindProjectionCard(c)
        bindGnssCard(c)
        bindCaptureCard(c)
        bindAveragingCard(c)
        bindNotesCard(c)
        bindMapCard(c)
        bindBadges(c)
    }

    private fun bindLocationCard(c: Coordinate) {
        addDetailRow(cardLocationRows, "Latitude",  fmt6(c.latitude)  + "°")
        addDetailRow(cardLocationRows, "Longitude", fmt6(c.longitude) + "°")
        c.altitude.let  { addDetailRow(cardLocationRows, "Altitude (ellipsoidal)", fmtM2(it)) }
        c.altitudeMsl?.let { addDetailRow(cardLocationRows, "Altitude (MSL)", fmtM2(it)) }
        c.geoidSeparationM?.let { addDetailRow(cardLocationRows, "Geoid separation", fmtM3(it)) }
        cardLocation?.visibility = View.VISIBLE
    }

    private fun bindProjectionCard(c: Coordinate) {
        val hasProj = c.easting != null || c.northing != null || c.utmZone != null || c.crsEpsg != null
        if (!hasProj) { cardProjection?.visibility = View.GONE; return }
        c.easting?.let  { addDetailRow(cardProjectionRows, "Easting",  String.format(Locale.US, "%.3f m", it)) }
        c.northing?.let { addDetailRow(cardProjectionRows, "Northing", String.format(Locale.US, "%.3f m", it)) }
        c.utmZone?.takeIf { it.isNotBlank() }?.let { addDetailRow(cardProjectionRows, "UTM zone", it) }
        c.crsEpsg?.let { addDetailRow(cardProjectionRows, "CRS (EPSG)", it.toString()) }
        cardProjection?.visibility = View.VISIBLE
    }

    private fun bindGnssCard(c: Coordinate) {
        c.provider.takeIf { it.isNotBlank() }?.let {
            addDetailRow(cardGnssRows, "Provider", it)
        }
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
        val satsLine = buildString {
            c.satsUsed?.let    { append("$it used") }
            c.satsVisible?.let { if (isNotEmpty()) append(" / "); append("$it visible") }
        }
        if (satsLine.isNotBlank()) addDetailRow(cardGnssRows, "Satellites", satsLine)
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
        c.speedMps?.let  { addDetailRow(cardGnssRows, "Speed",   String.format(Locale.US, "%.2f m/s", it)) }
        c.courseDeg?.let { addDetailRow(cardGnssRows, "Course",  String.format(Locale.US, "%.1f°", it)) }
        if (cardGnssRows?.childCount ?: 0 > 0) cardGnss?.visibility = View.VISIBLE
        else cardGnss?.visibility = View.GONE
    }

    private fun bindCaptureCard(c: Coordinate) {
        val dateObj = Date(c.timestamp)
        addDetailRow(cardCaptureRows, "Date", SimpleDateFormat("MM/dd/yyyy", Locale.US).format(dateObj))
        addDetailRow(cardCaptureRows, "Time", SimpleDateFormat("h:mm:ss a", Locale.US).format(dateObj).lowercase(Locale.US))
        c.timestampSource?.takeIf { it.isNotBlank() }?.let { addDetailRow(cardCaptureRows, "Time source", it) }
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

    private fun bindNotesCard(c: Coordinate) {
        val hasData = !c.note.isNullOrBlank() || !c.sourceDevice.isNullOrBlank() || !c.appVersion.isNullOrBlank()
        if (!hasData) { cardNotes?.visibility = View.GONE; return }
        c.note?.takeIf { it.isNotBlank() }?.let { addDetailRow(cardNotesRows, "Note", it) }
        c.sourceDevice?.takeIf { it.isNotBlank() }?.let { addDetailRow(cardNotesRows, "Device", it) }
        c.appVersion?.takeIf { it.isNotBlank() }?.let { addDetailRow(cardNotesRows, "App version", it) }
        cardNotes?.visibility = View.VISIBLE
    }

    private fun bindMapCard(c: Coordinate) {
        cardMap?.visibility = View.VISIBLE
        updateMapMarker(c)
    }

    // ── Badges ─────────────────────────────────────────────────────────────────

    private fun bindBadges(c: Coordinate) {
        applyRtkBadge(c)
        if (showAccuracyIndicators) applyAccuracyBadge(c) else badgeAccuracy?.visibility = View.GONE
        val anyVisible = (badgeRtk?.visibility == View.VISIBLE) || (badgeAccuracy?.visibility == View.VISIBLE)
        rowBadges?.visibility = if (anyVisible) View.VISIBLE else View.GONE
    }

    private fun applyRtkBadge(c: Coordinate) {
        val tv = badgeRtk ?: return
        val status = c.rtkStatus
        if (status.isNullOrBlank()) { tv.visibility = View.GONE; return }
        tv.text = status
        val color = when (status.uppercase(Locale.US)) {
            "FIX"    -> 0xFF2E7D32.toInt()
            "FLOAT"  -> 0xFFEF6C00.toInt()
            "DGPS"   -> 0xFF1976D2.toInt()
            "SINGLE" -> 0xFF607D8B.toInt()
            else     -> 0xFFC62828.toInt()
        }
        tv.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        tv.visibility = View.VISIBLE
    }

    private fun applyAccuracyBadge(c: Coordinate) {
        val tv = badgeAccuracy ?: return
        val hAcc = c.horizontalAccuracyM ?: c.hdop?.let { it * 0.6 }
        if (hAcc == null) { tv.visibility = View.GONE; return }
        val textVal = if (hAcc < 10) String.format(Locale.US, "%.2fm", hAcc)
                      else           String.format(Locale.US, "%.0fm", hAcc)
        val (color, label) = when {
            hAcc <= 0.05 -> 0xFF2E7D32.toInt() to "HP"
            hAcc <= 0.10 -> 0xFF00897B.toInt() to "HQ"
            hAcc <= 0.30 -> 0xFFF9A825.toInt() to "MD"
            hAcc <= 1.0  -> 0xFFEF6C00.toInt() to "LO"
            else         -> 0xFFC62828.toInt() to "PO"
        }
        tv.text = "$label $textVal"
        tv.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
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
            val descriptor = buildMarkerDescriptor(c.icon, c.color)
            if (descriptor != null) marker?.setIcon(descriptor)
        }
    }

    private suspend fun buildMarkerDescriptor(iconName: String?, colorInt: Int): BitmapDescriptor? {
        val ctx = context ?: return null
        if (iconName.isNullOrBlank()) return null
        if (iconName.startsWith("model:")) {
            val modelId = iconName.removePrefix("model:")
            return withContext(Dispatchers.IO) {
                try {
                    val db   = AppDatabase.getDatabase(ctx)
                    val repo = ModelRepositoryImpl(db.modelDao())
                    val model = repo.getModelById(modelId) ?: return@withContext null
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
        val resId = ctx.resources.getIdentifier(iconName, "drawable", ctx.packageName)
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
                val db = AppDatabase.getDatabase(requireContext())
                ModelRepositoryImpl(db.modelDao()).getAllModels().first()
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
