package com.example.surveyingapp.ui.rendermap

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.surveyingapp.R
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.ModelRepositoryImpl
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.ui.viewpoints.CoordinatesViewModel
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.animation.doOnEnd
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import android.widget.Button
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.*
import javax.inject.Inject

@AndroidEntryPoint
class RenderMapFragment : Fragment() {
    // Single companion object consolidating TAG and constants
    companion object {
        private const val TAG = "RenderMapFragment"
        private const val MIN_DISTANCE_METERS = 0.5 // Only add trail point if distance > 0.5m
        private const val MIN_HEADING_CHANGE_DEGREES = 5.0 // Or heading change > 5°
        private const val MAX_TRAIL_POINTS = 1000 // Limit trail length for performance
        private const val STAKEOUT_GREEN_THRESHOLD = 0.10 // < 0.10m = green
        private const val STAKEOUT_AMBER_THRESHOLD = 0.25 // < 0.25m = amber
    }

    // Inject FixSwitchboard using Hilt
    @Inject
    lateinit var fixSwitchboard: FixSwitchboard

    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var placeholder: View? = null
    private var lastLatLngs: List<LatLng> = emptyList()
    private var isSatellite = false
    private var dataObserved = false
    private var cameraInitialized = false

    private var leftPanel: View? = null
    private var panelHandle: View? = null
    private var collapseBtn: ImageButton? = null
    private var expandBtn: ImageButton? = null

    private var panelWidthPx: Int = 0
    private var panelCollapsed = false

    private val minPanelDp = 160f
    private val maxPanelDp = 480f

    private var toggleRecycler: RecyclerView? = null
    private lateinit var toggleAdapter: CoordinateToggleAdapter
    private val markerMap = mutableMapOf<String, Marker>()
    private val visibilityMap = mutableMapOf<String, Boolean>() // id -> visible
    private val coordinateMap = mutableMapOf<String, Coordinate>() // id -> coordinate data
    private var toggleSatBtn: FloatingActionButton? = null
    private var gridBtn: FloatingActionButton? = null
    private var showAllBtn: Button? = null
    private var hideAllBtn: Button? = null

    private var showGrid = false
    private val gridLines = mutableListOf<Polyline>()

    private val boundaryLines = mutableListOf<Polyline>()
    private var showBoundaries = false

    private var currentMarker: Marker? = null
    private var lastFixLatLng: LatLng? = null

    // Live tracking features
    private var accuracyCircle: Circle? = null
    private var liveTrail: Polyline? = null
    private val trailPoints = mutableListOf<LatLng>()
    private var lastTrailFix: Fix? = null
    private var gnssStatusChip: TextView? = null

    // Stakeout features
    private var isStakeoutMode = false
    private var stakeoutTarget: LatLng? = null
    private var stakeoutMarker: Marker? = null
    private var stakeoutPanel: View? = null
    private var btnToggleStakeout: Button? = null
    private var btnClearStakeout: Button? = null
    private var txtStakeoutTarget: TextView? = null
    private var txtStakeoutDistance: TextView? = null
    private var txtStakeoutBearing: TextView? = null
    private var currentFix: Fix? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_render_map, container, false)
        mapView = root.findViewById(R.id.mapView)
        placeholder = root.findViewById(R.id.text_render_map)
        gnssStatusChip = root.findViewById(R.id.text_gnss_status)
        toggleRecycler = root.findViewById(R.id.coordinate_toggle_list)
        toggleSatBtn = root.findViewById(R.id.fab_toggle_sat)
        gridBtn = root.findViewById(R.id.fab_toggle_grid)
        showAllBtn = root.findViewById(R.id.btn_show_all)
        hideAllBtn = root.findViewById(R.id.btn_hide_all)
        // measureBtn = root.findViewById(R.id.fab_measure) // Commented out - button doesn't exist in layout yet
        toggleAdapter = CoordinateToggleAdapter { id, checked ->
            visibilityMap[id] = checked
            markerMap[id]?.isVisible = checked
        }
        toggleRecycler?.layoutManager = LinearLayoutManager(requireContext())
        toggleRecycler?.adapter = toggleAdapter
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync { map ->
            googleMap = map
            googleMap?.mapType = if (isSatellite) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL

            // Allow much closer zooming - set maximum zoom level to 22 (very close)
            googleMap?.setMaxZoomPreference(22f)
            googleMap?.setMinZoomPreference(2f)

            // Set up custom info window adapter
            googleMap?.setInfoWindowAdapter(object : GoogleMap.InfoWindowAdapter {
                override fun getInfoWindow(marker: Marker): View? = null // Use default frame

                override fun getInfoContents(marker: Marker): View? {
                    return createInfoWindowView(marker)
                }
            })

            // Set up marker click listener to show info window
            googleMap?.setOnMarkerClickListener { marker ->
                marker.showInfoWindow()
                true // Return true to consume the event
            }

            Log.d("RenderMap", "GoogleMap ready")
            googleMap?.setOnMapLoadedCallback {
                Log.d("RenderMap", "Map loaded callback")
                if (!cameraInitialized && lastLatLngs.isNotEmpty()) updateCamera(lastLatLngs)
            }
            bindData()
            // Start collecting live fixes once map is ready
            startFixCollection()
        }
        toggleSatBtn?.setOnClickListener { toggleSatellite() }
        gridBtn?.setOnClickListener { toggleGrid() }
        showAllBtn?.setOnClickListener { showAllCoordinates() }
        hideAllBtn?.setOnClickListener { hideAllCoordinates() }
        root.findViewById<FloatingActionButton>(R.id.fab_recenter)?.setOnClickListener { recenterMap() }
        root.findViewById<FloatingActionButton>(R.id.fab_zoom_in)?.setOnClickListener { googleMap?.animateCamera(CameraUpdateFactory.zoomIn()) }
        root.findViewById<FloatingActionButton>(R.id.fab_zoom_out)?.setOnClickListener { googleMap?.animateCamera(CameraUpdateFactory.zoomOut()) }
        if (savedInstanceState != null) {
            panelWidthPx = savedInstanceState.getInt("panelWidthPx", 0)
            panelCollapsed = savedInstanceState.getBoolean("panelCollapsed", false)
        }
        return root
    }

    private fun startFixCollection() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    fixSwitchboard.fixes.collect { fix: Fix ->
                        updateLiveTracking(fix)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error collecting fixes: ${e.message}", e)
                }
            }
        }
    }

    private fun updateLiveTracking(fix: Fix) {
        try {
            val currentPos = LatLng(fix.latDeg, fix.lonDeg)
            currentFix = fix
            try { updateCurrentMarker(fix.latDeg, fix.lonDeg, fix.rtkStatus) } catch (e: Exception) { Log.w(TAG, "updateCurrentMarker failed", e) }
            try { updateAccuracyCircle(currentPos, fix.hAccM) } catch (e: Exception) { Log.w(TAG, "updateAccuracyCircle failed", e) }
            try { updateLiveTrail(currentPos, fix) } catch (e: Exception) { Log.w(TAG, "updateLiveTrail failed", e) }
            try { updateStakeoutCalculations(currentPos) } catch (e: Exception) { Log.w(TAG, "updateStakeoutCalculations failed", e) }
            try { updateGnssStatusChip(fix) } catch (e: Exception) { Log.w(TAG, "updateGnssStatusChip failed", e) }
        } catch (e: Exception) {
            Log.e(TAG, "updateLiveTracking error", e)
        }
    }

    /** Updates the GNSS source/status chip overlay on the map. */
    private fun updateGnssStatusChip(fix: Fix) {
        val chip = gnssStatusChip ?: return
        val sourceLabel = when (fix.provider) {
            Provider.INTERNAL                                      -> "Internal GPS"
            Provider.RS2_TCP, Provider.RS2_EXTERNAL, Provider.RS2_BT -> "RS2+"
            Provider.OTHER                                         -> "External"
        }
        val rtkLabel = when (fix.rtkStatus) {
            RtkStatus.FIX            -> "RTK FIX"
            RtkStatus.FLOAT          -> "RTK FLOAT"
            RtkStatus.DGPS           -> "DGPS"
            RtkStatus.SINGLE         -> "SINGLE"
            RtkStatus.DEAD_RECKONING -> "DEAD RECKONING"
            RtkStatus.NONE,
            RtkStatus.INVALID        -> "NO FIX"
        }
        val accLabel = fix.hAccM?.let { " ±${"%.2f".format(it)}m" } ?: ""
        chip.text = "$sourceLabel | $rtkLabel$accLabel"
        chip.setBackgroundColor(when (fix.rtkStatus) {
            RtkStatus.FIX            -> 0xCC1B5E20.toInt()  // dark green
            RtkStatus.FLOAT          -> 0xCCE65100.toInt()  // dark orange
            RtkStatus.DGPS           -> 0xCC0D47A1.toInt()  // dark blue
            RtkStatus.SINGLE         -> 0xCCB71C1C.toInt()  // dark red
            RtkStatus.DEAD_RECKONING -> 0xCC4A148C.toInt()  // dark purple
            RtkStatus.NONE,
            RtkStatus.INVALID        -> 0xCC424242.toInt()  // dark grey
        })
    }

    private fun updateStakeoutCalculations(currentPos: LatLng) {
        val target = stakeoutTarget ?: return
        if (!isStakeoutMode) return
        try {
            val distance = calculateDistance(currentPos, target)
            val bearing = calculateBearing(currentPos, target)
            val distanceText = when {
                distance < 1.0 -> String.format(Locale.getDefault(), "%.2f m", distance)
                distance < 10.0 -> String.format(Locale.getDefault(), "%.1f m", distance)
                else -> String.format(Locale.getDefault(), "%.0f m", distance)
            }
            txtStakeoutDistance?.text = distanceText
            txtStakeoutBearing?.text = String.format(Locale.getDefault(), "%.1f°", bearing)
            val color = when {
                distance < STAKEOUT_GREEN_THRESHOLD -> 0xFF4CAF50.toInt()
                distance < STAKEOUT_AMBER_THRESHOLD -> 0xFFFF9800.toInt()
                else -> 0xFFF44336.toInt()
            }
            txtStakeoutDistance?.setTextColor(color)
            txtStakeoutBearing?.setTextColor(color)
            updateStakeoutMarkerColor(distance)
        } catch (e: Exception) {
            Log.w(TAG, "updateStakeoutCalculations error", e)
        }
    }

    private fun updateStakeoutMarkerColor(distance: Double) {
        val hue = when {
            distance < STAKEOUT_GREEN_THRESHOLD -> BitmapDescriptorFactory.HUE_GREEN
            distance < STAKEOUT_AMBER_THRESHOLD -> BitmapDescriptorFactory.HUE_ORANGE
            else -> BitmapDescriptorFactory.HUE_RED
        }

        stakeoutMarker?.setIcon(BitmapDescriptorFactory.defaultMarker(hue))
    }

    private fun updateCurrentMarker(lat: Double, lon: Double, rtkStatus: RtkStatus = RtkStatus.NONE) {
        val pos = LatLng(lat, lon)
        lastFixLatLng = pos
        val hue = when (rtkStatus) {
            RtkStatus.FIX            -> BitmapDescriptorFactory.HUE_GREEN
            RtkStatus.FLOAT          -> BitmapDescriptorFactory.HUE_YELLOW
            RtkStatus.DGPS           -> BitmapDescriptorFactory.HUE_AZURE
            RtkStatus.SINGLE         -> BitmapDescriptorFactory.HUE_ORANGE
            RtkStatus.DEAD_RECKONING -> BitmapDescriptorFactory.HUE_VIOLET
            RtkStatus.NONE,
            RtkStatus.INVALID        -> BitmapDescriptorFactory.HUE_RED
        }
        if (currentMarker == null) {
            currentMarker = googleMap?.addMarker(
                MarkerOptions()
                    .position(pos)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(hue))
            )
        } else {
            try {
                currentMarker?.position = pos
                currentMarker?.setIcon(BitmapDescriptorFactory.defaultMarker(hue))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update marker position", e)
            }
        }
    }

    private fun updateAccuracyCircle(position: LatLng, hAccM: Double?) {
        val map = googleMap ?: return
        try { accuracyCircle?.remove() } catch (_: Exception) {}
        accuracyCircle = null
        hAccM?.let { accuracy ->
            if (accuracy > 0 && accuracy < 1000) {
                try {
                    accuracyCircle = map.addCircle(
                        CircleOptions()
                            .center(position)
                            .radius(accuracy)
                            .strokeColor(0x880000FF.toInt())
                            .fillColor(0x220000FF.toInt())
                            .strokeWidth(2f)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add accuracy circle", e)
                }
            }
        }
    }

    private fun updateLiveTrail(currentPos: LatLng, currentFix: Fix) {
        val map = googleMap ?: return
        if (!shouldAddTrailPoint(currentPos, currentFix)) return
        try {
            trailPoints.add(currentPos)
            lastTrailFix = currentFix
            if (trailPoints.size > MAX_TRAIL_POINTS) trailPoints.removeAt(0)
            updateTrailPolyline(map)
            Log.d(TAG, "Added trail point. Total points: ${trailPoints.size}")
        } catch (e: Exception) {
            Log.w(TAG, "updateLiveTrail error", e)
        }
    }

    private fun shouldAddTrailPoint(currentPos: LatLng, currentFix: Fix): Boolean {
        val lastFix = lastTrailFix

        // Always add the first point
        if (lastFix == null || trailPoints.isEmpty()) {
            return true
        }

        val lastPos = trailPoints.lastOrNull() ?: return true

        // Calculate distance from last trail point
        val distance = calculateDistance(lastPos, currentPos)

        // Check if distance threshold is met
        if (distance >= MIN_DISTANCE_METERS) {
            return true
        }

        // Check heading change if both fixes have course information
        val currentCourse = currentFix.courseDeg
        val lastCourse = lastFix.courseDeg

        if (currentCourse != null && lastCourse != null) {
            val headingChange = calculateHeadingChange(lastCourse, currentCourse)
            if (headingChange >= MIN_HEADING_CHANGE_DEGREES) {
                return true
            }
        }

        return false
    }

    private fun calculateDistance(pos1: LatLng, pos2: LatLng): Double {
        // Haversine formula for distance calculation
        val R = 6371000.0 // Earth's radius in meters

        val lat1Rad = Math.toRadians(pos1.latitude)
        val lat2Rad = Math.toRadians(pos2.latitude)
        val deltaLatRad = Math.toRadians(pos2.latitude - pos1.latitude)
        val deltaLonRad = Math.toRadians(pos2.longitude - pos1.longitude)

        val a = sin(deltaLatRad / 2).pow(2) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLonRad / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    private fun calculateHeadingChange(heading1: Double, heading2: Double): Double {
        val diff = abs(heading2 - heading1)
        return min(diff, 360.0 - diff) // Handle wraparound (e.g., 359° to 1°)
    }

    private fun updateTrailPolyline(map: GoogleMap) {
        try { liveTrail?.remove() } catch (_: Exception) {}
        liveTrail = null
        if (trailPoints.size >= 2) {
            try {
                liveTrail = map.addPolyline(
                    PolylineOptions()
                        .addAll(trailPoints)
                        .color(0xFFFF6B35.toInt())
                        .width(4f)
                        .geodesic(true)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update trail polyline", e)
            }
        }
    }

    private fun clearLiveTrail() {
        liveTrail?.remove()
        liveTrail = null
        trailPoints.clear()
        lastTrailFix = null
    }

    private fun clearAccuracyCircle() {
        accuracyCircle?.remove()
        accuracyCircle = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // init panel references
        leftPanel = view.findViewById(R.id.left_panel)
        panelHandle = view.findViewById(R.id.panel_handle)
        collapseBtn = view.findViewById(R.id.btn_collapse_panel)
        expandBtn = view.findViewById(R.id.btn_expand_panel)

        // Initialize stakeout UI components
        stakeoutPanel = view.findViewById(R.id.stakeout_panel)
        btnToggleStakeout = view.findViewById(R.id.btn_toggle_stakeout)
        btnClearStakeout = view.findViewById(R.id.btn_clear_stakeout)
        txtStakeoutTarget = view.findViewById(R.id.txt_stakeout_target)
        txtStakeoutDistance = view.findViewById(R.id.txt_stakeout_distance)
        txtStakeoutBearing = view.findViewById(R.id.txt_stakeout_bearing)

        // Set up stakeout button listeners
        btnToggleStakeout?.setOnClickListener { toggleStakeoutMode() }
        btnClearStakeout?.setOnClickListener { clearStakeout() }

        // Set initial satellite button icon
        updateSatelliteButtonIcon()

        if (panelWidthPx == 0) {
            panelWidthPx = dpToPx(260f)
        }
        applyPanelState()
        setupPanelInteractions()
        setupMapClickListener()
    }

    // Lifecycle pass-throughs
    override fun onStart() {
        super.onStart()
        try { mapView?.onStart() } catch (e: Exception) { Log.e(TAG, "onStart error", e) }
    }

    override fun onResume() {
        super.onResume()
        try { mapView?.onResume() } catch (e: Exception) { Log.e(TAG, "onResume error", e) }
    }

    override fun onPause() {
        try { mapView?.onPause() } catch (e: Exception) { Log.e(TAG, "onPause error", e) }
        super.onPause()
    }

    override fun onStop() {
        try {
            clearLiveTrail()
            clearAccuracyCircle()
            mapView?.onStop()
        } catch (e: Exception) {
            Log.e(TAG, "onStop error", e)
        }
        super.onStop()
    }

    override fun onDestroyView() {
        try {
            clearLiveTrail()
            clearAccuracyCircle()
            currentMarker?.remove()
            currentMarker = null
        } catch (e: Exception) {
            Log.e(TAG, "onDestroyView cleanup error", e)
        }
        mapView = null
        placeholder = null
        super.onDestroyView()
    }

    private fun bindData() {
        if (dataObserved) return
        dataObserved = true
        val vm = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(requireActivity().application))
            .get(CoordinatesViewModel::class.java)
        vm.allCoordinates.observe(viewLifecycleOwner) { points ->
            if (googleMap == null) return@observe
            try {
                markerMap.values.forEach { it.remove() }
                markerMap.clear()
                if (points.isEmpty()) {
                    placeholder?.visibility = View.VISIBLE
                    lastLatLngs = emptyList()
                    toggleAdapter.submit(emptyList())
                    return@observe
                }
                placeholder?.visibility = View.GONE
                val latLngsVisible = ArrayList<LatLng>()
                val toggleItems = mutableListOf<CoordinateToggleItem>()
                points.forEach { p ->
                    val ll = LatLng(p.latitude, p.longitude)
                    val visible = visibilityMap[p.id] ?: true
                    visibilityMap.putIfAbsent(p.id, visible)

                    // Add marker immediately with default icon so the map responds instantly.
                    val opts = MarkerOptions().position(ll).title(p.name)
                    val marker = googleMap!!.addMarker(opts)
                    if (marker != null) {
                        marker.isVisible = visible
                        marker.tag = p.id
                        markerMap[p.id] = marker
                        if (visible) latLngsVisible.add(ll)

                        // Load the icon asynchronously — handles both built-in drawables and
                        // "model:<id>" thumbnail keys without blocking the observer.
                        viewLifecycleOwner.lifecycleScope.launch {
                            val descriptor = buildMarkerDescriptor(p.icon, p.color)
                            if (descriptor != null) marker.setIcon(descriptor)
                        }
                    }
                    toggleItems += CoordinateToggleItem(p.id, p.name, visible, p.icon, p.color)
                    coordinateMap[p.id] = p
                }
                lastLatLngs = latLngsVisible
                toggleAdapter.submit(toggleItems)
                updateCamera(latLngsVisible)
            } catch (e: Exception) {
                Log.e(TAG, "Error in data binding observer", e)
            }
        }
    }

    /**
     * Returns a [BitmapDescriptor] for the given icon key, or null for the default red pin.
     *
     * - `"model:<id>"` → loads the model's thumbnail PNG and composites it into a
     *   white rounded-square marker (matches CoordinateDetailFragment behaviour)
     * - any other non-blank string → treated as a drawable resource name, tinted [colorInt]
     */
    private suspend fun buildMarkerDescriptor(iconName: String?, colorInt: Int): BitmapDescriptor? {
        val ctx = context ?: return null
        if (iconName.isNullOrBlank()) return null

        // ── Model thumbnail marker ─────────────────────────────────────────
        if (iconName.startsWith("model:")) {
            val modelId = iconName.removePrefix("model:")
            return withContext(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getDatabase(ctx)
                    val repo = ModelRepositoryImpl(db.modelDao())
                    val model = repo.getModelById(modelId) ?: return@withContext null
                    val thumbPath = model.thumbnailFilePath
                    if (thumbPath.isNullOrBlank()) return@withContext null
                    val thumbBmp = BitmapFactory.decodeFile(thumbPath) ?: return@withContext null
                    withContext(Dispatchers.Main) {
                        buildModelMarkerBitmap(thumbBmp)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load model thumbnail for marker", e)
                    null
                }
            }
        }

        // ── Built-in drawable marker ───────────────────────────────────────
        @Suppress("DiscouragedApi")
        val resId = ctx.resources.getIdentifier(iconName, "drawable", ctx.packageName)
        if (resId == 0) return null
        val d = ContextCompat.getDrawable(ctx, resId) ?: return null
        val size = dpToPx(32f)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        try {
            @Suppress("DEPRECATION")
            d.mutate().setColorFilter(colorInt, PorterDuff.Mode.SRC_ATOP)
        } catch (_: Exception) {}
        d.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /**
     * Composites [thumb] into a white rounded-square marker bitmap.
     * Matches the appearance used in CoordinateDetailFragment.
     */
    private fun buildModelMarkerBitmap(thumb: Bitmap): BitmapDescriptor {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val markerPx = (56 * density).toInt()
        val borderPx = (2 * density)
        val radiusPx = (6 * density)

        val out = Bitmap.createBitmap(markerPx, markerPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // White rounded-square background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        canvas.drawRoundRect(RectF(0f, 0f, markerPx.toFloat(), markerPx.toFloat()), radiusPx, radiusPx, bgPaint)

        // Thumbnail inset by border
        val dst = RectF(borderPx, borderPx, markerPx - borderPx, markerPx - borderPx)
        canvas.drawBitmap(thumb, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        // Thin dark border
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x55000000
            strokeWidth = borderPx
        }
        canvas.drawRoundRect(RectF(0f, 0f, markerPx.toFloat(), markerPx.toFloat()), radiusPx, radiusPx, strokePaint)

        thumb.recycle()
        return BitmapDescriptorFactory.fromBitmap(out)
    }

    private fun updateCamera(latLngs: List<LatLng>) {
        val map = googleMap ?: return
        if (latLngs.isEmpty()) return
        try {
            if (latLngs.size == 1) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 20f))
            } else if (!cameraInitialized) {
                val builder = LatLngBounds.builder()
                latLngs.forEach { builder.include(it) }
                val bounds = builder.build()
                mapView?.post {
                    try { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100)) } catch (_: Exception) {}
                }
            }
            cameraInitialized = true
        } catch (e: Exception) {
            Log.e("RenderMap", "Camera update error", e)
        }
    }

    private fun recenterMap() {
        val map = googleMap ?: return
        val live = lastFixLatLng
        if (live != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(live, 20f))
            return
        }
        updateCamera(markerMap.filter { it.value.isVisible }.values.map { it.position })
    }
    private fun toggleSatellite() {
        isSatellite = !isSatellite
        googleMap?.mapType = if (isSatellite) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL
        updateSatelliteButtonIcon()
    }

    private fun updateSatelliteButtonIcon() {
        toggleSatBtn?.setImageResource(
            if (isSatellite) {
                android.R.drawable.ic_menu_mapmode // Map icon when in satellite mode (to switch back to map)
            } else {
                android.R.drawable.ic_menu_gallery // Satellite/gallery icon when in map mode (to switch to satellite)
            }
        )
    }

    private fun toggleGrid() {
        showGrid = !showGrid
        if (showGrid) {
            drawCoordinateGrid()
        } else {
            gridLines.forEach { it.remove() }
            gridLines.clear()
        }
    }

    private fun showAllCoordinates() {
        try {
            coordinateMap.keys.forEach { id ->
                visibilityMap[id] = true
                try { markerMap[id]?.isVisible = true } catch (_: Exception) {}
            }
            refreshToggleList()
        } catch (e: Exception) {
            Log.w(TAG, "showAllCoordinates error", e)
        }
    }

    private fun hideAllCoordinates() {
        try {
            coordinateMap.keys.forEach { id ->
                visibilityMap[id] = false
                try { markerMap[id]?.isVisible = false } catch (_: Exception) {}
            }
            refreshToggleList()
        } catch (e: Exception) {
            Log.w(TAG, "hideAllCoordinates error", e)
        }
    }

    private fun refreshToggleList() {
        try {
            val toggleItems = coordinateMap.values.map { coordinate ->
                val visible = visibilityMap[coordinate.id] ?: true
                CoordinateToggleItem(coordinate.id, coordinate.name, visible, coordinate.icon, coordinate.color)
            }
            toggleAdapter.submit(toggleItems)
        } catch (e: Exception) {
            Log.w(TAG, "refreshToggleList error", e)
        }
    }

    private fun drawCoordinateGrid() {
        val map = googleMap ?: return
        try {
            val bounds = map.projection.visibleRegion.latLngBounds
            val latStep = calculateGridStep(bounds.northeast.latitude - bounds.southwest.latitude)
            var lat = (bounds.southwest.latitude / latStep).toInt() * latStep
            while (lat <= bounds.northeast.latitude) {
                try {
                    gridLines.add(map.addPolyline(
                        PolylineOptions()
                            .add(LatLng(lat, bounds.southwest.longitude))
                            .add(LatLng(lat, bounds.northeast.longitude))
                            .color(0x40000000).width(1f)
                    ))
                } catch (_: Exception) {}
                lat += latStep
            }
            val lngStep = calculateGridStep(bounds.northeast.longitude - bounds.southwest.longitude)
            var lng = (bounds.southwest.longitude / lngStep).toInt() * lngStep
            while (lng <= bounds.northeast.longitude) {
                try {
                    gridLines.add(map.addPolyline(
                        PolylineOptions()
                            .add(LatLng(bounds.southwest.latitude, lng))
                            .add(LatLng(bounds.northeast.latitude, lng))
                            .color(0x40000000).width(1f)
                    ))
                } catch (_: Exception) {}
                lng += lngStep
            }
        } catch (e: Exception) {
            Log.w(TAG, "drawCoordinateGrid error", e)
        }
    }

    private fun calculateGridStep(range: Double): Double {
        return when {
            range > 1.0 -> 0.1
            range > 0.1 -> 0.01
            range > 0.01 -> 0.001
            else -> 0.0001
        }
    }

    private fun toggleBoundaryLines() {
        showBoundaries = !showBoundaries
        if (showBoundaries) {
            drawBoundaryLines()
        } else {
            boundaryLines.forEach { it.remove() }
            boundaryLines.clear()
        }
    }

    private fun drawBoundaryLines() {
        try {
            val visibleCoords = coordinateMap.values.filter {
                visibilityMap[it.id] == true
            }.sortedBy { it.timestamp }
            if (visibleCoords.size < 2) return
            for (i in 0 until visibleCoords.size - 1) {
                val start = LatLng(visibleCoords[i].latitude, visibleCoords[i].longitude)
                val end = LatLng(visibleCoords[i + 1].latitude, visibleCoords[i + 1].longitude)
                try {
                    googleMap?.addPolyline(
                        PolylineOptions().add(start, end)
                            .color(0xFF2196F3.toInt()).width(3f).geodesic(true)
                    )?.let { boundaryLines.add(it) }
                } catch (_: Exception) {}
            }
            if (visibleCoords.size >= 3) {
                val start = LatLng(visibleCoords.last().latitude, visibleCoords.last().longitude)
                val end = LatLng(visibleCoords.first().latitude, visibleCoords.first().longitude)
                try {
                    googleMap?.addPolyline(
                        PolylineOptions().add(start, end)
                            .color(0xFF2196F3.toInt()).width(3f).geodesic(true)
                            .pattern(listOf(Dash(20f), Gap(10f)))
                    )?.let { boundaryLines.add(it) }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "drawBoundaryLines error", e)
        }
    }

    private fun setupPanelInteractions() {
        collapseBtn?.setOnClickListener { collapsePanel() }
        expandBtn?.setOnClickListener { expandPanel() }
        panelHandle?.setOnTouchListener { v, event ->
            if (panelCollapsed) return@setOnTouchListener false
            val lp = leftPanel?.layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    lastDragX = event.rawX
                    startDragWidth = lp.width
                    dragStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastDragX).toInt()
                    var newWidth = startDragWidth + dx
                    val minPx = dpToPx(minPanelDp)
                    val maxPx = dpToPx(maxPanelDp)
                    if (newWidth < minPx) newWidth = minPx
                    if (newWidth > maxPx) newWidth = maxPx
                    panelWidthPx = newWidth
                    lp.width = newWidth
                    leftPanel?.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val totalDx = event.rawX - lastDragX
                    val dt = (System.currentTimeMillis() - dragStartTime).coerceAtLeast(1)
                    val velocity = totalDx / dt.toFloat()
                    if (totalDx < -dpToPx(80f) || velocity < -0.6f) {
                        collapsePanel()
                    } else {
                        // treat as click if minimal movement
                        if (kotlin.math.abs(totalDx) < dpToPx(4f)) v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        leftPanel?.setOnTouchListener { v, ev ->
            if (panelCollapsed) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    panelSwipeDownX = ev.rawX
                    panelSwipeDownTime = System.currentTimeMillis()
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - panelSwipeDownX
                    val dt = (System.currentTimeMillis() - panelSwipeDownTime).coerceAtLeast(1)
                    val vVel = dx / dt.toFloat()
                    if (dx < -dpToPx(100f) || vVel < -0.7f) {
                        collapsePanel(); true
                    } else {
                        if (kotlin.math.abs(dx) < dpToPx(8f)) v.performClick()
                        false
                    }
                }
                else -> false
            }
        }
    }

    private var lastDragX: Float = 0f
    private var startDragWidth: Int = 0
    private var dragStartTime: Long = 0L
    private var panelSwipeDownX: Float = 0f
    private var panelSwipeDownTime: Long = 0L

    private fun collapsePanel() {
        if (panelCollapsed) return
        val lp = leftPanel?.layoutParams ?: return
        val start = lp.width
        val anim = ValueAnimator.ofInt(start, 0)
        anim.duration = 200
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.addUpdateListener {
            val v = it.animatedValue as Int
            lp.width = v
            leftPanel?.layoutParams = lp
        }
        anim.doOnEnd {
            panelCollapsed = true
            leftPanel?.visibility = View.GONE
            panelHandle?.visibility = View.GONE
            expandBtn?.visibility = View.VISIBLE
        }
        anim.start()
    }

    private fun expandPanel() {
        if (!panelCollapsed) return
        val target = if (panelWidthPx <= 0) dpToPx(260f) else panelWidthPx
        panelWidthPx = target
        leftPanel?.visibility = View.VISIBLE
        panelHandle?.visibility = View.VISIBLE
        expandBtn?.visibility = View.GONE
        val lp = leftPanel?.layoutParams ?: return
        lp.width = 0
        leftPanel?.layoutParams = lp
        val anim = ValueAnimator.ofInt(0, target)
        anim.duration = 220
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.addUpdateListener {
            val v = it.animatedValue as Int
            lp.width = v
            leftPanel?.layoutParams = lp
        }
        anim.doOnEnd { panelCollapsed = false }
        anim.start()
    }

    private fun applyPanelState() {
        if (panelCollapsed) {
            leftPanel?.visibility = View.GONE
            panelHandle?.visibility = View.GONE
            expandBtn?.visibility = View.VISIBLE
        } else {
            val lp = leftPanel?.layoutParams
            if (lp != null) {
                if (panelWidthPx <= 0) panelWidthPx = dpToPx(260f)
                lp.width = panelWidthPx
                leftPanel?.layoutParams = lp
            }
            leftPanel?.visibility = View.VISIBLE
            panelHandle?.visibility = View.VISIBLE
            expandBtn?.visibility = View.GONE
        }
    }

    private fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun createInfoWindowView(marker: Marker): View? {
        return try {
            val coordinateId = marker.tag as? String
            val coordinate = coordinateId?.let { coordinateMap[it] }
            @Suppress("InflateParams")
            val view = layoutInflater.inflate(R.layout.custom_info_window, null)
            val titleView = view.findViewById<TextView>(R.id.info_window_title)
            val contentView = view.findViewById<TextView>(R.id.info_window_content)
            titleView.text = coordinate?.name ?: marker.title ?: "Unknown"
            contentView.text = buildString {
                if (coordinate != null) {
                    append("Lat: ${String.format(Locale.getDefault(), "%.6f", coordinate.latitude)}\n")
                    append("Lon: ${String.format(Locale.getDefault(), "%.6f", coordinate.longitude)}")
                    append("\nElevation: ${String.format(Locale.getDefault(), "%.2f", coordinate.altitude)}m")
                    if (!coordinate.note.isNullOrBlank()) append("\n${coordinate.note}")
                } else {
                    append("Position: ${marker.position.latitude}, ${marker.position.longitude}")
                }
            }
            view
        } catch (e: Exception) {
            Log.w(TAG, "createInfoWindowView error", e)
            null
        }
    }

    private fun getCurrentCoordinateFormat(): String {
        // Default coordinate format - this could be made configurable
        return "decimal"
    }

    private fun toggleStakeoutMode() {
        isStakeoutMode = !isStakeoutMode
        if (isStakeoutMode) {
            // Activate stakeout mode
            stakeoutPanel?.visibility = View.VISIBLE
            btnToggleStakeout?.text = "Stop Stakeout"
            // Clear any existing stakeout marker
            stakeoutMarker?.remove()
            stakeoutMarker = null
        } else {
            // Deactivate stakeout mode
            stakeoutPanel?.visibility = View.GONE
            btnToggleStakeout?.text = "Start Stakeout"
            // Clear stakeout target
            stakeoutTarget = null
            txtStakeoutTarget?.text = "Target: None"
            // Remove stakeout marker if it exists
            stakeoutMarker?.remove()
            stakeoutMarker = null
        }
    }

    private fun clearStakeout() {
        // Clear stakeout target and marker
        stakeoutTarget = null
        txtStakeoutTarget?.text = "Target: None"
        stakeoutMarker?.remove()
        stakeoutMarker = null
    }

    private fun setupMapClickListener() {
        googleMap?.setOnMapClickListener { latLng ->
            if (!isStakeoutMode) return@setOnMapClickListener
            try {
                stakeoutTarget = latLng
                txtStakeoutTarget?.text = "Target: ${latLng.latitude}, ${latLng.longitude}"
                if (stakeoutMarker == null) {
                    stakeoutMarker = googleMap?.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title("Stakeout Target")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    )
                } else {
                    try { stakeoutMarker?.position = latLng } catch (e: Exception) { Log.w(TAG, "stakeout marker move failed", e) }
                }
                updateStakeoutDistanceAndBearing(latLng)
            } catch (e: Exception) {
                Log.w(TAG, "Map click stakeout error", e)
            }
        }
    }

    private fun updateStakeoutDistanceAndBearing(target: LatLng) {
        val currentPos = lastFixLatLng ?: return
        try {
            val distance = calculateDistance(currentPos, target)
            val bearing = calculateBearing(currentPos, target)
            txtStakeoutDistance?.text = String.format(Locale.getDefault(), "Distance: %.1f m", distance)
            txtStakeoutBearing?.text = String.format(Locale.getDefault(), "Bearing: %.1f°", bearing)
            updateStakeoutMarkerColor(distance)
        } catch (e: Exception) {
            Log.w(TAG, "updateStakeoutDistanceAndBearing error", e)
        }
    }

    private fun calculateBearing(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon2 = Math.toRadians(to.longitude)

        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))

        // Normalize bearing to 0-360°
        return (bearing + 360) % 360
    }
}
