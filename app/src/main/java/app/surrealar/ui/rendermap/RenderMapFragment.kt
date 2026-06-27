package app.surrealar.ui.rendermap

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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.surrealar.R
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.displayIconKey
import app.surrealar.domain.model.hasLinkedModel
import app.surrealar.domain.model.linkedModelId
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.ui.map.MapThemeHelper
import app.surrealar.ui.viewpoints.CoordinatesViewModel
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import app.surrealar.util.UtmConverter
import app.surrealar.util.diagnostics.MapRuntimeDiagnostics
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The full survey map screen: draws saved coordinates, the meter grid, point labels, visibility
 * toggles, and the info bottom sheet. The grid/label math lives in pure helpers (`MapGrid`,
 * `PointLabel`); camera/UI state is retained by `MapUiStateViewModel`.
 */
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
        // Guidance falls back to the "waiting" state if no live fix arrives within this window.
        private const val STAKEOUT_STALE_FIX_MS = 5000L
        private const val STAKEOUT_STALE_TICK_MS = 1500L
        // Point-label safeguards: cap labelled points, hide at low zoom, throttle distance refreshes.
        private const val LABEL_MAX_COUNT = 60
        private const val LABEL_MIN_ZOOM = 14f
        private const val LABEL_DISTANCE_THROTTLE_MS = 2000L
    }

    @Inject
    lateinit var fixSwitchboard: FixSwitchboard

    @Inject
    lateinit var sourceSettings: app.surrealar.gnss.source.SourceSettings

    @Inject
    lateinit var modelRepository: app.surrealar.domain.repository.ModelRepository

    @Inject
    lateinit var stakeoutSettingsRepo: app.surrealar.domain.repository.StakeoutSettingsRepository

    @Inject
    lateinit var mapSettingsRepo: app.surrealar.domain.repository.MapSettingsRepository

    /**
     * Transient map UI state, scoped to the ACTIVITY so it survives bottom-nav tab switches
     * (Home ⇄ Map recreate the fragment) as well as forward navigation (Details/AR) and config
     * changes. Cleared only when the activity finishes — i.e. session-only, not across app restart.
     */
    private val mapUiVm: MapUiStateViewModel by activityViewModels()

    // Map
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var placeholder: View? = null
    private var lastLatLngs: List<LatLng> = emptyList()
    private var isSatellite = false
    private var currentMapType = GoogleMap.MAP_TYPE_NORMAL
    private var mapTypeRow: View? = null
    private var txtMapType: TextView? = null
    // Cycle order (no MAP_TYPE_NONE): Normal → Satellite → Hybrid → Terrain → Normal.
    private val mapTypeCycle = intArrayOf(
        GoogleMap.MAP_TYPE_NORMAL,
        GoogleMap.MAP_TYPE_SATELLITE,
        GoogleMap.MAP_TYPE_HYBRID,
        GoogleMap.MAP_TYPE_TERRAIN,
    )
    private var dataObserved = false
    private var cameraInitialized = false

    // Panel
    private var leftPanel: View? = null
    private var collapseBtn: View? = null   // ImageButton in new layout, typed as View
    private var expandBtn: View? = null     // collapsed rail (LinearLayout), typed as View
    private var collapsedCount: TextView? = null   // point count badge on the collapsed rail
    private var leftPanelSubtitle: TextView? = null
    private var visibleCountText: TextView? = null
    private var pointSearchLayout: View? = null
    private var pointSearchEdit: android.widget.EditText? = null
    private var emptyTitle: TextView? = null
    private var emptySubtitle: TextView? = null
    // Drawer list filtering (display-only: filters the list, never marker visibility).
    private var allToggleItems: List<CoordinateToggleItem> = emptyList()
    private var pointSearchQuery: String = ""
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
    // Survey meter grid: cycles Off → Auto → Fine → Coarse. Fragment-only state (not persisted).
    private var gridMode = MapGridMode.OFF
    private var currentGridSpacingM: Double? = null
    private var mapGridRow: View? = null
    private var txtMapGrid: TextView? = null
    private val gridLines = mutableListOf<Polyline>()

    // Point labels (separate marker layer; glyph markers/clicks untouched). Session-only via MapUiState.
    private var pointLabelMode = PointLabelMode.OFF
    private var mapLabelsRow: View? = null
    private var txtMapLabels: TextView? = null
    private val labelMarkers = mutableMapOf<String, Marker>()
    private val labelDescriptorCache = mutableMapOf<String, BitmapDescriptor>()
    private var lastDistanceLabelMs = 0L
    private val boundaryLines = mutableListOf<Polyline>()
    private var showBoundaries = false

    // Live tracking
    private var currentMarker: Marker? = null
    // Map-display-only toggle for the live-location overlays. Does NOT affect fix collection,
    // recenter, or stakeout — those use the latest fix (lastFixLatLng / currentFix) directly.
    // TODO: persist via a future map-display settings model; fragment-only state for now.
    private var showCurrentLocationOnMap = true
    private var myLocationRow: View? = null
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
    private var stakeoutSettings = app.surrealar.settings.model.StakeoutSettings()
    private var isGuidanceActive = false
    /** Coordinate id backing the active target (null for a free map-tap target). */
    private var stakeoutTargetId: String? = null
    /** elapsedRealtime() of the last fix applied to the stakeout readout; null until the first. */
    private var lastStakeoutFixElapsedMs: Long? = null
    private var guidanceStaleJob: kotlinx.coroutines.Job? = null
    private val feedbackGate = app.surrealar.stakeout.StakeoutFeedbackGate()
    private var compassHeadingDeg: Double? = null
    private var sensorManager: android.hardware.SensorManager? = null
    private var rotationSensor: android.hardware.Sensor? = null
    private var compassRegistered = false
    private var fragmentResumed = false
    private var toneGenerator: android.media.ToneGenerator? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    // Current-location heading indicator (a flat, rotating arrow shown under the My Location marker).
    private var headingMarker: Marker? = null
    private var headingDescriptor: BitmapDescriptor? = null
    private var lastAppliedHeadingDeg: Double? = null
    private val rotationListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            if (event.sensor.type != android.hardware.Sensor.TYPE_ROTATION_VECTOR) return
            android.hardware.SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            android.hardware.SensorManager.getOrientation(rotationMatrix, orientationAngles)
            compassHeadingDeg =
                app.surrealar.stakeout.StakeoutGuidance.normalize360(Math.toDegrees(orientationAngles[0].toDouble()))
            // Keep the on-map heading arrow following the compass between fixes (throttled). Delivered
            // on the main thread, so touching the map here is safe.
            updateHeadingIndicator()
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    // Selected coordinate card
    private var selectedCoordinateId: String? = null
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
    private var btnSelectedClose: View? = null
    private var btnSelectedCenter: View? = null
    private var btnSelectedStakeout: View? = null
    private var btnSelectedAr: View? = null
    private var btnSelectedDetails: View? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_render_map, container, false)

        // On the FIRST map open of a session, seed the session state from the durable MapSettings
        // defaults (default map type/grid/labels, show-my-location, tools/drawer). After that, the
        // session ViewModel preserves the user's in-session choices across navigation. The DataStore
        // read is fast/cached; SurRealApplication uses the same runBlocking pattern for the startup theme.
        if (!mapUiVm.seededFromDefaults) {
            runCatching { kotlinx.coroutines.runBlocking { mapSettingsRepo.mapSettings.first() } }
                .getOrNull()?.let { mapUiVm.seedFromDefaults(it) }
        }

        // Restore transient map UI state into the working fields BEFORE views/map are wired, so the
        // toolbar labels, map type, grid, and My Location come back as the user left them. The map
        // type/grid/camera are applied in onMapReady; the Map Tools panel is restored once laid out.
        mapUiVm.state.let { s ->
            currentMapType = s.mapType
            gridMode = s.gridMode
            pointLabelMode = s.pointLabelMode
            showCurrentLocationOnMap = s.showCurrentLocation
            mapToolsVisible = s.isMapToolsOpen
            panelCollapsed = s.isLeftPanelCollapsed
            selectedCoordinateId = s.selectedCoordinateId
        }

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
        txtSelectedCoords = root.findViewById(R.id.txt_selected_coords)
        txtSelectedElevation = root.findViewById(R.id.txt_selected_elevation)
        layoutSelectedLive = root.findViewById(R.id.layout_selected_live)
        txtSelectedDistance = root.findViewById(R.id.txt_selected_distance)
        txtSelectedBearing = root.findViewById(R.id.txt_selected_bearing)
        layoutSelectedCapture = root.findViewById(R.id.layout_selected_capture)
        txtSelectedSource = root.findViewById(R.id.txt_selected_source)
        txtSelectedAccuracy = root.findViewById(R.id.txt_selected_accuracy)
        btnSelectedClose = root.findViewById(R.id.btn_selected_close)
        btnSelectedCenter = root.findViewById(R.id.btn_selected_center)
        btnSelectedStakeout = root.findViewById(R.id.btn_selected_stakeout)
        btnSelectedAr = root.findViewById(R.id.btn_selected_ar)
        btnSelectedDetails = root.findViewById(R.id.btn_selected_details)

        // Map Tools collapsible overlay (toggle + panel)
        mapToolsToggle = root.findViewById(R.id.btnMapToolsToggle)
        mapToolsPanel = root.findViewById(R.id.layoutMapTools)

        toggleAdapter = CoordinateToggleAdapter(
            modelRepository = modelRepository,
            onToggle = { id, checked ->
                visibilityMap[id] = checked
                markerMap[id]?.isVisible = checked
                updateVisibleCount()
                applyPointLabels()   // hide/show this point's label with its marker
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
            // Restore the saved camera if returning to the map; else the default view (the saved-coord
            // auto-fit in onMapLoaded only runs when no camera was restored, via cameraInitialized).
            val savedCam = mapUiVm.state.camera
            if (savedCam != null) {
                map.moveCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(savedCam.lat, savedCam.lng))
                        .zoom(savedCam.zoom).bearing(savedCam.bearing).tilt(savedCam.tilt).build()
                ))
                cameraInitialized = true
            } else {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.7963, -77.8570), 15f))
            }
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
                app.surrealar.util.diagnostics.MapRuntimeDiagnostics.update { it.copy(mapLoaded = true) }
                logMapEvent("MapLifecycle", "map loaded (tiles drawn)")
            }
            // Redraw the meter grid when the camera settles so Auto spacing adapts to zoom/pan.
            // Idle fires only after movement stops (not continuously), so this stays cheap.
            map.setOnCameraIdleListener {
                saveCameraState()
                if (gridMode != MapGridMode.OFF) redrawGrid()
                if (pointLabelMode != PointLabelMode.OFF) applyPointLabels()   // zoom-gate + reconcile
            }
            redrawGrid()   // restore the grid if a mode was active before view recreation
            bindData()
            startFixCollection()
            setupMapClickListener()
            app.surrealar.util.diagnostics.MapRuntimeDiagnostics.update {
                it.copy(cameraRestored = savedCam != null)
            }
            logMapEvent("MapLifecycle", "map ready type=${mapTypeLabel(currentMapType)} cameraRestored=${savedCam != null}")
        }

        // Map Tools overlay wiring. The toggle slides the panel in/out (AR pattern); each row
        // delegates to the SAME existing map action handlers — only the presentation changed.
        mapToolsToggle?.setOnClickListener { toggleMapTools() }
        restoreMapToolsState()   // re-open/close just as the user left it before navigating away
        root.findViewById<View>(R.id.btnMapCenter)?.setOnClickListener { recenterMap() }
        visibilityMenuBtn?.setOnClickListener { showVisibilityMenu(it) }
        mapTypeRow = root.findViewById(R.id.btnMapType)
        txtMapType = root.findViewById(R.id.txt_map_type)
        mapTypeRow?.setOnClickListener { cycleMapType() }
        updateMapTypeButton()
        mapGridRow = root.findViewById(R.id.btnMapGrid)
        txtMapGrid = root.findViewById(R.id.txt_map_grid)
        mapGridRow?.setOnClickListener { cycleGrid() }
        updateGridButton()
        mapLabelsRow = root.findViewById(R.id.btnMapLabels)
        txtMapLabels = root.findViewById(R.id.txt_map_labels)
        mapLabelsRow?.setOnClickListener { cycleLabels() }
        updateLabelsButton()
        root.findViewById<View>(R.id.btnMapZoomIn)?.setOnClickListener {
            googleMap?.animateCamera(CameraUpdateFactory.zoomIn())
        }
        root.findViewById<View>(R.id.btnMapZoomOut)?.setOnClickListener {
            googleMap?.animateCamera(CameraUpdateFactory.zoomOut())
        }
        root.findViewById<View>(R.id.btnMapFit)?.setOnClickListener { fitVisibleCoordinates() }
        myLocationRow = root.findViewById(R.id.btnMapMyLocation)
        myLocationRow?.setOnClickListener { toggleMyLocationVisibility() }
        updateMyLocationRowState()

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
        btnSelectedAr?.setOnClickListener { openSelectedInAr() }
        btnSelectedDetails?.setOnClickListener { openSelectedDetails() }

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
        collapsedCount = view.findViewById(R.id.text_collapsed_count)
        visibleCountText = view.findViewById(R.id.text_visible_count)
        pointSearchLayout = view.findViewById(R.id.layout_point_search)
        pointSearchEdit = view.findViewById(R.id.edit_point_search)
        emptyTitle = view.findViewById(R.id.left_panel_placeholder)
        emptySubtitle = view.findViewById(R.id.left_panel_placeholder_sub)
        view.findViewById<View>(R.id.btn_show_all)?.setOnClickListener { showAllCoordinates() }
        view.findViewById<View>(R.id.btn_hide_all)?.setOnClickListener { hideAllCoordinates() }
        pointSearchEdit?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                pointSearchQuery = s?.toString()?.trim().orEmpty()
                applyDrawerFilter()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

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

        // Keep the latest stakeout settings in memory for the guidance loop, and apply changes to
        // running guidance immediately (e.g. toggling audio/compass/keep-screen-on mid-stakeout).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                stakeoutSettingsRepo.stakeoutSettings.collect {
                    stakeoutSettings = it
                    reconcileGuidanceResources()
                }
            }
        }

        if (panelWidthPx == 0) panelWidthPx = dpToPx(288f)
        applyPanelState()
        setupPanelInteractions()
        startProviderObservation()
    }

    // ── Lifecycle pass-throughs ────────────────────────────────────────────────

    override fun onStart()  { super.onStart();  try { mapView?.onStart() } catch (e: Exception) { Log.e(TAG, "onStart", e) } }
    override fun onResume() {
        super.onResume()
        try { mapView?.onResume(); fragmentResumed = true; resumeGuidanceFeedback(); syncCompass() }
        catch (e: Exception) { Log.e(TAG, "onResume", e) }
    }
    override fun onPause() {
        try { saveCameraState(); mapView?.onPause(); fragmentResumed = false; pauseGuidanceFeedback(); syncCompass() }
        catch (e: Exception) { Log.e(TAG, "onPause", e) }
        super.onPause()
    }

    override fun onStop() {
        try { clearLiveTrail(); clearAccuracyCircle(); mapView?.onStop() } catch (e: Exception) { Log.e(TAG, "onStop", e) }
        super.onStop()
    }

    override fun onDestroyView() {
        try {
            stopGuidance()   // release tone, clear keep-screen-on
            fragmentResumed = false
            unregisterCompass()   // make sure the shared sensor is released with the view
            clearLiveTrail(); clearAccuracyCircle(); clearGrid(); removeAllPointLabels()
            labelDescriptorCache.clear()
            currentMarker?.remove(); currentMarker = null
            headingMarker?.remove(); headingMarker = null; lastAppliedHeadingDeg = null
            stakeoutLine?.remove(); stakeoutLine = null
        } catch (e: Exception) { Log.e(TAG, "onDestroyView cleanup", e) }
        markerDescriptorCache.clear()
        lastCurrentMarkerHue = null
        lastStakeoutMarkerHue = null
        // The map + its markers are destroyed with the view. Drop the stale marker references and the
        // observe-guard so that when this fragment's view is recreated (e.g. returning from the point
        // Details screen), bindData() re-attaches the coordinate observer and rebuilds markers on the
        // fresh GoogleMap instead of leaving the panel stuck on "Loading…". visibilityMap (the user's
        // show/hide choices, keyed by id) is intentionally preserved across recreation.
        markerMap.clear()
        coordinateMap.clear()
        lastLatLngs = emptyList()
        dataObserved = false
        try { mapView?.onDestroy() } catch (e: Exception) { Log.e(TAG, "mapView.onDestroy", e) }
        mapView = null; placeholder = null
        super.onDestroyView()
    }

    // ── GNSS fix collection ────────────────────────────────────────────────────

    private fun startProviderObservation() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previousProvider: app.surrealar.gnss.source.SourceSettings.ProviderChoice? = null
                sourceSettings.activeProvider.collect { provider ->
                    if (previousProvider != null && previousProvider != provider) {
                        clearLiveTrail()
                        clearAccuracyCircle()
                        currentMarker?.remove(); currentMarker = null
                        removeHeadingIndicator()
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
            lastFixLatLng = pos   // always tracked — recenter/stakeout use this regardless of display toggle
            // The current-location overlays are display-only; the toggle hides them without affecting
            // fix collection, recenter, or stakeout (which run below/elsewhere off the latest fix).
            if (showCurrentLocationOnMap) {
                try { updateCurrentMarker(fix.latDeg, fix.lonDeg, fix.rtkStatus) } catch (e: Exception) { Log.w(TAG, "updateCurrentMarker", e) }
                try { updateAccuracyCircle(pos, fix.hAccM) } catch (e: Exception) { Log.w(TAG, "updateAccuracyCircle", e) }
                try { updateLiveTrail(pos, fix) } catch (e: Exception) { Log.w(TAG, "updateLiveTrail", e) }
                try { updateHeadingIndicator() } catch (e: Exception) { Log.w(TAG, "updateHeadingIndicator", e) }
            }
            try { updateStakeoutCalculations(pos) } catch (e: Exception) { Log.w(TAG, "updateStakeoutCalculations", e) }
            // Distance labels follow the live position, throttled to avoid heavy marker rebuilds.
            if (pointLabelMode == PointLabelMode.DISTANCE) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastDistanceLabelMs >= LABEL_DISTANCE_THROTTLE_MS) {
                    lastDistanceLabelMs = now
                    try { applyPointLabels() } catch (e: Exception) { Log.w(TAG, "applyPointLabels", e) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateLiveTracking error", e)
        }
    }

    // ── Map Tools overlay (collapsible, AR-style) ──────────────────────────────

    /** Slides the Map Tools panel in/out, mirroring the AR floating-toolbar toggle. */
    private fun toggleMapTools() {
        setMapToolsOpen(!mapToolsVisible, animate = true)
    }

    /** Applies the open/closed state to the panel + toggle, persisting it to the session ViewModel. */
    private fun setMapToolsOpen(open: Boolean, animate: Boolean) {
        val panel = mapToolsPanel ?: return
        mapToolsVisible = open
        mapUiVm.update { it.copy(isMapToolsOpen = open) }
        if (animate) logMapEvent("MapControls", "map tools=${if (open) "open" else "closed"}")
        if (open) {
            mapToolsToggle?.contentDescription = "Hide map tools"
            if (animate) {
                panel.translationX = panel.width.toFloat().coerceAtLeast(200f)
                panel.alpha = 0f
                panel.visibility = View.VISIBLE
                panel.animate().translationX(0f).alpha(1f).setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                mapToolsToggle?.animate()?.rotation(90f)?.setDuration(200)?.start()
            } else {
                panel.translationX = 0f; panel.alpha = 1f; panel.visibility = View.VISIBLE
                mapToolsToggle?.rotation = 90f
            }
        } else {
            mapToolsToggle?.contentDescription = "Show map tools"
            if (animate) {
                panel.animate().translationX(panel.width.toFloat().coerceAtLeast(200f)).alpha(0f)
                    .setDuration(160).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { panel.visibility = View.GONE }.start()
                mapToolsToggle?.animate()?.rotation(0f)?.setDuration(160)?.start()
            } else {
                panel.visibility = View.GONE
                mapToolsToggle?.rotation = 0f
            }
        }
    }

    /** Re-applies the saved Map Tools open/closed state to the freshly created views (no animation). */
    private fun restoreMapToolsState() {
        val panel = mapToolsPanel ?: return
        // Defer until the panel has been measured so a "closed" state lays out correctly.
        panel.post { setMapToolsOpen(mapToolsVisible, animate = false) }
    }

    // ── Stakeout calculations ──────────────────────────────────────────────────

    private fun updateStakeoutCalculations(currentPos: LatLng) {
        val target = stakeoutTarget ?: return
        if (!isStakeoutMode) return
        lastStakeoutFixElapsedMs = android.os.SystemClock.elapsedRealtime()
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
        syncCompass()
        if (stakeoutSettings.enableAudio) ensureToneGenerator()
        if (stakeoutSettings.keepScreenOnDuringStakeout) setKeepScreenOn(true)
        startStaleWatcher()
        refreshStakeout()
        logMapEvent("MapStakeout", "guidance started")
    }

    private fun stopGuidance() {
        isGuidanceActive = false
        btnStakeoutGuidance?.text = "Start Guidance"
        imgStakeoutArrow?.visibility = View.GONE
        txtStakeoutTolerance?.visibility = View.GONE
        txtStakeoutHeadingNote?.visibility = View.GONE
        guidanceStaleJob?.cancel(); guidanceStaleJob = null
        syncCompass()   // My Location may still need the compass even after guidance stops
        releaseToneGenerator()
        setKeepScreenOn(false)
        feedbackGate.reset()
        logMapEvent("MapStakeout", "guidance stopped")
    }

    /**
     * Ticks while guidance is active; if no fresh fix arrives within [STAKEOUT_STALE_FIX_MS] the
     * readout drops to the waiting state and the feedback latch resets, so a frozen arrow never
     * looks live and re-acquiring position fires feedback cleanly. Lifecycle-scoped → dies with the view.
     */
    private fun startStaleWatcher() {
        guidanceStaleJob?.cancel()
        guidanceStaleJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isGuidanceActive) {
                val stale = app.surrealar.stakeout.StakeoutGuidance.isPositionStale(
                    lastStakeoutFixElapsedMs, android.os.SystemClock.elapsedRealtime(), STAKEOUT_STALE_FIX_MS
                )
                if (stale) {
                    txtStakeoutStatus?.text = "Waiting for live position…"
                    txtStakeoutHeadingNote?.visibility = View.GONE
                    feedbackGate.reset()
                }
                kotlinx.coroutines.delay(STAKEOUT_STALE_TICK_MS)
            }
        }
    }

    /** Applies live settings changes to running guidance resources (sensor/audio/screen). */
    private fun reconcileGuidanceResources() {
        if (!isGuidanceActive) return
        syncCompass()
        if (!stakeoutSettings.enableAudio) releaseToneGenerator()
        setKeepScreenOn(stakeoutSettings.keepScreenOnDuringStakeout)
        txtStakeoutTolerance?.text =
            String.format(Locale.getDefault(), "Tolerance: %.2f m", stakeoutSettings.toleranceMeters)
    }

    /** Pause feedback (audio/screen/ticker) but keep the active target — for backgrounding. Compass
     *  is handled by [onPause]/[syncCompass] since My Location may also be using it. */
    private fun pauseGuidanceFeedback() {
        if (!isGuidanceActive) return
        guidanceStaleJob?.cancel(); guidanceStaleJob = null
        releaseToneGenerator()
        setKeepScreenOn(false)
        feedbackGate.reset()   // returning to the map shouldn't replay a stale arrival cue
    }

    private fun resumeGuidanceFeedback() {
        if (!isGuidanceActive) return
        if (stakeoutSettings.enableAudio) ensureToneGenerator()
        if (stakeoutSettings.keepScreenOnDuringStakeout) setKeepScreenOn(true)
        startStaleWatcher()
    }

    /** Updates the arrow + fires throttled haptics/audio. Uses the passed-in distance/bearing — no new math. */
    private fun updateGuidance(distanceMeters: Double, bearingToTargetDeg: Double) {
        if (!isGuidanceActive) return
        val s = stakeoutSettings
        val (source, heading) = app.surrealar.stakeout.StakeoutGuidance.resolveHeading(
            preferCompass = s.guidanceUsesCompassHeading,
            compassHeadingDeg = compassHeadingDeg,
            courseOverGroundDeg = currentFix?.courseDeg,
            speedMps = currentFix?.speedMps,
        )
        val relative = app.surrealar.stakeout.StakeoutGuidance.relativeBearing(bearingToTargetDeg, heading)
        // Arrow shows the relative direction when heading is known, else points at the map-north bearing.
        imgStakeoutArrow?.rotation = (relative ?: bearingToTargetDeg).toFloat()
        if (source == app.surrealar.stakeout.HeadingSource.NORTH_UP) {
            txtStakeoutHeadingNote?.visibility = View.VISIBLE
            txtStakeoutHeadingNote?.text = "Arrow is north-up until heading is available."
        } else {
            txtStakeoutHeadingNote?.visibility = View.GONE
        }
        val status = app.surrealar.stakeout.StakeoutGuidance.status(
            hasTarget = true, hasPosition = true, distanceMeters = distanceMeters, toleranceMeters = s.toleranceMeters
        )
        when (feedbackGate.onUpdate(status, android.os.SystemClock.elapsedRealtime())) {
            app.surrealar.stakeout.StakeoutFeedback.ENTERED_TOLERANCE -> {
                if (s.enableHaptics) haptic(arrived = true); if (s.enableAudio) beep(arrived = true)
            }
            app.surrealar.stakeout.StakeoutFeedback.NAVIGATING_PULSE -> {
                if (s.enableHaptics) haptic(arrived = false); if (s.enableAudio) beep(arrived = false)
            }
            app.surrealar.stakeout.StakeoutFeedback.NONE -> {}
        }
    }

    /**
     * Single source of compass truth shared by stakeout guidance AND the My Location heading arrow.
     * Registers exactly ONE rotation-vector listener when any feature needs it and the fragment is
     * resumed; unregisters when nothing needs it. Idempotent — safe to call from any of the callers.
     */
    private fun syncCompass() {
        val guidanceWants = isGuidanceActive && stakeoutSettings.guidanceUsesCompassHeading
        val myLocationWants = showCurrentLocationOnMap
        val needed = fragmentResumed && (guidanceWants || myLocationWants)
        if (needed) registerCompass() else unregisterCompass()
    }

    private fun registerCompass() {
        if (compassRegistered) return
        try {
            val sm = sensorManager ?: (requireContext()
                .getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager)
                ?.also { sensorManager = it }
            rotationSensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
            if (rotationSensor != null) {
                sm?.registerListener(rotationListener, rotationSensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
                compassRegistered = true
            } else {
                compassHeadingDeg = null  // no rotation-vector sensor → callers fall back to COG/north-up
            }
        } catch (e: Exception) { Log.w(TAG, "registerCompass failed", e); compassHeadingDeg = null }
    }

    private fun unregisterCompass() {
        if (compassRegistered) {
            try { sensorManager?.unregisterListener(rotationListener) } catch (_: Exception) {}
            compassRegistered = false
        }
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
                    allToggleItems = emptyList()
                    pointSearchLayout?.visibility = View.GONE
                    showEmptyState(searching = false)
                    lastLatLngs = emptyList()
                    toggleAdapter.submit(emptyList())
                    updateVisibleCount()
                    // A coordinate-backed stakeout target no longer exists — end stakeout cleanly.
                    if (isStakeoutMode && stakeoutTargetId != null) {
                        showSnackbar("Stakeout target was removed"); stopStakeout()
                    }
                    return@observe
                }
                placeholder?.visibility = View.GONE

                // Remove markers for coordinates that were deleted
                val newIds = points.map { it.id }.toHashSet()
                val removedIds = markerMap.keys.filter { it !in newIds }
                removedIds.forEach { id -> markerMap.remove(id)?.remove(); coordinateMap.remove(id) }
                // If the active stakeout target was the deleted coordinate, end stakeout cleanly.
                if (isStakeoutMode && stakeoutTargetId != null && stakeoutTargetId in removedIds) {
                    showSnackbar("Stakeout target was removed"); stopStakeout()
                }

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
                                val descriptor = buildMarkerDescriptor(p.linkedModelId, p.displayIconKey, p.color)
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
                                val descriptor = buildMarkerDescriptor(p.linkedModelId, p.displayIconKey, p.color)
                                if (descriptor != null) marker.setIcon(descriptor)
                            }
                        }
                    }

                    coordinateMap[p.id] = p
                    if (visible) latLngsVisible.add(ll)
                    toggleItems += CoordinateToggleItem(
                        id = p.id, name = p.name, checked = visible,
                        icon = p.displayIconKey ?: "", modelId = p.linkedModelId, color = p.color,
                        lat = p.latitude, lon = p.longitude,
                        meta = buildRowMeta(p)
                    )
                }

                lastLatLngs = latLngsVisible
                allToggleItems = toggleItems
                // Show search once the list gets long enough to warrant filtering.
                pointSearchLayout?.visibility = if (toggleItems.size > 8) View.VISIBLE else View.GONE
                applyDrawerFilter()
                updateVisibleCount()
                updateCamera(latLngsVisible)
                // Re-show the selected-point card once coordinates are loaded (e.g. after returning
                // from details). Clears the selection if that coordinate no longer exists.
                restoreSelectedCard()
                applyPointLabels()
                logMapEvent("MapMarkers", "markersLoaded total=${points.size} visible=${latLngsVisible.size} hidden=${points.size - latLngsVisible.size}")
            } catch (e: Exception) {
                MapRuntimeDiagnostics.update { it.copy(lastError = "observer: ${e.javaClass.simpleName}: ${e.message}") }
                Log.e(TAG, "Error in data binding observer", e)
            }
        }
    }

    // ── Marker icon building ───────────────────────────────────────────────────

    private suspend fun buildMarkerDescriptor(modelId: String?, iconKey: String?, colorInt: Int): BitmapDescriptor? {
        val ctx = context ?: return null
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
                } catch (e: Exception) { Log.w(TAG, "Failed to load model thumbnail for marker", e); null }
            }
        }
        if (iconKey.isNullOrBlank()) return null
        val cacheKey = "icon:$iconKey:$colorInt"
        markerDescriptorCache[cacheKey]?.let { return it }
        @Suppress("DiscouragedApi")
        val resId = ctx.resources.getIdentifier(iconKey, "drawable", ctx.packageName)
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

    // ── Map diagnostics (privacy-safe: counts/modes/booleans only, never coordinates) ──────────

    /** Logs a low-frequency map state transition and refreshes the diagnostic-report snapshot. */
    private fun logMapEvent(category: String, message: String) {
        try { app.surrealar.util.DiagnosticsLogger.i(category, message) } catch (_: Exception) {}
        refreshMapDiag()
    }

    /** Pushes the current map UI state into the diagnostic-report snapshot. No coordinates. */
    private fun refreshMapDiag() {
        try {
            val total = coordinateMap.size
            val visible = visibilityMap.count { it.value }
            app.surrealar.util.diagnostics.MapRuntimeDiagnostics.update {
                it.copy(
                    mapReady = googleMap != null,
                    mapType = mapTypeLabel(currentMapType),
                    mapToolsOpen = mapToolsVisible,
                    gridMode = gridMode.label,
                    gridSpacing = currentGridSpacingM?.let { m -> MapGrid.formatSpacing(m) },
                    pointLabelMode = pointLabelMode.label,
                    currentLocationVisible = showCurrentLocationOnMap,
                    headingAvailable = headingMarker != null,
                    accuracyCircleVisible = accuracyCircle != null,
                    markersTotal = total,
                    markersVisible = visible,
                    selectedActive = selectedCoordinateId != null,
                    stakeoutActive = isStakeoutMode,
                    guidanceActive = isGuidanceActive,
                )
            }
        } catch (_: Exception) {}
    }

    /** Saves the current camera into the session ViewModel so it can be restored on return. */
    private fun saveCameraState() {
        val cam = googleMap?.cameraPosition ?: return
        mapUiVm.update {
            it.copy(camera = MapCameraState(
                lat = cam.target.latitude, lng = cam.target.longitude,
                zoom = cam.zoom, bearing = cam.bearing, tilt = cam.tilt,
            ))
        }
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

    // ── My Location display toggle (map display only) ──────────────────────────

    private fun toggleMyLocationVisibility() {
        showCurrentLocationOnMap = !showCurrentLocationOnMap
        mapUiVm.update { it.copy(showCurrentLocation = showCurrentLocationOnMap) }
        updateMyLocationRowState()
        applyCurrentLocationVisibility()
        syncCompass()   // My Location heading shares the one compass listener
        logMapEvent("MapControls", "my location overlay=${if (showCurrentLocationOnMap) "on" else "off"}")
        showSnackbar(if (showCurrentLocationOnMap) "Showing my location" else "Hiding my location")
    }

    /** Reflects the toggle state on the toolbar row (dimmed = off) and swaps its content description. */
    private fun updateMyLocationRowState() {
        myLocationRow?.alpha = if (showCurrentLocationOnMap) 1.0f else 0.4f
        myLocationRow?.contentDescription =
            if (showCurrentLocationOnMap) "Hide my location and direction on map"
            else "Show my location and direction on map"
    }

    /**
     * Shows/hides the live-location overlays (marker, accuracy circle, trail, heading arrow) only.
     * The latest fix is untouched, so recenter and stakeout keep working while hidden. When re-enabled,
     * the marker/accuracy/arrow are recreated from the latest known fix; the trail resumes on next fix.
     */
    private fun applyCurrentLocationVisibility() {
        if (showCurrentLocationOnMap) {
            currentFix?.let { f ->
                if (f.latDeg in -90.0..90.0 && f.lonDeg in -180.0..180.0) {
                    updateCurrentMarker(f.latDeg, f.lonDeg, f.rtkStatus)
                    updateAccuracyCircle(LatLng(f.latDeg, f.lonDeg), f.hAccM)
                }
            }
            updateHeadingIndicator()
        } else {
            currentMarker?.remove(); currentMarker = null; lastCurrentMarkerHue = null
            clearAccuracyCircle()
            clearLiveTrail()
            removeHeadingIndicator()
        }
    }

    /**
     * Draws/updates the heading arrow under the current-location marker. Heading source priority is
     * shared with stakeout (compass → course-over-ground when moving → none). The marker is FLAT so
     * Google Maps compensates for camera bearing automatically — no manual rotation needed. The arrow
     * is hidden when My Location is off, there's no live position, or no reliable direction exists.
     */
    private fun updateHeadingIndicator() {
        val map = googleMap
        val pos = lastFixLatLng
        if (!showCurrentLocationOnMap || map == null || pos == null) { removeHeadingIndicator(); return }

        val (source, heading) = app.surrealar.stakeout.StakeoutGuidance.resolveHeading(
            preferCompass = true,
            compassHeadingDeg = compassHeadingDeg,
            courseOverGroundDeg = currentFix?.courseDeg,
            speedMps = currentFix?.speedMps,
        )
        if (heading == null || source == app.surrealar.stakeout.HeadingSource.NORTH_UP) {
            removeHeadingIndicator(); return
        }

        // Throttle marker churn: skip tiny rotation deltas.
        val last = lastAppliedHeadingDeg
        val descriptor = headingArrowDescriptor() ?: return
        if (headingMarker == null) {
            headingMarker = map.addMarker(
                MarkerOptions().position(pos).icon(descriptor)
                    .anchor(0.5f, 0.5f).flat(true).rotation(heading.toFloat())
                    .zIndex(0.5f)   // under coordinate pins, with the current-location dot
            )
            lastAppliedHeadingDeg = heading
        } else {
            try {
                headingMarker?.position = pos
                if (last == null || app.surrealar.stakeout.StakeoutGuidance.normalize180(heading - last).let { kotlin.math.abs(it) } >= 2.0) {
                    headingMarker?.rotation = heading.toFloat()
                    lastAppliedHeadingDeg = heading
                }
            } catch (e: Exception) { Log.w(TAG, "heading marker update failed", e) }
        }
    }

    private fun removeHeadingIndicator() {
        try { headingMarker?.remove() } catch (_: Exception) {}
        headingMarker = null
        lastAppliedHeadingDeg = null
    }

    /** Lazily builds (and caches) the heading-arrow bitmap descriptor from the vector drawable. */
    private fun headingArrowDescriptor(): BitmapDescriptor? {
        headingDescriptor?.let { return it }
        return try {
            val d = ContextCompat.getDrawable(requireContext(), R.drawable.ic_location_heading) ?: return null
            val size = dpToPx(36f)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(canvas)
            BitmapDescriptorFactory.fromBitmap(bmp).also { headingDescriptor = it }
        } catch (e: Exception) { Log.w(TAG, "headingArrowDescriptor failed", e); null }
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

    /** Advances the map type one step through [mapTypeCycle] and applies it. */
    private fun cycleMapType() {
        val idx = mapTypeCycle.indexOf(currentMapType).coerceAtLeast(0)
        applyMapType(mapTypeCycle[(idx + 1) % mapTypeCycle.size])
        showSnackbar("Map type: ${mapTypeLabel(currentMapType)}")
    }

    private fun applyMapType(type: Int) {
        currentMapType = type
        mapUiVm.update { it.copy(mapType = type) }
        googleMap?.mapType = type
        googleMap?.let { MapThemeHelper.applyTheme(requireContext(), it, type) }
        isSatellite = type == GoogleMap.MAP_TYPE_HYBRID || type == GoogleMap.MAP_TYPE_SATELLITE
        updateMapTypeButton()
        logMapEvent("MapControls", "map type=${mapTypeLabel(type)}")
    }

    /** Shows the current map type on the toolbar row and announces the next one for accessibility. */
    private fun updateMapTypeButton() {
        val idx = mapTypeCycle.indexOf(currentMapType).coerceAtLeast(0)
        val current = mapTypeLabel(currentMapType)
        val next = mapTypeLabel(mapTypeCycle[(idx + 1) % mapTypeCycle.size])
        txtMapType?.text = current
        mapTypeRow?.contentDescription = "Map type: $current. Tap to switch to $next."
    }

    private fun mapTypeLabel(type: Int): String = when (type) {
        GoogleMap.MAP_TYPE_SATELLITE -> "Satellite"
        GoogleMap.MAP_TYPE_HYBRID    -> "Hybrid"
        GoogleMap.MAP_TYPE_TERRAIN   -> "Terrain"
        else                         -> "Normal"
    }

    // ── Coordinate visibility ──────────────────────────────────────────────────

    /** Overflow ("More") menu. Show all / Hide all are now explicit buttons; this keeps the
     *  contextual "Show selected only" and is the home for future point-display options. */
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
            applyPointLabels()
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
            applyPointLabels()
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
            applyPointLabels()
            showSnackbar("All coordinates hidden")
        } catch (e: Exception) { Log.w(TAG, "hideAllCoordinates error", e) }
    }

    private fun refreshToggleList() {
        try {
            allToggleItems = coordinateMap.values.map { c ->
                CoordinateToggleItem(
                    id = c.id, name = c.name,
                    checked = visibilityMap[c.id] ?: true,
                    icon = c.displayIconKey ?: "", modelId = c.linkedModelId, color = c.color,
                    lat = c.latitude, lon = c.longitude,
                    meta = buildRowMeta(c)
                )
            }
            applyDrawerFilter()
        } catch (e: Exception) { Log.w(TAG, "refreshToggleList error", e) }
    }

    /** Submits the drawer list filtered by the search query (display-only — never changes marker
     *  visibility) and manages the empty/no-match state. */
    private fun applyDrawerFilter() {
        val q = pointSearchQuery
        val filtered = if (q.isBlank()) allToggleItems
            else allToggleItems.filter { it.name.contains(q, ignoreCase = true) }
        toggleAdapter.submit(filtered)
        when {
            allToggleItems.isEmpty() -> showEmptyState(searching = false)
            filtered.isEmpty() -> showEmptyState(searching = true)
            else -> placeholder?.visibility = View.GONE
        }
    }

    private fun showEmptyState(searching: Boolean) {
        placeholder?.visibility = View.VISIBLE
        if (searching) {
            emptyTitle?.text = "No matching points"
            emptySubtitle?.text = "Try a different search."
        } else {
            emptyTitle?.text = "No map points"
            emptySubtitle?.text = "Capture or import coordinates to show them here."
        }
    }

    private fun updateVisibleCount() {
        val visible = visibilityMap.values.count { it }
        val total = coordinateMap.size
        // Map-workspace style count, e.g. "12 shown / 18 total".
        leftPanelSubtitle?.text = if (total == 0) "No points yet" else "$visible shown / $total total"
        visibleCountText?.text = "Visible $visible / $total"
        collapsedCount?.text = total.toString()
    }

    // ── Selected coordinate ────────────────────────────────────────────────────

    private fun selectCoordinate(id: String) {
        val coord = coordinateMap[id] ?: return
        selectedCoordinateId = id
        mapUiVm.update { it.copy(selectedCoordinateId = id) }
        toggleAdapter.setSelectedId(id)
        showSelectedCoordinateCard(coord)
        logMapEvent("MapControls", "selected point opened")
        // Center map on selection, keeping zoom at least 16
        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(
            LatLng(coord.latitude, coord.longitude),
            (googleMap?.cameraPosition?.zoom ?: 18f).coerceAtLeast(16f)
        ))
    }

    /** Re-shows the selected-point card on view recreation WITHOUT moving the camera (camera is
     *  restored separately). Clears the selection if the coordinate no longer exists. */
    private fun restoreSelectedCard() {
        val id = selectedCoordinateId ?: return
        val coord = coordinateMap[id]
        if (coord == null) {
            dismissSelectedCoordinate()
            return
        }
        toggleAdapter.setSelectedId(id)
        showSelectedCoordinateCard(coord)
    }

    private fun showSelectedCoordinateCard(coord: Coordinate) {
        selectedCoordinateCard?.visibility = View.VISIBLE
        txtSelectedName?.text = coord.name.ifBlank { "—" }
        txtSelectedCoords?.text = String.format(Locale.US, "%.6f, %.6f", coord.latitude, coord.longitude)
        txtSelectedElevation?.text = String.format(Locale.getDefault(), "Elev: %.2f m", coord.altitude)
        loadCoordinateIconIntoView(coord, imgSelectedIcon)

        // Live relationship (distance + bearing from the current position) — uses the existing map
        // math; hidden when there is no live fix. Does not affect stakeout.
        val live = lastFixLatLng
        if (live != null) {
            val target = LatLng(coord.latitude, coord.longitude)
            val dist = calculateDistance(live, target)
            val bearing = calculateBearing(live, target)
            txtSelectedDistance?.text = "Distance: " + formatDistanceMeters(dist)
            txtSelectedBearing?.text = String.format(
                Locale.getDefault(), "Bearing: %s %.0f°",
                app.surrealar.stakeout.StakeoutGuidance.compassPoint(bearing), bearing
            )
            layoutSelectedLive?.visibility = View.VISIBLE
        } else {
            layoutSelectedLive?.visibility = View.GONE
        }

        // Capture-quality summary from the SAVED point metadata (not live receiver status).
        val sourceText = buildCaptureSourceText(coord)
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

    private fun formatDistanceMeters(d: Double): String = when {
        d < 1.0  -> String.format(Locale.getDefault(), "%.2f m", d)
        d < 10.0 -> String.format(Locale.getDefault(), "%.1f m", d)
        else     -> String.format(Locale.getDefault(), "%.0f m", d)
    }

    /** "Source: RS2+ Float"-style summary from saved capture metadata; null when nothing useful. */
    private fun buildCaptureSourceText(coord: Coordinate): String? {
        val device = friendlySource(coord.provider)
        val fix = coord.rtkStatus?.takeIf { it.isNotBlank() }?.let { friendlyFix(it) }
        val parts = listOfNotNull(device, fix)
        return if (parts.isEmpty()) null else "Source: " + parts.joinToString(" ")
    }

    private fun friendlySource(provider: String?): String? = when {
        provider == null -> null
        provider.contains("rs2", ignoreCase = true) -> "RS2+"
        provider.contains("internal", ignoreCase = true) -> "Internal"
        else -> null   // "fused"/unknown: don't claim a source we can't verify
    }

    private fun friendlyFix(rtk: String): String = when (rtk.uppercase(Locale.US)) {
        "FIX" -> "Fixed"
        "FLOAT" -> "Float"
        "DGPS" -> "DGPS"
        "SINGLE" -> "Single"
        "NONE", "INVALID" -> "No fix"
        else -> rtk
    }

    /**
     * Compact, surveyor-focused metadata line for a drawer row: "Elev 432.7 m · Float · RS2+ · Model".
     * Uses only the saved point metadata; omits parts that aren't available. Empty → adapter shows lat/lon.
     */
    private fun buildRowMeta(p: Coordinate): String {
        val parts = mutableListOf<String>()
        parts += String.format(Locale.getDefault(), "Elev %.1f m", p.altitude)
        p.rtkStatus?.takeIf { it.isNotBlank() }?.let { parts += friendlyFix(it) }
        friendlySource(p.provider)?.let { parts += it }
        if (p.hasLinkedModel) parts += "Model"
        return parts.joinToString(" · ")
    }

    /** Opens the global AR scene (all saved points, including the selected one). */
    private fun openSelectedInAr() {
        try { findNavController().navigate(R.id.nav_open_in_ar) }
        catch (e: Exception) { Log.w(TAG, "open in AR failed", e) }
    }

    /** Opens the existing rich coordinate-detail screen for the selected point. */
    private fun openSelectedDetails() {
        val id = selectedCoordinateId ?: return
        try {
            val args = android.os.Bundle().apply { putString("arg_id", id) }
            findNavController().navigate(R.id.nav_coordinate_detail, args)
        } catch (e: Exception) { Log.w(TAG, "open details failed", e) }
    }

    private fun dismissSelectedCoordinate() {
        selectedCoordinateId = null
        mapUiVm.update { it.copy(selectedCoordinateId = null) }
        selectedCoordinateCard?.visibility = View.GONE
        toggleAdapter.setSelectedId(null)
        refreshMapDiag()   // keep the diagnostic snapshot's selected-active flag accurate (no log)
    }

    private fun loadCoordinateIconIntoView(coord: Coordinate, iv: ImageView?) {
        iv ?: return
        val modelId = coord.linkedModelId
        if (modelId != null) {
            iv.setImageResource(R.drawable.ic_pin)
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
        stakeoutTargetId = id
        lastStakeoutFixElapsedMs = null
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
        logMapEvent("MapStakeout", "stakeout started")
        showSnackbar("Stakeout started: ${coord.name}")
        dismissSelectedCoordinate()
    }

    private fun stopStakeout() {
        if (!isStakeoutMode) return
        if (isGuidanceActive) stopGuidance()
        isStakeoutMode = false
        stakeoutPanel?.visibility = View.GONE
        stakeoutTarget = null
        stakeoutTargetId = null
        lastStakeoutFixElapsedMs = null
        txtStakeoutTarget?.text = "—"
        showStakeoutWaiting()
        stakeoutMarker?.remove(); stakeoutMarker = null; lastStakeoutMarkerHue = null
        stakeoutLine?.remove(); stakeoutLine = null
        logMapEvent("MapStakeout", "stakeout stopped")
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
                    stakeoutTargetId = null   // free map-tap target (not tied to a saved coordinate)
                    lastStakeoutFixElapsedMs = null
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
        mapUiVm.update { it.copy(isLeftPanelCollapsed = true) }
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
        mapUiVm.update { it.copy(isLeftPanelCollapsed = false) }
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

    /** Advances Off → Auto → Fine → Coarse → Off and redraws. */
    private fun cycleGrid() {
        gridMode = gridMode.next()
        mapUiVm.update { it.copy(gridMode = gridMode) }
        redrawGrid()
        logMapEvent("MapControls", "grid mode=${gridMode.label}")
        showSnackbar("Grid: ${MapGrid.buttonLabel(gridMode, currentGridSpacingM)}")
    }

    private fun clearGrid() {
        gridLines.forEach { try { it.remove() } catch (_: Exception) {} }
        gridLines.clear()
    }

    /** Map scale (metres per screen pixel) at the camera centre — Google Web-Mercator formula. */
    private fun metersPerPixel(): Double {
        val cam = googleMap?.cameraPosition ?: return Double.NaN
        return 156543.03392 * cos(Math.toRadians(cam.target.latitude)) / 2.0.pow(cam.zoom.toDouble())
    }

    /**
     * Resolves the spacing for the current mode, redraws the meter grid, and refreshes the button.
     * Called on grid cycle and on camera-idle (so Auto spacing adapts to zoom).
     */
    private fun redrawGrid() {
        clearGrid()
        if (gridMode == MapGridMode.OFF) {
            currentGridSpacingM = null
            updateGridButton()
            return
        }
        val auto = MapGrid.autoSpacingMeters(metersPerPixel())
        val spacing = MapGrid.spacingForMode(gridMode, auto)
        currentGridSpacingM = spacing
        if (spacing != null) drawMeterGrid(spacing)
        updateGridButton()
        // Update the diagnostic snapshot summary (no per-redraw log — camera-idle is frequent).
        val zoom = googleMap?.cameraPosition?.zoom ?: 0f
        MapRuntimeDiagnostics.update {
            it.copy(lastGridSummary = MapRuntimeDiagnostics.gridSummary(
                gridMode.label, currentGridSpacingM?.let { m -> MapGrid.formatSpacing(m) }, gridLines.size, zoom))
        }
    }

    /**
     * Draws a UTM-aligned metre grid: vertical lines on round easting multiples, horizontal lines on
     * round northing multiples of [spacingMeters], using the existing [UtmConverter] (forward +
     * inverse). Lines are capped per axis for performance/readability. Straight polylines between the
     * converted endpoints are an acceptable approximation at survey zoom levels (single UTM zone).
     */
    private fun drawMeterGrid(spacingMeters: Double) {
        val map = googleMap ?: return
        try {
            val bounds = map.projection.visibleRegion.latLngBounds
            val sw = UtmConverter.latLonToUtm(bounds.southwest.latitude, bounds.southwest.longitude)
            val ne = UtmConverter.latLonToUtm(bounds.northeast.latitude, bounds.northeast.longitude)
            // Use the centre zone/hemisphere so all lines convert consistently within one zone.
            val centre = UtmConverter.latLonToUtm(
                (bounds.southwest.latitude + bounds.northeast.latitude) / 2.0,
                (bounds.southwest.longitude + bounds.northeast.longitude) / 2.0,
            )
            val minE = minOf(sw.easting, ne.easting); val maxE = maxOf(sw.easting, ne.easting)
            val minN = minOf(sw.northing, ne.northing); val maxN = maxOf(sw.northing, ne.northing)

            val color = ContextCompat.getColor(requireContext(), R.color.map_grid_line)
            fun line(a: LatLng, b: LatLng) {
                try { gridLines.add(map.addPolyline(PolylineOptions().add(a, b).color(color).width(1.5f).zIndex(0f))) } catch (_: Exception) {}
            }

            // Vertical lines (constant easting).
            var e = Math.ceil(minE / spacingMeters) * spacingMeters
            var n = 0
            while (e <= maxE && n < MapGrid.MAX_LINES_PER_AXIS) {
                val (lat1, lon1) = UtmConverter.utmToLatLon(e, minN, centre.zone, centre.hemisphere)
                val (lat2, lon2) = UtmConverter.utmToLatLon(e, maxN, centre.zone, centre.hemisphere)
                line(LatLng(lat1, lon1), LatLng(lat2, lon2))
                e += spacingMeters; n++
            }
            // Horizontal lines (constant northing).
            var north = Math.ceil(minN / spacingMeters) * spacingMeters
            n = 0
            while (north <= maxN && n < MapGrid.MAX_LINES_PER_AXIS) {
                val (lat1, lon1) = UtmConverter.utmToLatLon(minE, north, centre.zone, centre.hemisphere)
                val (lat2, lon2) = UtmConverter.utmToLatLon(maxE, north, centre.zone, centre.hemisphere)
                line(LatLng(lat1, lon1), LatLng(lat2, lon2))
                north += spacingMeters; n++
            }
        } catch (e: Exception) { Log.w(TAG, "drawMeterGrid error", e) }
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
        logMapEvent("MapControls", "point labels=${pointLabelMode.label}")
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
    private fun applyPointLabels() {
        val map = googleMap ?: return
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

        val live = lastFixLatLng
        val distanceMode = pointLabelMode == PointLabelMode.DISTANCE
        val labelled = HashSet<String>()
        var count = 0
        for ((id, coord) in coordinateMap) {
            if (count >= LABEL_MAX_COUNT) break
            if (visibilityMap[id] != true) continue
            val dist = if (distanceMode && live != null)
                calculateDistance(live, LatLng(coord.latitude, coord.longitude)) else null
            val text = PointLabel.labelText(pointLabelMode, coord.name, coord.altitude, dist) ?: continue
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
        val visibleCount = visibilityMap.count { it.value }
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
            val density = resources.displayMetrics.density
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
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
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 6f * density, 6f * density, bgPaint)
            var y = pad - fm.ascent
            for (line in lines) { canvas.drawText(line, width / 2f, y, textPaint); y += lineH }
            BitmapDescriptorFactory.fromBitmap(bmp).also { if (cache) labelDescriptorCache[text] = it }
        } catch (e: Exception) { Log.w(TAG, "buildLabelDescriptor failed", e); null }
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
