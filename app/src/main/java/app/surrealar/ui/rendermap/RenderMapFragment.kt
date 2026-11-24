package app.surrealar.ui.rendermap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Bundle
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
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
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
import io.github.pennstatecs.geokit.projection.Utm
import app.surrealar.util.diagnostics.MapRuntimeDiagnostics
import app.surrealar.util.DevLog
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
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
        // Stakeout thresholds come from Settings → Stakeout (toleranceMeters = green / "Within
        // tolerance", warningAccuracyMeters = low-accuracy warning). Amber spans out to this multiple
        // of the tolerance (the previous fixed 0.10 / 0.25 m pair had the same 2.5× ratio).
        private const val STAKEOUT_AMBER_FACTOR = 2.5
        // Guidance falls back to the "waiting" state if no live fix arrives within this window.
        private const val STAKEOUT_STALE_FIX_MS = 5000L
        private const val STAKEOUT_STALE_TICK_MS = 1500L
    }

    @Inject
    lateinit var fixSwitchboard: FixSwitchboard

    @Inject
    lateinit var lastKnownPositionStore: app.surrealar.gnss.lastknown.LastKnownPositionStore

    @Inject
    lateinit var sourceSettings: app.surrealar.gnss.source.SourceSettings

    @Inject
    lateinit var locationSourceRepo: app.surrealar.domain.repository.LocationSourceSettingsRepository

    @Inject
    lateinit var coordinateSettingsRepo: app.surrealar.domain.repository.CoordinateDisplaySettingsRepository

    @Inject
    lateinit var coordinateSystemService: app.surrealar.coords.CoordinateSystemService

    private var coordSystemSettings = app.surrealar.settings.model.CoordinateSystemSettings()

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

    // Map: the fragment is the one owner of the MapView and GoogleMap; components read the map
    // through `{ googleMap }` and never keep it.
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var dataObserved = false

    // ── Map components ─────────────────────────────────────────────────────────
    // Created lazily (after Hilt injection), one per fragment instance: their session state survives
    // view recreation, and each drops its views / map objects in its onDestroyView().

    // Saved-coordinate markers: the one owner of the loaded coordinates, markers and visibility.
    // Created lazily (after Hilt injection); one per fragment instance so visibility survives the view.
    private val markers: CoordinateMarkerLayer by lazy {
        CoordinateMarkerLayer(
            map = { googleMap },
            context = { context },
            scope = { viewLifecycleOwner.lifecycleScope },
            modelRepository = modelRepository,
        )
    }

    // Meter grid, point labels and boundary lines (+ their Map Tools rows).
    private val overlays: MapOverlayController by lazy {
        MapOverlayController(
            map = { googleMap },
            context = { context },
            markers = markers,
            liveLatLng = { lastFixLatLng },
            coordinateSystemService = coordinateSystemService,
            coordSystemSettings = { coordSystemSettings },
            mapUiVm = mapUiVm,
            logMapEvent = ::logMapEvent,
            showSnackbar = ::showSnackbar,
        )
    }

    // Camera restore / auto-fit / recenter / fit / save.
    private val camera: MapCameraController by lazy {
        MapCameraController(
            map = { googleMap },
            mapView = { mapView },
            mapUiVm = mapUiVm,
            markers = markers,
            liveLatLng = { lastFixLatLng },
            showSnackbar = ::showSnackbar,
        )
    }

    // Map Tools overlay: open state, map type, My Location row.
    private val tools: MapToolsController by lazy {
        MapToolsController(
            map = { googleMap },
            context = { context },
            mapUiVm = mapUiVm,
            logMapEvent = ::logMapEvent,
            showSnackbar = ::showSnackbar,
        )
    }

    // Left drawer: point list, search, counts, visibility menu, collapse/expand.
    private val drawer: MapDrawerController by lazy {
        MapDrawerController(
            context = { context },
            markers = markers,
            modelRepository = modelRepository,
            coordinateSystemService = coordinateSystemService,
            mapUiVm = mapUiVm,
            selectedId = { selection.selectedCoordinateId },
            onRowClick = { id -> selection.selectCoordinate(id) },
            onVisibilityChanged = { overlays.applyPointLabels() },
            showSnackbar = ::showSnackbar,
        )
    }

    // Selected point (owner of the selected id) and its card.
    private val selection: SelectedCoordinateController by lazy {
        SelectedCoordinateController(
            context = { context },
            scope = { viewLifecycleOwner.lifecycleScope },
            markers = markers,
            camera = camera,
            mapUiVm = mapUiVm,
            coordinateSystemService = coordinateSystemService,
            modelRepository = modelRepository,
            liveLatLng = { lastFixLatLng },
            onSelectionChanged = { id -> drawer.setSelectedId(id) },
            logMapEvent = ::logMapEvent,
            refreshMapDiag = ::refreshMapDiag,
        )
    }

    // Live tracking
    private var currentMarker: Marker? = null
    // Map-display-only toggle for the live-location overlays. Does NOT affect fix collection,
    // recenter, or stakeout — those use the latest fix (lastFixLatLng / currentFix) directly.
    // TODO: persist via a future map-display settings model; fragment-only state for now.
    private var showCurrentLocationOnMap = true
    private var lastFixLatLng: LatLng? = null
    private var accuracyCircle: Circle? = null
    private var liveTrail: Polyline? = null
    private val trailPoints = mutableListOf<LatLng>()
    private var lastTrailFix: Fix? = null
    private var currentFix: Fix? = null
    private var lastCurrentMarkerHue: Float? = null
    private var lastStakeoutMarkerHue: Float? = null

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
    private val feedbackGate = io.github.pennstatecs.geokit.stakeout.StakeoutFeedbackGate()
    private var compassHeadingDeg: Double? = null
    private var sensorManager: android.hardware.SensorManager? = null
    private var rotationSensor: android.hardware.Sensor? = null
    private var compassRegistered = false
    private var fragmentResumed = false
    private var toneGenerator: android.media.ToneGenerator? = null
    // TRUE heading (declination at the latest FixSwitchboard fix, display rotation, flat/upright).
    private val headingProvider = HeadingProvider(
        latestFix = { currentFix },
        // Before the first fix: declination from the last known position (changes little over tens of km).
        lastKnownPosition = { if (::lastKnownPositionStore.isInitialized) lastKnownPositionStore.get() else null },
    )
    // Current-location heading indicator (a flat, rotating arrow shown under the My Location marker).
    private var headingMarker: Marker? = null
    private var headingDescriptor: BitmapDescriptor? = null
    private var lastAppliedHeadingDeg: Double? = null
    private val rotationListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            if (event.sensor.type != android.hardware.Sensor.TYPE_ROTATION_VECTOR) return
            // Was getOrientation() azimuth = MAGNETIC north, screen-top only (≈11.6° off near Hazleton).
            val rotation = view?.display?.rotation ?: android.view.Surface.ROTATION_0
            compassHeadingDeg = headingProvider.trueHeadingDeg(event.values, rotation)
            logHeadingSourceIfChanged(null)
            // Keep the on-map heading arrow following the compass between fixes (throttled). Delivered
            // on the main thread, so touching the map here is safe.
            updateHeadingIndicator()
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
            app.surrealar.util.diagnostics.HeadingDiagnostics.onCompassAccuracy(accuracy)
        }
    }

    // Diagnostics only: log the heading source when its parts change (enum compares, no allocation).
    private var loggedDeclSource: Any? = null
    private var loggedMagneticOnly: Boolean? = null
    private var loggedStakeoutSource: Any? = null
    private fun logHeadingSourceIfChanged(stakeoutSource: io.github.pennstatecs.geokit.stakeout.HeadingSource?) {
        val decl = headingProvider.declinationSource
        val mag = headingProvider.isMagneticOnly
        val st = stakeoutSource ?: loggedStakeoutSource
        if (decl == loggedDeclSource && mag == loggedMagneticOnly && st == loggedStakeoutSource) return
        loggedDeclSource = decl; loggedMagneticOnly = mag; loggedStakeoutSource = st
        app.surrealar.util.diagnostics.HeadingDiagnostics.onSource(
            app.surrealar.util.diagnostics.HeadingDiagnostics.describe((st as? Enum<*>)?.name, decl.name, mag))
    }


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
        // Re-read every open: the VM re-seeds only if the defaults changed in Settings since last time.
        runCatching { kotlinx.coroutines.runBlocking { mapSettingsRepo.mapSettings.first() } }
            .getOrNull()?.let { mapUiVm.seedFromDefaults(it) }

        // Restore transient map UI state into the working fields BEFORE views/map are wired, so the
        // toolbar labels, map type, grid, and My Location come back as the user left them. The map
        // type/grid/camera are applied in onMapReady; the Map Tools panel is restored once laid out.
        mapUiVm.state.let { s ->
            tools.restoreState(s.mapType, s.isMapToolsOpen)
            overlays.restoreModes(s.gridMode, s.pointLabelMode)
            showCurrentLocationOnMap = s.showCurrentLocation
            drawer.restoreState(s.isLeftPanelCollapsed)
            selection.restoreState(s.selectedCoordinateId)
        }

        mapView = root.findViewById(R.id.mapView)
        drawer.bindListViews(root)   // list + adapter, empty state, header, visibility menu
        selection.bindViews(
            root,
            onStakeout = { startStakeoutForSelectedCoordinate() },
            onOpenAr = { openSelectedInAr() },
            onOpenDetails = { openSelectedDetails() },
        )

        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync { map ->
            DevLog.d(TAG) { "onMapReady: map ready, mapType=${tools.currentMapType}" }
            googleMap = map
            tools.applyToMap(map)
            val cameraRestored = camera.restoreOrDefault(map)
            map.setMaxZoomPreference(22f)
            map.setMinZoomPreference(2f)

            map.setInfoWindowAdapter(object : GoogleMap.InfoWindowAdapter {
                override fun getInfoWindow(marker: Marker): View? = null
                override fun getInfoContents(marker: Marker): View? = createInfoWindowView(marker)
            })

            map.setOnMarkerClickListener { marker ->
                val id = marker.tag as? String
                if (id != null && markers.coordinates.containsKey(id)) {
                    selection.selectCoordinate(id)
                } else {
                    marker.showInfoWindow()
                }
                true
            }

            map.setOnMapLoadedCallback {
                camera.onMapLoaded()
                app.surrealar.util.diagnostics.MapRuntimeDiagnostics.update { it.copy(mapLoaded = true) }
                logMapEvent("MAP", "Map tiles loaded")
            }
            // Redraw the meter grid when the camera settles so Auto spacing adapts to zoom/pan.
            // Idle fires only after movement stops (not continuously), so this stays cheap.
            map.setOnCameraIdleListener {
                camera.saveCameraState()
                overlays.onCameraIdle()
            }
            overlays.redrawGrid()   // restore the grid if a mode was active before view recreation
            bindData()
            startFixCollection()
            setupMapClickListener()
            app.surrealar.util.diagnostics.MapRuntimeDiagnostics.update {
                it.copy(cameraRestored = cameraRestored)
            }
            logMapEvent("MAP", "Map ready type=${MapTypes.label(tools.currentMapType)} cameraRestored=$cameraRestored")
        }

        // Map Tools overlay wiring. The toggle slides the panel in/out (AR pattern); each row
        // delegates to the SAME existing map action handlers — only the presentation changed.
        tools.bindViews(root, showCurrentLocationOnMap, onMyLocationClick = { toggleMyLocationVisibility() })
        root.findViewById<View>(R.id.btnMapCenter)?.setOnClickListener { camera.recenterMap() }
        overlays.bindViews(root)   // Grid + Labels rows
        root.findViewById<View>(R.id.btnMapZoomIn)?.setOnClickListener { camera.zoomIn() }
        root.findViewById<View>(R.id.btnMapZoomOut)?.setOnClickListener { camera.zoomOut() }
        root.findViewById<View>(R.id.btnMapFit)?.setOnClickListener { camera.fitVisibleCoordinates() }

        drawer.restoreFromBundle(savedInstanceState)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        drawer.bindPanelViews(view)   // panel, counts, search, Show all / Hide all

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
        // Coordinate system (Settings → Coordinates) for elevation labels.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                coordinateSettingsRepo.coordinateSystemSettings.collect {
                    coordSystemSettings = it
                    overlays.onCoordinateSettingsChanged()
                }
            }
        }

        drawer.initPanel()
        startProviderObservation()
    }

    // ── Lifecycle pass-throughs ────────────────────────────────────────────────

    override fun onStart()  { super.onStart();  try { mapView?.onStart() } catch (e: Exception) { logFail("onStart", e) } }
    override fun onLowMemory() { super.onLowMemory(); try { mapView?.onLowMemory() } catch (e: Exception) { logFail("onLowMemory", e) } }
    override fun onResume() {
        super.onResume()
        try { mapView?.onResume(); fragmentResumed = true; resumeGuidanceFeedback(); syncCompass() }
        catch (e: Exception) { logFail("onResume", e) }
    }
    override fun onPause() {
        try { camera.saveCameraState(); mapView?.onPause(); fragmentResumed = false; pauseGuidanceFeedback(); syncCompass() }
        catch (e: Exception) { logFail("onPause", e) }
        super.onPause()
    }

    override fun onStop() {
        try { clearLiveTrail(); clearAccuracyCircle(); mapView?.onStop() } catch (e: Exception) { logFail("onStop", e) }
        super.onStop()
    }

    override fun onDestroyView() {
        try {
            stopGuidance()   // release tone, clear keep-screen-on
            fragmentResumed = false
            unregisterCompass()   // make sure the shared sensor is released with the view
            clearLiveTrail(); clearAccuracyCircle()
            overlays.onDestroyView()   // grid lines, label markers + label cache, Grid/Labels rows
            currentMarker?.remove(); currentMarker = null
            headingMarker?.remove(); headingMarker = null; lastAppliedHeadingDeg = null
            stakeoutLine?.remove(); stakeoutLine = null
        } catch (e: Exception) { logFail("onDestroyView cleanup", e) }
        lastCurrentMarkerHue = null
        lastStakeoutMarkerHue = null
        // The map + its markers are destroyed with the view. Drop the stale marker references and the
        // observe-guard so that when this fragment's view is recreated (e.g. returning from the point
        // Details screen), bindData() re-attaches the coordinate observer and rebuilds markers on the
        // fresh GoogleMap instead of leaving the panel stuck on "Loading…". The marker layer keeps the
        // user's show/hide choices (keyed by id) across recreation.
        markers.onDestroyView()
        camera.onDestroyView()
        dataObserved = false
        // View-only components drop their view references (state such as the selection, drawer
        // search/collapse and map tools open state is kept for the recreated view).
        drawer.onDestroyView()
        selection.onDestroyView()
        tools.onDestroyView()
        try { mapView?.onDestroy() } catch (e: Exception) { logFail("mapView.onDestroy", e) }
        // Drop the destroyed map too (it used to be retained until the next onMapReady), so nothing
        // can draw on it between view destruction and recreation.
        mapView = null; googleMap = null
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
                    // Live position from the GNSS feed, only when it comes from the SELECTED source
                    // (same rule as the toolbar and Home map): during the External connecting window
                    // the live provider can still be Internal, and the map must not show that as the
                    // receiver's position.
                    kotlinx.coroutines.flow.combine(fixSwitchboard.fixes, locationSourceRepo.locationSource) { fix, src -> fix to src }
                        .conflate()
                        .sample(250)
                        .collect { (fix, src) ->
                            val matches = if (src == app.surrealar.domain.model.LocationSourceType.INTERNAL)
                                fix.provider == io.github.pennstatecs.geokit.gnss.model.Provider.INTERNAL
                            else fix.provider != io.github.pennstatecs.geokit.gnss.model.Provider.INTERNAL
                            if (matches) updateLiveTracking(fix) else clearLiveMarker()
                        }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // The map stops following GNSS until the screen restarts.
                    logFail("fix collector", e, 'E')
                }
            }
        }
        // Fixes stopped (signal lost / receiver disconnected): remove the live marker instead of
        // leaving a frozen position on the map that looks current. It reappears with the next fix.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fixSwitchboard.fixStale.collect { stale -> if (stale) clearLiveMarker() }
            }
        }
    }

    /** Removes the live-position overlays (they come back with the next matching GNSS fix). */
    private fun clearLiveMarker() {
        if (currentMarker == null && currentFix == null && lastFixLatLng == null) return
        clearAccuracyCircle()
        currentMarker?.remove(); currentMarker = null
        removeHeadingIndicator()
        currentFix = null
        lastFixLatLng = null   // recenter must not jump to a position that is no longer live
        lastCurrentMarkerHue = null
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
                // Per fix: each step logs at most once a minute (logFail).
                try { updateCurrentMarker(fix.latDeg, fix.lonDeg, fix.rtkStatus) } catch (e: Exception) { logFail("live.updateCurrentMarker", e) }
                try { updateAccuracyCircle(pos, fix.hAccM) } catch (e: Exception) { logFail("live.updateAccuracyCircle", e) }
                try { updateLiveTrail(pos, fix) } catch (e: Exception) { logFail("live.updateLiveTrail", e) }
                try { updateHeadingIndicator() } catch (e: Exception) { logFail("live.updateHeadingIndicator", e) }
            }
            try { updateStakeoutCalculations(pos) } catch (e: Exception) { logFail("live.updateStakeoutCalculations", e) }
            // Distance labels follow the live position, throttled to avoid heavy marker rebuilds.
            overlays.refreshDistanceLabelsIfDue()
        } catch (e: Exception) {
            logFail("live.updateLiveTracking", e, 'E')
        }
    }

    // ── Stakeout calculations ──────────────────────────────────────────────────

    private fun updateStakeoutCalculations(currentPos: LatLng) {
        val target = stakeoutTarget ?: return
        if (!isStakeoutMode) return
        lastStakeoutFixElapsedMs = android.os.SystemClock.elapsedRealtime()
        try {
            // Distance + bearing: WGS84 geodesic (geokit-core Geodesic).
            val distance = calculateDistance(currentPos, target)
            val bearing = calculateBearing(currentPos, target)
            // Distance in the units chosen under Settings → Coordinates.
            txtStakeoutDistance?.text = coordinateSystemService.distanceText(distance)
            // Bearing shown with an 8-point compass label, e.g. "NE 42°".
            txtStakeoutBearing?.text =
                String.format(Locale.getDefault(), "%s %.0f°", compassPoint(bearing), bearing)
            val tol = stakeoutSettings.toleranceMeters
            val color = when {
                distance < tol                         -> 0xFF4CAF50.toInt()
                distance < tol * STAKEOUT_AMBER_FACTOR -> 0xFFFF9800.toInt()
                else                                   -> 0xFFF44336.toInt()
            }
            txtStakeoutDistance?.setTextColor(color)
            txtStakeoutBearing?.setTextColor(color)

            // Display-only north/east offsets (true north, local tangent plane) in the chosen units.
            val (dN, dE) = northEastDeltaMeters(currentPos, target)
            txtStakeoutDelta?.text =
                "ΔN ${coordinateSystemService.deltaText(dN, 2)}    ΔE ${coordinateSystemService.deltaText(dE, 2)}"

            // State line: within tolerance vs. which way to move.
            txtStakeoutStatus?.text =
                if (distance < tol) "Within tolerance"
                else "Move ${compassDirectionWord(bearing)}"

            updateStakeoutAccuracy()
            updateStakeoutMarkerColor(distance)
            updateStakeoutLine(currentPos, target)
            updateGuidance(distance, bearing)
        } catch (e: Exception) {
            logFail("stakeout.calculations", e)
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
        if (acc > stakeoutSettings.warningAccuracyMeters) {
            tv.text = String.format(
                Locale.getDefault(), "Accuracy ±%.2f m · Low accuracy — stakeout may be unreliable", acc
            )
            tv.setTextColor(0xFFF44336.toInt())
        } else {
            tv.text = String.format(Locale.getDefault(), "Accuracy ±%.2f m", acc)
            tv.setTextColor(stakeoutAccuracyDefaultColor)
        }
    }

    /** 8-point compass abbreviation for a bearing in degrees (display-only; geokit-core helper). */
    private fun compassPoint(bearing: Double): String = io.github.pennstatecs.geokit.heading.Compass.point8(bearing)

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

    /** Local north/east offsets (metres) from [from] to [to] — same WGS84 frame as distance/bearing. */
    private fun northEastDeltaMeters(from: LatLng, to: LatLng): Pair<Double, Double> =
        io.github.pennstatecs.geokit.ellipsoid.Geodesic.northEastDeltaM(from.latitude, from.longitude, to.latitude, to.longitude).let { it.northM to it.eastM }

    // ── Stakeout guidance mode (display + feedback only) ───────────────────────

    private fun startGuidance() {
        if (!isStakeoutMode || stakeoutTarget == null) { showSnackbar("Select a stakeout target first"); return }
        isGuidanceActive = true
        feedbackGate.reset()
        btnStakeoutGuidance?.text = "Stop Guidance"
        imgStakeoutArrow?.visibility = View.VISIBLE
        txtStakeoutTolerance?.visibility = View.VISIBLE
        txtStakeoutTolerance?.text = "Tolerance: " + coordinateSystemService.lengthText(stakeoutSettings.toleranceMeters, 2)
        syncCompass()
        if (stakeoutSettings.enableAudio) ensureToneGenerator()
        if (stakeoutSettings.keepScreenOnDuringStakeout) setKeepScreenOn(true)
        startStaleWatcher()
        refreshStakeout()
        logMapEvent("STAKEOUT", "Stakeout guidance started toleranceM=${stakeoutSettings.toleranceMeters}")
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
        logMapEvent("STAKEOUT", "Stakeout guidance stopped")
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
                val stale = io.github.pennstatecs.geokit.stakeout.StakeoutGuidance.isPositionStale(
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
        txtStakeoutTolerance?.text = "Tolerance: " + coordinateSystemService.lengthText(stakeoutSettings.toleranceMeters, 2)
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
        val (source, heading) = io.github.pennstatecs.geokit.stakeout.StakeoutGuidance.resolveHeading(
            preferCompass = s.guidanceUsesCompassHeading,
            compassHeadingDeg = compassHeadingDeg,
            courseOverGroundDeg = currentFix?.courseDeg,
            speedMps = currentFix?.speedMps,
        )
        val relative = io.github.pennstatecs.geokit.stakeout.StakeoutGuidance.relativeBearing(bearingToTargetDeg, heading)
        // Arrow shows the relative direction when heading is known, else points at the map-north bearing.
        imgStakeoutArrow?.rotation = (relative ?: bearingToTargetDeg).toFloat()
        logHeadingSourceIfChanged(source)
        if (source == io.github.pennstatecs.geokit.stakeout.HeadingSource.NORTH_UP) {
            txtStakeoutHeadingNote?.visibility = View.VISIBLE
            txtStakeoutHeadingNote?.text = "Arrow is north-up until heading is available."
        } else if (source == io.github.pennstatecs.geokit.stakeout.HeadingSource.COMPASS && headingProvider.isMagneticOnly) {
            // No position at all yet → no declination: the compass heading is magnetic (~11.6° off here).
            txtStakeoutHeadingNote?.visibility = View.VISIBLE
            txtStakeoutHeadingNote?.text = "Magnetic heading (no position yet)"
        } else {
            txtStakeoutHeadingNote?.visibility = View.GONE
        }
        val status = io.github.pennstatecs.geokit.stakeout.StakeoutGuidance.status(
            hasTarget = true, hasPosition = true, distanceMeters = distanceMeters, toleranceMeters = s.toleranceMeters
        )
        when (feedbackGate.onUpdate(status, android.os.SystemClock.elapsedRealtime())) {
            io.github.pennstatecs.geokit.stakeout.StakeoutFeedback.ENTERED_TOLERANCE -> {
                // Distance to target only (no positions): the gate fires once per arrival.
                logMapEvent("STAKEOUT", "Stakeout target within tolerance distM=${"%.3f".format(java.util.Locale.US, distanceMeters)} toleranceM=${s.toleranceMeters}")
                if (s.enableHaptics) haptic(arrived = true); if (s.enableAudio) beep(arrived = true)
            }
            io.github.pennstatecs.geokit.stakeout.StakeoutFeedback.NAVIGATING_PULSE -> {
                if (s.enableHaptics) haptic(arrived = false); if (s.enableAudio) beep(arrived = false)
            }
            io.github.pennstatecs.geokit.stakeout.StakeoutFeedback.NONE -> {}
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
        } catch (e: Exception) { logFail("registerCompass", e); compassHeadingDeg = null }
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
            } catch (e: Exception) { logFail("ToneGenerator init", e); null }
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
        } catch (e: Exception) { logFail("stakeout beep", e) }
    }

    /** Uses a per-view keep-screen-on request so it never clobbers a global window flag. */
    private fun setKeepScreenOn(on: Boolean) {
        try { view?.keepScreenOn = on } catch (e: Exception) { logFail("keepScreenOn", e) }
    }

    private fun updateStakeoutMarkerColor(distance: Double) {
        val tol = stakeoutSettings.toleranceMeters
        val hue = when {
            distance < tol                         -> BitmapDescriptorFactory.HUE_GREEN
            distance < tol * STAKEOUT_AMBER_FACTOR -> BitmapDescriptorFactory.HUE_ORANGE
            else                                   -> BitmapDescriptorFactory.HUE_RED
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
            catch (e: Exception) { logFail("stakeoutLine update (recreating)", e); try { existing.remove() } catch (_: Exception) {}; stakeoutLine = null }
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
            } catch (e: Exception) { logFail("live.currentMarker position", e) }
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
            catch (e: Exception) { logFail("live.accuracyCircle update (recreating)", e); existing.remove(); accuracyCircle = null }
        }
        try {
            accuracyCircle = map.addCircle(
                CircleOptions().center(position).radius(accuracy)
                    .strokeColor(0x880000FF.toInt()).fillColor(0x220000FF.toInt()).strokeWidth(2f)
            )
        } catch (e: Exception) { logFail("live.accuracyCircle add", e) }
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
                catch (e: Exception) { logFail("live.liveTrail update (recreating)", e); existing.remove(); liveTrail = null }
            }
            liveTrail = map.addPolyline(
                // Not geodesic: at survey scale the great-circle and straight segments are identical, and the
                // geodesic version is recomputed for up to MAX_TRAIL_POINTS points on every new point.
                PolylineOptions().addAll(trailPoints).color(0xFFFF6B35.toInt()).width(4f).geodesic(false)
            )
        } catch (e: Exception) { logFail("live.liveTrail", e) }
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
        vm.allCoordinates.observe(viewLifecycleOwner) { stored ->
            if (googleMap == null) return@observe
            // Google Maps is WGS84: NAD83(2011)-stored points are converted for plotting (no-op for WGS84).
            val points = stored.map(app.surrealar.coords.StoredDatum::toWgs84)
            try {
                if (points.isEmpty()) {
                    markers.clear()
                    camera.clearCoordinates()
                    drawer.showNoPoints()
                    // A coordinate-backed stakeout target no longer exists — end stakeout cleanly.
                    if (isStakeoutMode && stakeoutTargetId != null) {
                        showSnackbar("Stakeout target was removed"); stopStakeout()
                    }
                    return@observe
                }
                drawer.hidePlaceholder()

                // Remove markers for coordinates that were deleted
                val newIds = points.map { it.id }.toHashSet()
                val removedIds = markers.removeMissing(newIds)
                // If the active stakeout target was the deleted coordinate, end stakeout cleanly.
                if (isStakeoutMode && stakeoutTargetId != null && stakeoutTargetId in removedIds) {
                    showSnackbar("Stakeout target was removed"); stopStakeout()
                }

                val latLngsVisible = ArrayList<LatLng>()
                val toggleItems = mutableListOf<CoordinateToggleItem>()

                points.forEach { p ->
                    // Adds/updates the glyph marker; null = the map went away (skip, as before).
                    val visible = markers.upsert(p) ?: return@forEach
                    if (visible) latLngsVisible.add(LatLng(p.latitude, p.longitude))
                    toggleItems += drawer.toggleItem(p, visible)
                }

                drawer.showItems(toggleItems)
                camera.onCoordinatesLoaded(latLngsVisible)
                // Re-show the selected-point card once coordinates are loaded (e.g. after returning
                // from details). Clears the selection if that coordinate no longer exists.
                selection.restoreSelectedCard()
                overlays.applyPointLabels()
                // Every DB emission lands here; log only when the counts change.
                val total = points.size
                val visibleCount = latLngsVisible.size
                DiagnosticsLogger.logOnChange(DiagTags.MAP, "rendermap.markers", total to visibleCount) {
                    "Map markers loaded total=$total visible=$visibleCount hidden=${total - visibleCount}"
                }
                refreshMapDiag()
            } catch (e: Exception) {
                MapRuntimeDiagnostics.update { it.copy(lastError = "observer: ${e.javaClass.simpleName}: ${e.message}") }
                logFail("coordinate observer", e, 'E')
            }
        }
    }

    // ── Map diagnostics (privacy-safe: counts/modes/booleans only, never coordinates) ──────────

    /** Logs a low-frequency map state transition and refreshes the diagnostic-report snapshot. */
    private fun logMapEvent(category: String, message: String) {
        DiagnosticsLogger.i(category, message)   // never throws
        refreshMapDiag()
    }

    /** Pushes the current map UI state into the diagnostic-report snapshot. No coordinates. */
    private fun refreshMapDiag() {
        try {
            val total = markers.coordinates.size
            val visible = markers.visibility.visibleCount
            app.surrealar.util.diagnostics.MapRuntimeDiagnostics.update {
                it.copy(
                    mapReady = googleMap != null,
                    mapType = MapTypes.label(tools.currentMapType),
                    mapToolsOpen = tools.mapToolsVisible,
                    gridMode = overlays.gridMode.label,
                    gridSpacing = overlays.currentGridSpacingM?.let { m -> MapGrid.formatSpacing(m) },
                    pointLabelMode = overlays.pointLabelMode.label,
                    currentLocationVisible = showCurrentLocationOnMap,
                    headingAvailable = headingMarker != null,
                    accuracyCircleVisible = accuracyCircle != null,
                    markersTotal = total,
                    markersVisible = visible,
                    selectedActive = selection.selectedCoordinateId != null,
                    stakeoutActive = isStakeoutMode,
                    guidanceActive = isGuidanceActive,
                )
            }
        } catch (e: Exception) { logFail("diagnostic snapshot refresh", e) }
    }

    // ── My Location display toggle (map display only) ──────────────────────────

    private fun toggleMyLocationVisibility() {
        showCurrentLocationOnMap = !showCurrentLocationOnMap
        mapUiVm.update { it.copy(showCurrentLocation = showCurrentLocationOnMap) }
        tools.updateMyLocationRowState(showCurrentLocationOnMap)
        applyCurrentLocationVisibility()
        syncCompass()   // My Location heading shares the one compass listener
        logMapEvent("MAP", "Map location overlay ${if (showCurrentLocationOnMap) "on" else "off"}")
        showSnackbar(if (showCurrentLocationOnMap) "Showing my location" else "Hiding my location")
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

        val (source, heading) = io.github.pennstatecs.geokit.stakeout.StakeoutGuidance.resolveHeading(
            preferCompass = true,
            compassHeadingDeg = compassHeadingDeg,
            courseOverGroundDeg = currentFix?.courseDeg,
            speedMps = currentFix?.speedMps,
        )
        if (heading == null || source == io.github.pennstatecs.geokit.stakeout.HeadingSource.NORTH_UP) {
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
                if (last == null || io.github.pennstatecs.geokit.stakeout.StakeoutGuidance.normalize180(heading - last).let { kotlin.math.abs(it) } >= 2.0) {
                    headingMarker?.rotation = heading.toFloat()
                    lastAppliedHeadingDeg = heading
                }
            } catch (e: Exception) { logFail("live.headingMarker update", e) }
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
            val size = requireContext().dpToPx(36f)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(canvas)
            BitmapDescriptorFactory.fromBitmap(bmp).also { headingDescriptor = it }
        } catch (e: Exception) { logFail("headingArrowDescriptor", e); null }
    }

    /** Opens the global AR scene (all saved points, including the selected one). */
    private fun openSelectedInAr() {
        try { findNavController().navigate(R.id.nav_open_in_ar) }
        catch (e: Exception) { DiagnosticsLogger.w(DiagTags.NAV, "Map open in AR failed", e) }
    }

    /** Opens the existing rich coordinate-detail screen for the selected point. */
    private fun openSelectedDetails() {
        val id = selection.selectedCoordinateId ?: return
        try {
            val args = android.os.Bundle().apply { putString("arg_id", id) }
            findNavController().navigate(R.id.nav_coordinate_detail, args)
        } catch (e: Exception) { DiagnosticsLogger.w(DiagTags.NAV, "Map point details open failed", e) }
    }

    // ── Stakeout ───────────────────────────────────────────────────────────────

    private fun startStakeoutForSelectedCoordinate() {
        val id = selection.selectedCoordinateId ?: run { showSnackbar("Select a coordinate first"); return }
        val coord = markers.coordinate(id) ?: return
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
        logMapEvent("STAKEOUT", "Stakeout started targetId=${coord.id}")   // id only: no point names in logs
        showSnackbar("Stakeout started: ${coord.name}")
        selection.dismissSelectedCoordinate()
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
        logMapEvent("STAKEOUT", "Stakeout stopped")
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
                        try { stakeoutMarker?.position = latLng } catch (e: Exception) { logFail("stakeout marker move", e) }
                    }
                    refreshStakeout()
                } catch (e: Exception) { logFail("map click stakeout", e) }
            } else {
                selection.dismissSelectedCoordinate()
            }
        }
    }

    // ── Info window ────────────────────────────────────────────────────────────

    private fun createInfoWindowView(marker: Marker): View? {
        return try {
            val coordinateId = marker.tag as? String
            val coordinate = coordinateId?.let { markers.coordinate(it) }
            @Suppress("InflateParams")
            val view = layoutInflater.inflate(R.layout.custom_info_window, null)
            // Non-point markers without a title are the live position dot.
            view.findViewById<TextView>(R.id.info_window_title).text =
                coordinate?.name ?: marker.title ?: "Current position"
            view.findViewById<TextView>(R.id.info_window_content).text = buildString {
                if (coordinate != null) {
                    append(coordinateSystemService.positionText(coordinate, latLonDecimals = 7))
                    append("\nElevation: ${coordinateSystemService.heightText(coordinate)}")
                    if (!coordinate.note.isNullOrBlank()) append("\n${coordinate.note}")
                } else {
                    // Live position (or another non-point marker): in the chosen system, not raw doubles.
                    val fix = fixSwitchboard.currentFix.value
                    val p = runCatching {
                        if (fix != null) coordinateSystemService.convertFix(fix, coordSystemSettings) else null
                    }.getOrNull()
                    if (p != null && marker.title == null) {
                        append(app.surrealar.coords.CoordinateFormat.horizontal(p, latLonDecimals = 7))
                        append("\nElevation: ${app.surrealar.coords.CoordinateFormat.height(p)}")
                    } else {
                        append(String.format(Locale.US, "%.7f, %.7f", marker.position.latitude, marker.position.longitude))
                    }
                }
            }
            view
        } catch (e: Exception) { logFail("createInfoWindowView", e); null }
    }

    // ── Snackbar ───────────────────────────────────────────────────────────────

    private fun showSnackbar(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
    }
}
