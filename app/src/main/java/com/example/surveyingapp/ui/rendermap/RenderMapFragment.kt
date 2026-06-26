package com.example.surveyingapp.ui.rendermap

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
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.ui.map.MapThemeHelper
import com.example.surveyingapp.ui.viewpoints.CoordinatesViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@AndroidEntryPoint
class RenderMapFragment : Fragment() {

    companion object {
        private const val TAG = "RenderMapFragment"
        private const val MIN_DISTANCE_METERS = 0.5
        private const val MIN_HEADING_CHANGE_DEGREES = 5.0
        private const val MAX_TRAIL_POINTS = 1000
        private const val STAKEOUT_GREEN_THRESHOLD = 0.10
        private const val STAKEOUT_AMBER_THRESHOLD = 0.25
        // Display-only thresholds (no persisted setting, no effect on calculations):
        //  • within "tolerance" reuses the existing green distance threshold (10 cm).
        //  • low-accuracy warning when the live horizontal accuracy is worse than this.
        private const val STAKEOUT_TOLERANCE_THRESHOLD_M = STAKEOUT_GREEN_THRESHOLD
        private const val STAKEOUT_LOW_ACCURACY_M = 0.30
    }

    @Inject
    lateinit var fixSwitchboard: FixSwitchboard

    @Inject
    lateinit var sourceSettings: com.example.surveyingapp.gnss.source.SourceSettings

    @Inject
    lateinit var modelRepository: com.example.surveyingapp.domain.repository.ModelRepository

    @Inject
    lateinit var stakeoutSettingsRepo: com.example.surveyingapp.domain.repository.StakeoutSettingsRepository

    // Map
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var placeholder: View? = null
    private var lastLatLngs: List<LatLng> = emptyList()
    private var isSatellite = false
    private var currentMapType = GoogleMap.MAP_TYPE_NORMAL
    private var dataObserved = false
    private var cameraInitialized = false

    // Panel
    private var leftPanel: View? = null
    private var collapseBtn: View? = null   // ImageButton in new layout, typed as View
    private var expandBtn: View? = null     // ExtendedFAB in new layout, typed as View
    private var leftPanelSubtitle: TextView? = null
    private var panelHandle: View? = null   // GONE compat stub
    private var panelWidthPx: Int = 0
    private var panelCollapsed = false
    private val minPanelDp = 160f
    private val maxPanelDp = 480f
    private var panelSwipeDownX: Float = 0f
    private var panelSwipeDownTime: Long = 0L
    private var lastDragX: Float = 0f
    private var startDragWidth: Int = 0
    private var dragStartTime: Long = 0L

    // Coordinate list
    private var toggleRecycler: RecyclerView? = null
    private lateinit var toggleAdapter: CoordinateToggleAdapter
    private val markerMap = mutableMapOf<String, Marker>()
    private val visibilityMap = mutableMapOf<String, Boolean>()
    private val coordinateMap = mutableMapOf<String, Coordinate>()

    // Visibility menu button in panel header
    private var visibilityMenuBtn: ImageButton? = null

    // Map Tools overlay (collapsible toolbar modeled on the AR floating toolbar)
    private var mapToolsToggle: View? = null
    private var mapToolsPanel: View? = null
    private var mapToolsVisible = false

    // Grid / boundary (internal logic unchanged)
    private var showGrid = false
    private val gridLines = mutableListOf<Polyline>()
    private val boundaryLines = mutableListOf<Polyline>()
    private var showBoundaries = false

    // Live tracking
    private var currentMarker: Marker? = null
    private var lastFixLatLng: LatLng? = null
    private var accuracyCircle: Circle? = null
    private var liveTrail: Polyline? = null
    private val trailPoints = mutableListOf<LatLng>()
    private var lastTrailFix: Fix? = null
    private var currentFix: Fix? = null
    private var lastCurrentMarkerHue: Float? = null
    private var lastStakeoutMarkerHue: Float? = null

    // Marker descriptor cache — keyed by "icon:$name:$color" or "model:$id:$path"
    private val markerDescriptorCache = mutableMapOf<String, BitmapDescriptor>()

    // Stakeout
    private var isStakeoutMode = false
    private var stakeoutTarget: LatLng? = null
    private var stakeoutMarker: Marker? = null
    private var stakeoutLine: Polyline? = null
    private var stakeoutPanel: View? = null
    private var btnToggleStakeout: Button? = null   // GONE compat stub
    private var btnClearStakeout: View? = null
    private var txtStakeoutTarget: TextView? = null
    private var txtStakeoutDistance: TextView? = null
    private var txtStakeoutBearing: TextView? = null
    private var txtStakeoutDelta: TextView? = null
    private var txtStakeoutStatus: TextView? = null
    private var txtStakeoutAccuracy: TextView? = null
    private var stakeoutAccuracyDefaultColor: Int = 0

    // Stakeout guidance mode (display/feedback only — does not touch distance/bearing math)
    private var imgStakeoutArrow: ImageView? = null
    private var txtStakeoutTolerance: TextView? = null
    private var txtStakeoutHeadingNote: TextView? = null
    private var btnStakeoutGuidance: com.google.android.material.button.MaterialButton? = null
    private var stakeoutSettings = com.example.surveyingapp.settings.model.StakeoutSettings()
    private var isGuidanceActive = false
    private val feedbackGate = com.example.surveyingapp.stakeout.StakeoutFeedbackGate()
    private var compassHeadingDeg: Double? = null
    private var sensorManager: android.hardware.SensorManager? = null
    private var rotationSensor: android.hardware.Sensor? = null
    private var toneGenerator: android.media.ToneGenerator? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val rotationListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            if (event.sensor.type != android.hardware.Sensor.TYPE_ROTATION_VECTOR) return
            android.hardware.SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            android.hardware.SensorManager.getOrientation(rotationMatrix, orientationAngles)
            compassHeadingDeg =
                com.example.surveyingapp.stakeout.StakeoutGuidance.normalize360(Math.toDegrees(orientationAngles[0].toDouble()))
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    // Selected coordinate card
    private var selectedCoordinateId: String? = null
    private var selectedCoordinateCard: View? = null
    private var imgSelectedIcon: ImageView? = null
    private var txtSelectedName: TextView? = null
    private var txtSelectedSubtitle: TextView? = null
    private var btnSelectedClose: View? = null
    private var btnSelectedCenter: View? = null
    private var btnSelectedStakeout: View? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_render_map, container, false)

        mapView = root.findViewById(R.id.mapView)
        // Empty state now lives inside the left panel ("No saved points to show") rather than a
        // confusing centered "No render available" overlay on top of a perfectly good map.
        placeholder = root.findViewById(R.id.left_panel_empty)
        toggleRecycler = root.findViewById(R.id.coordinate_toggle_list)
        visibilityMenuBtn = root.findViewById(R.id.btn_visibility_menu)
        leftPanelSubtitle = root.findViewById(R.id.left_panel_subtitle)

        // Selected coord card
        selectedCoordinateCard = root.findViewById(R.id.card_selected_coordinate)
        imgSelectedIcon = root.findViewById(R.id.img_selected_icon)
        txtSelectedName = root.findViewById(R.id.txt_selected_name)
        txtSelectedSubtitle = root.findViewById(R.id.txt_selected_subtitle)
        btnSelectedClose = root.findViewById(R.id.btn_selected_close)
        btnSelectedCenter = root.findViewById(R.id.btn_selected_center)
        btnSelectedStakeout = root.findViewById(R.id.btn_selected_stakeout)

        // Map Tools collapsible overlay (toggle + panel)
        mapToolsToggle = root.findViewById(R.id.btnMapToolsToggle)
        mapToolsPanel = root.findViewById(R.id.layoutMapTools)

        toggleAdapter = CoordinateToggleAdapter(
            modelRepository = modelRepository,
            onToggle = { id, checked ->
                visibilityMap[id] = checked
                markerMap[id]?.isVisible = checked
                updateVisibleCount()
                if (!checked) showSnackbar("Coordinate hidden")
            },
            onRowClick = { id -> selectCoordinate(id) }
        )
        toggleRecycler?.layoutManager = LinearLayoutManager(requireContext())
        toggleRecycler?.adapter = toggleAdapter

        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync { map ->
            Log.d(TAG, "onMapReady: map ready, mapType=$currentMapType")
            googleMap = map
            map.mapType = currentMapType
            MapThemeHelper.applyTheme(requireContext(), map, currentMapType)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.7963, -77.8570), 15f))
            map.setMaxZoomPreference(22f)
            map.setMinZoomPreference(2f)

            map.setInfoWindowAdapter(object : GoogleMap.InfoWindowAdapter {
                override fun getInfoWindow(marker: Marker): View? = null
                override fun getInfoContents(marker: Marker): View? = createInfoWindowView(marker)
            })

            map.setOnMarkerClickListener { marker ->
                val id = marker.tag as? String
                if (id != null && coordinateMap.containsKey(id)) {
                    selectCoordinate(id)
                } else {
                    marker.showInfoWindow()
                }
                true
            }

            map.setOnMapLoadedCallback {
                if (!cameraInitialized && lastLatLngs.isNotEmpty()) updateCamera(lastLatLngs)
            }
            bindData()
            startFixCollection()
            setupMapClickListener()
        }

        // Map Tools overlay wiring. The toggle slides the panel in/out (AR pattern); each row
        // delegates to the SAME existing map action handlers — only the presentation changed.
        mapToolsToggle?.setOnClickListener { toggleMapTools() }
        // Always-visible one-tap recenter (Center row inside Map Tools is kept too).
        root.findViewById<View>(R.id.btnMapRecenter)?.setOnClickListener { recenterMap() }
        visibilityMenuBtn?.setOnClickListener { showVisibilityMenu(it) }
        root.findViewById<View>(R.id.btnMapType)?.setOnClickListener { showLayersMenu(it) }
        root.findViewById<View>(R.id.btnMapCenter)?.setOnClickListener { recenterMap() }
        root.findViewById<View>(R.id.btnMapGrid)?.setOnClickListener { toggleGrid() }
        root.findViewById<View>(R.id.btnMapZoomIn)?.setOnClickListener {
            googleMap?.animateCamera(CameraUpdateFactory.zoomIn())
        }
        root.findViewById<View>(R.id.btnMapZoomOut)?.setOnClickListener {
            googleMap?.animateCamera(CameraUpdateFactory.zoomOut())
        }
        root.findViewById<View>(R.id.btnMapCompass)?.setOnClickListener { resetMapOrientation() }
        root.findViewById<View>(R.id.btnMapFit)?.setOnClickListener { fitVisibleCoordinates() }

        // Selected coord card buttons
        btnSelectedClose?.setOnClickListener { dismissSelectedCoordinate() }
        btnSelectedCenter?.setOnClickListener {
            selectedCoordinateId?.let { id ->
                coordinateMap[id]?.let { c ->
                    googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(c.latitude, c.longitude),
                        (googleMap?.cameraPosition?.zoom ?: 18f).coerceAtLeast(16f)
                    ))
                }
            }
        }
        btnSelectedStakeout?.setOnClickListener { startStakeoutForSelectedCoordinate() }

        if (savedInstanceState != null) {
            panelWidthPx = savedInstanceState.getInt("panelWidthPx", 0)
            panelCollapsed = savedInstanceState.getBoolean("panelCollapsed", false)
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        leftPanel = view.findViewById(R.id.left_panel)
        panelHandle = view.findViewById(R.id.panel_handle)  // GONE stub
        collapseBtn = view.findViewById(R.id.btn_collapse_panel)
        expandBtn = view.findViewById(R.id.btn_expand_panel)

        stakeoutPanel = view.findViewById(R.id.stakeout_panel)
        btnToggleStakeout = view.findViewById(R.id.btn_toggle_stakeout)  // GONE stub
        btnClearStakeout = view.findViewById(R.id.btn_clear_stakeout)
        txtStakeoutTarget = view.findViewById(R.id.txt_stakeout_target)
        txtStakeoutDistance = view.findViewById(R.id.txt_stakeout_distance)
        txtStakeoutBearing = view.findViewById(R.id.txt_stakeout_bearing)
        txtStakeoutDelta = view.findViewById(R.id.txt_stakeout_delta)
        txtStakeoutStatus = view.findViewById(R.id.txt_stakeout_status)
        txtStakeoutAccuracy = view.findViewById(R.id.txt_stakeout_accuracy)
        // Capture the theme default text color so the low-accuracy warning can revert cleanly.
        stakeoutAccuracyDefaultColor = txtStakeoutAccuracy?.currentTextColor ?: 0

        imgStakeoutArrow = view.findViewById(R.id.img_stakeout_arrow)
        txtStakeoutTolerance = view.findViewById(R.id.txt_stakeout_tolerance)
        txtStakeoutHeadingNote = view.findViewById(R.id.txt_stakeout_heading_note)
        btnStakeoutGuidance = view.findViewById(R.id.btn_stakeout_guidance)

        btnToggleStakeout?.setOnClickListener { toggleStakeoutMode() }
        btnClearStakeout?.setOnClickListener { stopStakeout() }
        btnStakeoutGuidance?.setOnClickListener { if (isGuidanceActive) stopGuidance() else startGuidance() }

        // Keep the latest stakeout settings in memory for the guidance loop.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                stakeoutSettingsRepo.stakeoutSettings.collect { stakeoutSettings = it }
            }
        }

        if (panelWidthPx == 0) panelWidthPx = dpToPx(272f)
        applyPanelState()
        setupPanelInteractions()
        startProviderObservation()
    }

    // ── Lifecycle pass-throughs ────────────────────────────────────────────────

    override fun onStart()  { super.onStart();  try { mapView?.onStart() } catch (e: Exception) { Log.e(TAG, "onStart", e) } }
    override fun onResume() { super.onResume(); try { mapView?.onResume(); resumeGuidanceFeedback() } catch (e: Exception) { Log.e(TAG, "onResume", e) } }
    override fun onPause()  { try { mapView?.onPause(); pauseGuidanceFeedback() } catch (e: Exception) { Log.e(TAG, "onPause", e) }; super.onPause() }

    override fun onStop() {
        try { clearLiveTrail(); clearAccuracyCircle(); mapView?.onStop() } catch (e: Exception) { Log.e(TAG, "onStop", e) }
        super.onStop()
    }

    override fun onDestroyView() {
        try {
            stopGuidance()   // unregister sensor, release tone, clear keep-screen-on
            clearLiveTrail(); clearAccuracyCircle()
            currentMarker?.remove(); currentMarker = null
            stakeoutLine?.remove(); stakeoutLine = null
        } catch (e: Exception) { Log.e(TAG, "onDestroyView cleanup", e) }
        markerDescriptorCache.clear()
        lastCurrentMarkerHue = null
        lastStakeoutMarkerHue = null
        try { mapView?.onDestroy() } catch (e: Exception) { Log.e(TAG, "mapView.onDestroy", e) }
        mapView = null; placeholder = null
        super.onDestroyView()
    }

    // ── GNSS fix collection ────────────────────────────────────────────────────

    private fun startProviderObservation() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previousProvider: com.example.surveyingapp.gnss.source.SourceSettings.ProviderChoice? = null
                sourceSettings.activeProvider.collect { provider ->
                    if (previousProvider != null && previousProvider != provider) {
                        clearLiveTrail()
                        clearAccuracyCircle()
                        currentMarker?.remove(); currentMarker = null
                        lastFixLatLng = null
                        currentFix = null
                        lastCurrentMarkerHue = null
                    }
                    previousProvider = provider
                }
            }
        }
    }

    private fun startFixCollection() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    fixSwitchboard.fixes
                        .conflate()
                        .sample(250)
                        .collect { fix: Fix -> updateLiveTracking(fix) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Error collecting fixes", e)
                }
            }
        }
    }

    private fun updateLiveTracking(fix: Fix) {
        try {
            if (fix.latDeg !in -90.0..90.0 || fix.lonDeg !in -180.0..180.0) return
            val pos = LatLng(fix.latDeg, fix.lonDeg)
            currentFix = fix
            try { updateCurrentMarker(fix.latDeg, fix.lonDeg, fix.rtkStatus) } catch (e: Exception) { Log.w(TAG, "updateCurrentMarker", e) }
            try { updateAccuracyCircle(pos, fix.hAccM) } catch (e: Exception) { Log.w(TAG, "updateAccuracyCircle", e) }
            try { updateLiveTrail(pos, fix) } catch (e: Exception) { Log.w(TAG, "updateLiveTrail", e) }
            try { updateStakeoutCalculations(pos) } catch (e: Exception) { Log.w(TAG, "updateStakeoutCalculations", e) }
        } catch (e: Exception) {
            Log.e(TAG, "updateLiveTracking error", e)
        }
    }

    // ── Map Tools overlay (collapsible, AR-style) ──────────────────────────────

    /** Slides the Map Tools panel in/out, mirroring the AR floating-toolbar toggle. */
    private fun toggleMapTools() {
        val panel = mapToolsPanel ?: return
        if (mapToolsVisible) {
            panel.animate()
                .translationX(panel.width.toFloat().coerceAtLeast(200f))
                .alpha(0f)
                .setDuration(160)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { panel.visibility = View.GONE }
                .start()
            mapToolsToggle?.animate()?.rotation(0f)?.setDuration(160)?.start()
            mapToolsToggle?.contentDescription = "Show map tools"
            mapToolsVisible = false
        } else {
            panel.translationX = panel.width.toFloat().coerceAtLeast(200f)
            panel.alpha = 0f
            panel.visibility = View.VISIBLE
            panel.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            mapToolsToggle?.animate()?.rotation(90f)?.setDuration(200)?.start()
            mapToolsToggle?.contentDescription = "Hide map tools"
            mapToolsVisible = true
        }
    }

    // ── Stakeout calculations ──────────────────────────────────────────────────

    private fun updateStakeoutCalculations(currentPos: LatLng) {
        val target = stakeoutTarget ?: return
        if (!isStakeoutMode) return
        try {
            // Distance + bearing use the EXISTING haversine/great-circle helpers — unchanged math.
            val distance = calculateDistance(currentPos, target)
            val bearing = calculateBearing(currentPos, target)
            val distanceText = when {
                distance < 1.0  -> String.format(Locale.getDefault(), "%.2f m", distance)
                distance < 10.0 -> String.format(Locale.getDefault(), "%.1f m", distance)
                else            -> String.format(Locale.getDefault(), "%.0f m", distance)
            }
            txtStakeoutDistance?.text = distanceText
            // Bearing shown with an 8-point compass label, e.g. "NE 42°".
            txtStakeoutBearing?.text =
                String.format(Locale.getDefault(), "%s %.0f°", compassPoint(bearing), bearing)
            val color = when {
                distance < STAKEOUT_GREEN_THRESHOLD -> 0xFF4CAF50.toInt()
                distance < STAKEOUT_AMBER_THRESHOLD -> 0xFFFF9800.toInt()
                else                                -> 0xFFF44336.toInt()
            }
            txtStakeoutDistance?.setTextColor(color)
            txtStakeoutBearing?.setTextColor(color)

            // Display-only north/east offsets (metres).
            val (dN, dE) = northEastDeltaMeters(currentPos, target)
            txtStakeoutDelta?.text =
                String.format(Locale.getDefault(), "ΔN %+.2f m    ΔE %+.2f m", dN, dE)

            // State line: within tolerance vs. which way to move.
            txtStakeoutStatus?.text =
                if (distance < STAKEOUT_TOLERANCE_THRESHOLD_M) "Within tolerance"
                else "Move ${compassDirectionWord(bearing)}"

            updateStakeoutAccuracy()
            updateStakeoutMarkerColor(distance)
            updateStakeoutLine(currentPos, target)
            updateGuidance(distance, bearing)
        } catch (e: Exception) {
            Log.w(TAG, "updateStakeoutCalculations error", e)
        }
    }

    /** Target is set but there is no live fix yet — show a clear "waiting" state. Display-only. */
    private fun showStakeoutWaiting() {
        txtStakeoutDistance?.text = "—"
        txtStakeoutBearing?.text = "—"
        txtStakeoutDelta?.text = "—"
        txtStakeoutStatus?.text = "Waiting for live position…"
        txtStakeoutAccuracy?.visibility = View.GONE
    }

    /** Re-render the stakeout card from the latest fix, or the waiting state if none yet. */
    private fun refreshStakeout() {
        val fix = lastFixLatLng
        if (fix != null) updateStakeoutCalculations(fix) else showStakeoutWaiting()
    }

    /** Live horizontal accuracy (the only GNSS detail in the card) + non-blocking low-accuracy warning. */
    private fun updateStakeoutAccuracy() {
        val tv = txtStakeoutAccuracy ?: return
        val acc = currentFix?.hAccM
        if (acc == null || acc <= 0.0) { tv.visibility = View.GONE; return }
        tv.visibility = View.VISIBLE
        if (acc > STAKEOUT_LOW_ACCURACY_M) {
            tv.text = String.format(
                Locale.getDefault(), "Accuracy ±%.2f m · Low accuracy — stakeout may be unreliable", acc
            )
            tv.setTextColor(0xFFF44336.toInt())
        } else {
            tv.text = String.format(Locale.getDefault(), "Accuracy ±%.2f m", acc)
            tv.setTextColor(stakeoutAccuracyDefaultColor)
        }
    }

    /** 8-point compass abbreviation for a bearing in degrees (display-only). */
    private fun compassPoint(bearing: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = ((((bearing % 360) + 360) % 360) / 45.0).roundToInt() % 8
        return dirs[idx]
    }

    /** Human-readable "move" word for a bearing (display-only). */
    private fun compassDirectionWord(bearing: Double): String = when (compassPoint(bearing)) {
        "N"  -> "north"
        "NE" -> "northeast"
        "E"  -> "east"
        "SE" -> "southeast"
        "S"  -> "south"
        "SW" -> "southwest"
        "W"  -> "west"
        else -> "northwest"
    }

    /**
     * Display-only local north/east offsets (metres) from [from] to [to], using the same Earth
     * radius as [calculateDistance]. Equirectangular approximation — adequate at stakeout distances.
     * It does NOT feed the distance/bearing values shown, which keep their existing math.
     */
    private fun northEastDeltaMeters(from: LatLng, to: LatLng): Pair<Double, Double> {
        val r = 6371000.0
        val dN = Math.toRadians(to.latitude - from.latitude) * r
        val dE = Math.toRadians(to.longitude - from.longitude) * r * cos(Math.toRadians(from.latitude))
        return dN to dE
    }

    // ── Stakeout guidance mode (display + feedback only) ───────────────────────

    private fun startGuidance() {
        if (!isStakeoutMode || stakeoutTarget == null) { showSnackbar("Select a stakeout target first"); return }
        isGuidanceActive = true
        feedbackGate.reset()
        btnStakeoutGuidance?.text = "Stop Guidance"
        imgStakeoutArrow?.visibility = View.VISIBLE
        txtStakeoutTolerance?.visibility = View.VISIBLE
        txtStakeoutTolerance?.text =
            String.format(Locale.getDefault(), "Tolerance: %.2f m", stakeoutSettings.toleranceMeters)
        if (stakeoutSettings.guidanceUsesCompassHeading) startCompass()
        if (stakeoutSettings.enableAudio) ensureToneGenerator()
        if (stakeoutSettings.keepScreenOnDuringStakeout) setKeepScreenOn(true)
        refreshStakeout()
    }

    private fun stopGuidance() {
        isGuidanceActive = false
        btnStakeoutGuidance?.text = "Start Guidance"
        imgStakeoutArrow?.visibility = View.GONE
        txtStakeoutTolerance?.visibility = View.GONE
        txtStakeoutHeadingNote?.visibility = View.GONE
        stopCompass()
        releaseToneGenerator()
        setKeepScreenOn(false)
        feedbackGate.reset()
    }

    /** Pause feedback (sensor/audio/screen) but keep the active target — for backgrounding. */
    private fun pauseGuidanceFeedback() {
        if (!isGuidanceActive) return
        stopCompass()
        releaseToneGenerator()
        setKeepScreenOn(false)
    }

    private fun resumeGuidanceFeedback() {
        if (!isGuidanceActive) return
        if (stakeoutSettings.guidanceUsesCompassHeading) startCompass()
        if (stakeoutSettings.enableAudio) ensureToneGenerator()
        if (stakeoutSettings.keepScreenOnDuringStakeout) setKeepScreenOn(true)
    }

    /** Updates the arrow + fires throttled haptics/audio. Uses the passed-in distance/bearing — no new math. */
    private fun updateGuidance(distanceMeters: Double, bearingToTargetDeg: Double) {
        if (!isGuidanceActive) return
        val s = stakeoutSettings
        val (source, heading) = com.example.surveyingapp.stakeout.StakeoutGuidance.resolveHeading(
            preferCompass = s.guidanceUsesCompassHeading,
            compassHeadingDeg = compassHeadingDeg,
            courseOverGroundDeg = currentFix?.courseDeg,
            speedMps = currentFix?.speedMps,
        )
        val relative = com.example.surveyingapp.stakeout.StakeoutGuidance.relativeBearing(bearingToTargetDeg, heading)
        // Arrow shows the relative direction when heading is known, else points at the map-north bearing.
        imgStakeoutArrow?.rotation = (relative ?: bearingToTargetDeg).toFloat()
        if (source == com.example.surveyingapp.stakeout.HeadingSource.NORTH_UP) {
            txtStakeoutHeadingNote?.visibility = View.VISIBLE
            txtStakeoutHeadingNote?.text = "Arrow is north-up until heading is available."
        } else {
            txtStakeoutHeadingNote?.visibility = View.GONE
        }
        val status = com.example.surveyingapp.stakeout.StakeoutGuidance.status(
            hasTarget = true, hasPosition = true, distanceMeters = distanceMeters, toleranceMeters = s.toleranceMeters
        )
        when (feedbackGate.onUpdate(status, android.os.SystemClock.elapsedRealtime())) {
            com.example.surveyingapp.stakeout.StakeoutFeedback.ENTERED_TOLERANCE -> {
                if (s.enableHaptics) haptic(arrived = true); if (s.enableAudio) beep(arrived = true)
            }
            com.example.surveyingapp.stakeout.StakeoutFeedback.NAVIGATING_PULSE -> {
                if (s.enableHaptics) haptic(arrived = false); if (s.enableAudio) beep(arrived = false)
            }
            com.example.surveyingapp.stakeout.StakeoutFeedback.NONE -> {}
        }
    }

    private fun startCompass() {
        try {
            val sm = sensorManager ?: (requireContext()
                .getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager)
                ?.also { sensorManager = it }
            rotationSensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
            if (rotationSensor != null) {
                sm?.registerListener(rotationListener, rotationSensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
            } else {
                compassHeadingDeg = null  // no rotation-vector sensor → fall back to COG/north-up
            }
        } catch (e: Exception) { Log.w(TAG, "startCompass failed", e); compassHeadingDeg = null }
    }

    private fun stopCompass() {
        try { sensorManager?.unregisterListener(rotationListener) } catch (_: Exception) {}
        compassHeadingDeg = null
    }

    private fun ensureToneGenerator() {
        if (toneGenerator == null) {
            toneGenerator = try {
                android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80)
            } catch (e: Exception) { Log.w(TAG, "ToneGenerator init failed", e); null }
        }
    }

    private fun releaseToneGenerator() {
        try { toneGenerator?.release() } catch (_: Exception) {}
        toneGenerator = null
    }

    private fun haptic(arrived: Boolean) {
        val v = view ?: return
        v.performHapticFeedback(
            if (arrived) android.view.HapticFeedbackConstants.CONFIRM
            else android.view.HapticFeedbackConstants.CLOCK_TICK
        )
    }

    private fun beep(arrived: Boolean) {
        ensureToneGenerator()
        try {
            if (arrived) toneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 200)
            else toneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 80)
        } catch (e: Exception) { Log.w(TAG, "beep failed", e) }
    }

    /** Uses a per-view keep-screen-on request so it never clobbers a global window flag. */
    private fun setKeepScreenOn(on: Boolean) {
        try { view?.keepScreenOn = on } catch (e: Exception) { Log.w(TAG, "keepScreenOn failed", e) }
    }

    private fun updateStakeoutMarkerColor(distance: Double) {
        val hue = when {
            distance < STAKEOUT_GREEN_THRESHOLD -> BitmapDescriptorFactory.HUE_GREEN
            distance < STAKEOUT_AMBER_THRESHOLD -> BitmapDescriptorFactory.HUE_ORANGE
            else                                -> BitmapDescriptorFactory.HUE_RED
        }
        if (hue != lastStakeoutMarkerHue) {
            stakeoutMarker?.setIcon(BitmapDescriptorFactory.defaultMarker(hue))
            lastStakeoutMarkerHue = hue
        }
    }

    private fun updateStakeoutLine(from: LatLng, to: LatLng) {
        val map = googleMap ?: return
        val existing = stakeoutLine
        if (existing != null) {
            try { existing.points = listOf(from, to); return }
            catch (e: Exception) { Log.w(TAG, "stakeoutLine update failed, recreating", e); try { existing.remove() } catch (_: Exception) {}; stakeoutLine = null }
        }
        stakeoutLine = try {
            map.addPolyline(
                PolylineOptions().add(from, to).color(0xCCFF5722.toInt()).width(4f).geodesic(true)
            )
        } catch (_: Exception) { null }
    }

    // ── Current position marker ────────────────────────────────────────────────

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
                MarkerOptions().position(pos).title("Current Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(hue))
            )
            lastCurrentMarkerHue = hue
        } else {
            try {
                currentMarker?.position = pos
                if (hue != lastCurrentMarkerHue) {
                    currentMarker?.setIcon(BitmapDescriptorFactory.defaultMarker(hue))
                    lastCurrentMarkerHue = hue
                }
            } catch (e: Exception) { Log.w(TAG, "Update current marker position failed", e) }
        }
    }

    // ── Accuracy circle ────────────────────────────────────────────────────────

    private fun updateAccuracyCircle(position: LatLng, hAccM: Double?) {
        val map = googleMap ?: return
        val accuracy = hAccM?.takeIf { it > 0 && it < 1000 }
        if (accuracy == null) {
            accuracyCircle?.remove(); accuracyCircle = null
            return
        }
        val existing = accuracyCircle
        if (existing != null) {
            try { existing.center = position; existing.radius = accuracy; return }
            catch (e: Exception) { Log.w(TAG, "accuracyCircle update failed, recreating", e); existing.remove(); accuracyCircle = null }
        }
        try {
            accuracyCircle = map.addCircle(
                CircleOptions().center(position).radius(accuracy)
                    .strokeColor(0x880000FF.toInt()).fillColor(0x220000FF.toInt()).strokeWidth(2f)
            )
        } catch (e: Exception) { Log.w(TAG, "Failed to add accuracy circle", e) }
    }

    private fun clearAccuracyCircle() { accuracyCircle?.remove(); accuracyCircle = null }

    // ── Live trail ─────────────────────────────────────────────────────────────

    private fun updateLiveTrail(currentPos: LatLng, fix: Fix) {
        val map = googleMap ?: return
        if (!shouldAddTrailPoint(currentPos, fix)) return
        try {
            trailPoints.add(currentPos); lastTrailFix = fix
            if (trailPoints.size > MAX_TRAIL_POINTS) trailPoints.removeAt(0)
            if (trailPoints.size < 2) return
            val existing = liveTrail
            if (existing != null) {
                try { existing.points = trailPoints; return }
                catch (e: Exception) { Log.w(TAG, "liveTrail update failed, recreating", e); existing.remove(); liveTrail = null }
            }
            liveTrail = map.addPolyline(
                PolylineOptions().addAll(trailPoints).color(0xFFFF6B35.toInt()).width(4f).geodesic(true)
            )
        } catch (e: Exception) { Log.w(TAG, "updateLiveTrail error", e) }
    }

    private fun shouldAddTrailPoint(pos: LatLng, fix: Fix): Boolean {
        val lastFix = lastTrailFix ?: return true
        val lastPos = trailPoints.lastOrNull() ?: return true
        if (calculateDistance(lastPos, pos) >= MIN_DISTANCE_METERS) return true
        val cc = fix.courseDeg; val lc = lastFix.courseDeg
        if (cc != null && lc != null && calculateHeadingChange(lc, cc) >= MIN_HEADING_CHANGE_DEGREES) return true
        return false
    }

    private fun clearLiveTrail() { liveTrail?.remove(); liveTrail = null; trailPoints.clear(); lastTrailFix = null }

    // ── Data binding ───────────────────────────────────────────────────────────

    private fun bindData() {
        if (dataObserved) return
        dataObserved = true
        // CoordinatesViewModel is @HiltViewModel (it has an @Inject constructor), so it must be
        // created through Hilt's ViewModel factory. Passing AndroidViewModelFactory here bypasses
        // Hilt and crashes with NoSuchMethodException (<init>[]). Using the fragment's default
        // factory resolves the Hilt factory (this fragment is @AndroidEntryPoint), matching how
        // CoordinatesFragment / CoordinateDetailFragment obtain the same ViewModel.
        val vm = ViewModelProvider(this)[CoordinatesViewModel::class.java]
        vm.allCoordinates.observe(viewLifecycleOwner) { points ->
            if (googleMap == null) return@observe
            try {
                if (points.isEmpty()) {
                    markerMap.values.forEach { it.remove() }
                    markerMap.clear()
                    coordinateMap.clear()
                    placeholder?.visibility = View.VISIBLE
                    lastLatLngs = emptyList()
                    toggleAdapter.submit(emptyList())
                    updateVisibleCount()
                    return@observe
                }
                placeholder?.visibility = View.GONE

                // Remove markers for coordinates that were deleted
                val newIds = points.map { it.id }.toHashSet()
                val removedIds = markerMap.keys.filter { it !in newIds }
                removedIds.forEach { id -> markerMap.remove(id)?.remove(); coordinateMap.remove(id) }

                val latLngsVisible = ArrayList<LatLng>()
                val toggleItems = mutableListOf<CoordinateToggleItem>()

                points.forEach { p ->
                    val ll = LatLng(p.latitude, p.longitude)
                    val visible = visibilityMap.getOrPut(p.id) { true }
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
                            viewLifecycleOwner.lifecycleScope.launch {
                                val descriptor = buildMarkerDescriptor(p.icon, p.color)
                                if (descriptor != null) existingMarker.setIcon(descriptor)
                            }
                        }
                    } else {
                        val marker = (googleMap ?: return@forEach).addMarker(MarkerOptions().position(ll).title(p.name))
                        if (marker != null) {
                            marker.isVisible = visible
                            marker.tag = p.id
                            markerMap[p.id] = marker
                            viewLifecycleOwner.lifecycleScope.launch {
                                val descriptor = buildMarkerDescriptor(p.icon, p.color)
                                if (descriptor != null) marker.setIcon(descriptor)
                            }
                        }
                    }

                    coordinateMap[p.id] = p
                    if (visible) latLngsVisible.add(ll)
                    toggleItems += CoordinateToggleItem(
                        id = p.id, name = p.name, checked = visible,
                        icon = p.icon ?: "", color = p.color,
                        lat = p.latitude, lon = p.longitude
                    )
                }

                lastLatLngs = latLngsVisible
                toggleAdapter.submit(toggleItems)
                updateVisibleCount()
                updateCamera(latLngsVisible)
            } catch (e: Exception) {
                Log.e(TAG, "Error in data binding observer", e)
            }
        }
    }

    // ── Marker icon building ───────────────────────────────────────────────────

    private suspend fun buildMarkerDescriptor(iconName: String?, colorInt: Int): BitmapDescriptor? {
        val ctx = context ?: return null
        if (iconName.isNullOrBlank()) return null
        if (iconName.startsWith("model:")) {
            val modelId = iconName.removePrefix("model:")
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
                } catch (e: Exception) { Log.w(TAG, "Failed to load model thumbnail for marker", e); null }
            }
        }
        val cacheKey = "icon:$iconName:$colorInt"
        markerDescriptorCache[cacheKey]?.let { return it }
        @Suppress("DiscouragedApi")
        val resId = ctx.resources.getIdentifier(iconName, "drawable", ctx.packageName)
        if (resId == 0) return null
        val d = ContextCompat.getDrawable(ctx, resId) ?: return null
        val size = dpToPx(32f)
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
        val ctx = requireContext()
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

    // ── Camera ─────────────────────────────────────────────────────────────────

    private fun updateCamera(latLngs: List<LatLng>) {
        val map = googleMap ?: return
        if (latLngs.isEmpty()) return
        try {
            if (latLngs.size == 1) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 20f))
            } else if (!cameraInitialized) {
                val bounds = LatLngBounds.builder().also { b -> latLngs.forEach { b.include(it) } }.build()
                mapView?.post { try { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100)) } catch (_: Exception) {} }
            }
            cameraInitialized = true
        } catch (e: Exception) { Log.e(TAG, "Camera update error", e) }
    }

    private fun recenterMap() {
        val map = googleMap ?: return
        val live = lastFixLatLng
        if (live != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(live, 20f))
            return
        }
        val visiblePositions = markerMap.entries
            .filter { (id, _) -> visibilityMap[id] == true }
            .map { it.value.position }
        if (visiblePositions.isNotEmpty()) {
            updateCamera(visiblePositions)
        } else {
            showSnackbar("Current location unavailable")
        }
    }

    private fun resetMapOrientation() {
        val gMap = googleMap ?: return
        val current = gMap.cameraPosition
        gMap.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(current.target)
                    .zoom(current.zoom)
                    .bearing(0f)
                    .tilt(0f)
                    .build()
            )
        )
    }

    private fun fitVisibleCoordinates() {
        val visiblePositions = markerMap.entries
            .filter { (id, _) -> visibilityMap[id] == true }
            .map { it.value.position }
        if (visiblePositions.isEmpty()) {
            showSnackbar("No visible coordinates")
            return
        }
        if (visiblePositions.size == 1) {
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(visiblePositions.first(), 18f))
            return
        }
        val bounds = LatLngBounds.builder().also { b -> visiblePositions.forEach { b.include(it) } }.build()
        mapView?.post {
            try { googleMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80)) }
            catch (_: Exception) { googleMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0)) }
        }
    }

    // ── Map layers ─────────────────────────────────────────────────────────────

    private fun showLayersMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_map_layers, popup.menu)
        popup.menu.findItem(when (currentMapType) {
            GoogleMap.MAP_TYPE_SATELLITE -> R.id.map_type_satellite
            GoogleMap.MAP_TYPE_TERRAIN   -> R.id.map_type_terrain
            GoogleMap.MAP_TYPE_HYBRID    -> R.id.map_type_hybrid
            else                         -> R.id.map_type_normal
        })?.isChecked = true
        popup.setOnMenuItemClickListener { item ->
            currentMapType = when (item.itemId) {
                R.id.map_type_satellite -> GoogleMap.MAP_TYPE_SATELLITE
                R.id.map_type_terrain   -> GoogleMap.MAP_TYPE_TERRAIN
                R.id.map_type_hybrid    -> GoogleMap.MAP_TYPE_HYBRID
                else                    -> GoogleMap.MAP_TYPE_NORMAL
            }
            googleMap?.mapType = currentMapType
            googleMap?.let { MapThemeHelper.applyTheme(requireContext(), it, currentMapType) }
            isSatellite = currentMapType == GoogleMap.MAP_TYPE_HYBRID || currentMapType == GoogleMap.MAP_TYPE_SATELLITE
            true
        }
        popup.show()
    }

    // ── Coordinate visibility ──────────────────────────────────────────────────

    private fun showVisibilityMenu(anchor: View) {
        if (coordinateMap.isEmpty()) return
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Show all")
        popup.menu.add(0, 2, 1, "Hide all")
        selectedCoordinateId?.let { popup.menu.add(0, 3, 2, "Show selected only") }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showAllCoordinates()
                2 -> hideAllCoordinates()
                3 -> showSelectedOnly()
            }
            true
        }
        popup.show()
    }

    private fun showSelectedOnly() {
        val selId = selectedCoordinateId ?: return
        try {
            coordinateMap.keys.forEach { id ->
                val visible = id == selId
                visibilityMap[id] = visible
                try { markerMap[id]?.isVisible = visible } catch (_: Exception) {}
            }
            refreshToggleList()
            updateVisibleCount()
        } catch (e: Exception) { Log.w(TAG, "showSelectedOnly error", e) }
    }

    private fun showAllCoordinates() {
        try {
            coordinateMap.keys.forEach { id ->
                visibilityMap[id] = true
                try { markerMap[id]?.isVisible = true } catch (_: Exception) {}
            }
            refreshToggleList()
            updateVisibleCount()
        } catch (e: Exception) { Log.w(TAG, "showAllCoordinates error", e) }
    }

    private fun hideAllCoordinates() {
        try {
            coordinateMap.keys.forEach { id ->
                visibilityMap[id] = false
                try { markerMap[id]?.isVisible = false } catch (_: Exception) {}
            }
            refreshToggleList()
            updateVisibleCount()
            showSnackbar("All coordinates hidden")
        } catch (e: Exception) { Log.w(TAG, "hideAllCoordinates error", e) }
    }

    private fun refreshToggleList() {
        try {
            val toggleItems = coordinateMap.values.map { c ->
                CoordinateToggleItem(
                    id = c.id, name = c.name,
                    checked = visibilityMap[c.id] ?: true,
                    icon = c.icon ?: "", color = c.color,
                    lat = c.latitude, lon = c.longitude
                )
            }
            toggleAdapter.submit(toggleItems)
        } catch (e: Exception) { Log.w(TAG, "refreshToggleList error", e) }
    }

    private fun updateVisibleCount() {
        val visible = visibilityMap.values.count { it }
        val total = coordinateMap.size
        // Map-workspace style count, e.g. "12 shown / 18 total".
        leftPanelSubtitle?.text = if (total == 0) "No points yet" else "$visible shown / $total total"
    }

    // ── Selected coordinate ────────────────────────────────────────────────────

    private fun selectCoordinate(id: String) {
        val coord = coordinateMap[id] ?: return
        selectedCoordinateId = id
        toggleAdapter.setSelectedId(id)
        showSelectedCoordinateCard(coord)
        // Center map on selection, keeping zoom at least 16
        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(
            LatLng(coord.latitude, coord.longitude),
            (googleMap?.cameraPosition?.zoom ?: 18f).coerceAtLeast(16f)
        ))
    }

    private fun showSelectedCoordinateCard(coord: Coordinate) {
        selectedCoordinateCard?.visibility = View.VISIBLE
        txtSelectedName?.text = coord.name.ifBlank { "—" }
        txtSelectedSubtitle?.text = String.format(
            Locale.US, "%.6f, %.6f  ·  %.2f m",
            coord.latitude, coord.longitude, coord.altitude
        )
        loadCoordinateIconIntoView(coord, imgSelectedIcon)
    }

    private fun dismissSelectedCoordinate() {
        selectedCoordinateId = null
        selectedCoordinateCard?.visibility = View.GONE
        toggleAdapter.setSelectedId(null)
    }

    private fun loadCoordinateIconIntoView(coord: Coordinate, iv: ImageView?) {
        iv ?: return
        val iconName = coord.icon ?: ""
        if (iconName.startsWith("model:")) {
            iv.setImageResource(R.drawable.ic_pin)
            val modelId = iconName.removePrefix("model:")
            viewLifecycleOwner.lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        val path = modelRepository.getModelById(modelId)?.thumbnailFilePath ?: return@withContext null
                        BitmapFactory.decodeFile(path)
                    } catch (_: Exception) { null }
                }
                if (bmp != null) { iv.clearColorFilter(); iv.setImageBitmap(bmp) }
            }
        } else {
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

    // ── Stakeout ───────────────────────────────────────────────────────────────

    private fun startStakeoutForSelectedCoordinate() {
        val id = selectedCoordinateId ?: run { showSnackbar("Select a coordinate first"); return }
        val coord = coordinateMap[id] ?: return
        val latLng = LatLng(coord.latitude, coord.longitude)

        // Start stakeout mode if not already active
        if (!isStakeoutMode) {
            isStakeoutMode = true
            stakeoutPanel?.visibility = View.VISIBLE
        }

        stakeoutTarget = latLng
        txtStakeoutTarget?.text = coord.name
        showStakeoutWaiting()

        stakeoutMarker?.remove()
        lastStakeoutMarkerHue = null
        stakeoutMarker = googleMap?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Stakeout: ${coord.name}")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .zIndex(5f)   // keep the active target above the regular coordinate markers
        )
        lastStakeoutMarkerHue = BitmapDescriptorFactory.HUE_RED

        refreshStakeout()
        showSnackbar("Stakeout started: ${coord.name}")
        dismissSelectedCoordinate()
    }

    private fun stopStakeout() {
        if (!isStakeoutMode) return
        if (isGuidanceActive) stopGuidance()
        isStakeoutMode = false
        stakeoutPanel?.visibility = View.GONE
        stakeoutTarget = null
        txtStakeoutTarget?.text = "—"
        showStakeoutWaiting()
        stakeoutMarker?.remove(); stakeoutMarker = null; lastStakeoutMarkerHue = null
        stakeoutLine?.remove(); stakeoutLine = null
        showSnackbar("Stakeout stopped")
    }

    private fun toggleStakeoutMode() {
        if (isStakeoutMode) {
            stopStakeout()
        } else {
            isStakeoutMode = true
            stakeoutPanel?.visibility = View.VISIBLE
            stakeoutMarker?.remove(); stakeoutMarker = null
        }
    }


    private fun setupMapClickListener() {
        googleMap?.setOnMapClickListener { latLng ->
            if (isStakeoutMode) {
                try {
                    stakeoutTarget = latLng
                    txtStakeoutTarget?.text = String.format(Locale.US, "%.5f, %.5f", latLng.latitude, latLng.longitude)
                    if (stakeoutMarker == null) {
                        stakeoutMarker = googleMap?.addMarker(
                            MarkerOptions().position(latLng).title("Stakeout Target")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                                .zIndex(5f)   // keep the active target above the regular coordinate markers
                        )
                    } else {
                        try { stakeoutMarker?.position = latLng } catch (e: Exception) { Log.w(TAG, "stakeout marker move", e) }
                    }
                    refreshStakeout()
                } catch (e: Exception) { Log.w(TAG, "Map click stakeout error", e) }
            } else {
                dismissSelectedCoordinate()
            }
        }
    }

    // ── Panel interactions ─────────────────────────────────────────────────────

    private fun setupPanelInteractions() {
        collapseBtn?.setOnClickListener { collapsePanel() }
        expandBtn?.setOnClickListener { expandPanel() }

        // Swipe-to-collapse on the panel itself
        leftPanel?.setOnTouchListener { v, ev ->
            if (panelCollapsed) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    panelSwipeDownX = ev.rawX; panelSwipeDownTime = System.currentTimeMillis(); false
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - panelSwipeDownX
                    val dt = (System.currentTimeMillis() - panelSwipeDownTime).coerceAtLeast(1)
                    val vel = dx / dt.toFloat()
                    if (dx < -dpToPx(100f) || vel < -0.7f) { collapsePanel(); true }
                    else { if (abs(dx) < dpToPx(8f)) v.performClick(); false }
                }
                else -> false
            }
        }
    }

    /** Distance to slide the panel fully off-screen-left: its width + start margin + shadow. */
    private fun panelSlideDistance(): Float {
        val panel = leftPanel
        val w = if (panel != null && panel.width > 0) panel.width else panelWidthPx
        return (w + dpToPx(20f)).toFloat()
    }

    private fun collapsePanel() {
        if (panelCollapsed) return
        val panel = leftPanel ?: return
        panelCollapsed = true
        panel.animate().cancel()
        panel.animate()
            .translationX(-panelSlideDistance())
            .alpha(0f)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                panel.visibility = View.GONE
                expandBtn?.apply {
                    alpha = 0f
                    visibility = View.VISIBLE
                    animate().alpha(1f).setDuration(150).start()
                }
            }
            .start()
    }

    private fun expandPanel() {
        if (!panelCollapsed) return
        val panel = leftPanel ?: return
        panelCollapsed = false
        expandBtn?.visibility = View.GONE
        panel.animate().cancel()
        // Pre-position off-screen, then slide in — content stays laid out at full width.
        panel.translationX = -panelSlideDistance()
        panel.alpha = 0f
        panel.visibility = View.VISIBLE
        panel.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun applyPanelState() {
        val panel = leftPanel
        if (panelCollapsed) {
            panel?.visibility = View.GONE
            expandBtn?.visibility = View.VISIBLE
        } else {
            panel?.translationX = 0f
            panel?.alpha = 1f
            panel?.visibility = View.VISIBLE
            expandBtn?.visibility = View.GONE
        }
    }

    // ── Grid / Boundary (unchanged internal logic) ─────────────────────────────

    private fun toggleGrid() {
        showGrid = !showGrid
        if (showGrid) drawCoordinateGrid()
        else { gridLines.forEach { it.remove() }; gridLines.clear() }
    }

    private fun drawCoordinateGrid() {
        val map = googleMap ?: return
        try {
            val bounds = map.projection.visibleRegion.latLngBounds
            val latStep = calculateGridStep(bounds.northeast.latitude - bounds.southwest.latitude)
            var lat = (bounds.southwest.latitude / latStep).toInt() * latStep
            while (lat <= bounds.northeast.latitude) {
                try { gridLines.add(map.addPolyline(PolylineOptions()
                    .add(LatLng(lat, bounds.southwest.longitude), LatLng(lat, bounds.northeast.longitude))
                    .color(0x40000000).width(1f))) } catch (_: Exception) {}
                lat += latStep
            }
            val lngStep = calculateGridStep(bounds.northeast.longitude - bounds.southwest.longitude)
            var lng = (bounds.southwest.longitude / lngStep).toInt() * lngStep
            while (lng <= bounds.northeast.longitude) {
                try { gridLines.add(map.addPolyline(PolylineOptions()
                    .add(LatLng(bounds.southwest.latitude, lng), LatLng(bounds.northeast.latitude, lng))
                    .color(0x40000000).width(1f))) } catch (_: Exception) {}
                lng += lngStep
            }
        } catch (e: Exception) { Log.w(TAG, "drawCoordinateGrid error", e) }
    }

    private fun calculateGridStep(range: Double): Double = when {
        range > 1.0 -> 0.1; range > 0.1 -> 0.01; range > 0.01 -> 0.001; else -> 0.0001
    }

    private fun toggleBoundaryLines() {
        showBoundaries = !showBoundaries
        if (showBoundaries) drawBoundaryLines()
        else { boundaryLines.forEach { it.remove() }; boundaryLines.clear() }
    }

    private fun drawBoundaryLines() {
        try {
            val coords = coordinateMap.values.filter { visibilityMap[it.id] == true }.sortedBy { it.timestamp }
            if (coords.size < 2) return
            for (i in 0 until coords.size - 1) {
                val s = LatLng(coords[i].latitude, coords[i].longitude)
                val e = LatLng(coords[i + 1].latitude, coords[i + 1].longitude)
                try { googleMap?.addPolyline(PolylineOptions().add(s, e).color(0xFF2196F3.toInt()).width(3f).geodesic(true))
                    ?.let { boundaryLines.add(it) } } catch (_: Exception) {}
            }
            if (coords.size >= 3) {
                val s = LatLng(coords.last().latitude, coords.last().longitude)
                val e = LatLng(coords.first().latitude, coords.first().longitude)
                try { googleMap?.addPolyline(PolylineOptions().add(s, e).color(0xFF2196F3.toInt()).width(3f).geodesic(true)
                    .pattern(listOf(Dash(20f), Gap(10f))))?.let { boundaryLines.add(it) } } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.w(TAG, "drawBoundaryLines error", e) }
    }

    // ── Info window ────────────────────────────────────────────────────────────

    private fun createInfoWindowView(marker: Marker): View? {
        return try {
            val coordinateId = marker.tag as? String
            val coordinate = coordinateId?.let { coordinateMap[it] }
            @Suppress("InflateParams")
            val view = layoutInflater.inflate(R.layout.custom_info_window, null)
            view.findViewById<TextView>(R.id.info_window_title).text =
                coordinate?.name ?: marker.title ?: "Unknown"
            view.findViewById<TextView>(R.id.info_window_content).text = buildString {
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
        } catch (e: Exception) { Log.w(TAG, "createInfoWindowView error", e); null }
    }

    // ── Snackbar ───────────────────────────────────────────────────────────────

    private fun showSnackbar(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
    }

    // ── Math helpers ───────────────────────────────────────────────────────────

    private fun calculateDistance(pos1: LatLng, pos2: LatLng): Double {
        val R = 6371000.0
        val lat1 = Math.toRadians(pos1.latitude); val lat2 = Math.toRadians(pos2.latitude)
        val dLat = Math.toRadians(pos2.latitude - pos1.latitude)
        val dLon = Math.toRadians(pos2.longitude - pos1.longitude)
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun calculateHeadingChange(h1: Double, h2: Double): Double {
        val diff = abs(h2 - h1); return min(diff, 360.0 - diff)
    }

    private fun calculateBearing(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.latitude); val lon1 = Math.toRadians(from.longitude)
        val lat2 = Math.toRadians(to.latitude);   val lon2 = Math.toRadians(to.longitude)
        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    @Suppress("unused")
    private fun getCurrentCoordinateFormat(): String = "decimal"
}
