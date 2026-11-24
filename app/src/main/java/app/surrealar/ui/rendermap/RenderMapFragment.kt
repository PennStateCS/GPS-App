package app.surrealar.ui.rendermap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import app.surrealar.R
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.ui.viewpoints.CoordinatesViewModel
import app.surrealar.util.DevLog
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import app.surrealar.util.diagnostics.MapRuntimeDiagnostics
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * The full survey map screen. The fragment hosts the Google Maps lifecycle (MapView pass-throughs,
 * GoogleMap ready/destroy), navigation, the coordinate data binding and the coordination between
 * the map components in this package:
 *
 * - [CoordinateMarkerLayer] saved-point markers + visibility, [MapOverlayController] grid/labels,
 * - [MapToolsController], [MapDrawerController], [MapCameraController], [SelectedCoordinateController],
 * - [LiveTrackingController] live GNSS position, [CompassHeadingController] the shared compass,
 * - [StakeoutController] stakeout target, readout and guidance.
 *
 * Shared state has one owner each: the GoogleMap/MapView here; coordinates + visibility in the
 * marker layer; the selected id in the selection controller; the latest fix in live tracking; the
 * stakeout target in the stakeout controller. Camera/UI session state is kept by
 * [MapUiStateViewModel]; grid/label math lives in pure helpers ([MapGrid], [PointLabel]).
 */
@AndroidEntryPoint
class RenderMapFragment : Fragment() {

    companion object {
        private const val TAG = "RenderMapFragment"
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
    private var fragmentResumed = false

    // ── Map components ─────────────────────────────────────────────────────────
    // Created lazily (after Hilt injection), one per fragment instance: their session state survives
    // view recreation, and each drops its views / map objects in its onDestroyView().

    // Saved-coordinate markers: the one owner of the loaded coordinates, markers and visibility.
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
            liveLatLng = { live.lastFixLatLng },
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
            liveLatLng = { live.lastFixLatLng },
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
            liveLatLng = { live.lastFixLatLng },
            onSelectionChanged = { id -> drawer.setSelectedId(id) },
            logMapEvent = ::logMapEvent,
            refreshMapDiag = ::refreshMapDiag,
        )
    }

    // Live GNSS position (owner of the latest fix): marker, accuracy circle, trail, heading arrow.
    private val live: LiveTrackingController by lazy {
        LiveTrackingController(
            map = { googleMap },
            context = { context },
            fixes = fixSwitchboard.fixes,
            fixStale = fixSwitchboard.fixStale,
            activeProvider = sourceSettings.activeProvider,
            locationSource = locationSourceRepo.locationSource,
            compassHeading = { compass.compassHeadingDeg },
            onLivePosition = { pos ->
                try { stakeout.updateStakeoutCalculations(pos) } catch (e: Exception) { logFail("live.updateStakeoutCalculations", e) }
                // Distance labels follow the live position, throttled to avoid heavy marker rebuilds.
                overlays.refreshDistanceLabelsIfDue()
            },
        )
    }

    // TRUE heading (declination at the latest FixSwitchboard fix, display rotation, flat/upright).
    private val headingProvider = HeadingProvider(
        latestFix = { live.currentFix },
        // Before the first fix: declination from the last known position (changes little over tens of km).
        lastKnownPosition = { if (::lastKnownPositionStore.isInitialized) lastKnownPositionStore.get() else null },
    )

    // The one rotation-vector listener, shared by stakeout guidance and the My Location arrow.
    private val compass: CompassHeadingController by lazy {
        CompassHeadingController(
            sensorManagerProvider = {
                requireContext().getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            },
            displayRotation = { view?.display?.rotation ?: android.view.Surface.ROTATION_0 },
            headingProvider = headingProvider,
            onHeadingChanged = { live.updateHeadingIndicator() },
        )
    }

    // Stakeout target, card readout and guidance (haptics/audio/keep-screen-on/stale watcher).
    private val stakeout: StakeoutController by lazy {
        StakeoutController(
            map = { googleMap },
            view = { view },
            scope = { viewLifecycleOwner.lifecycleScope },
            coordinateSystemService = coordinateSystemService,
            currentFix = { live.currentFix },
            liveLatLng = { live.lastFixLatLng },
            compassHeading = { compass.compassHeadingDeg },
            isMagneticOnly = { compass.isMagneticOnly },
            logHeadingSource = { source -> compass.logHeadingSourceIfChanged(source) },
            syncCompass = ::syncCompass,
            logMapEvent = ::logMapEvent,
            showSnackbar = ::showSnackbar,
            loadSession = { mapUiVm.state.stakeout },
            saveSession = { s -> mapUiVm.update { it.copy(stakeout = s) } },
            isFixStale = { fixSwitchboard.fixStale.value },
        )
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
            live.restoreShowCurrentLocation(s.showCurrentLocation)
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
                MapRuntimeDiagnostics.update { it.copy(mapLoaded = true) }
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
            live.collectFixes(viewLifecycleOwner)
            setupMapClickListener()
            stakeout.onMapReady()   // a restored stakeout's target marker on the new map
            MapRuntimeDiagnostics.update {
                it.copy(cameraRestored = cameraRestored)
            }
            logMapEvent("MAP", "Map ready type=${MapTypes.label(tools.currentMapType)} cameraRestored=$cameraRestored")
        }

        // Map Tools overlay wiring. The toggle slides the panel in/out (AR pattern); each row
        // delegates to the SAME existing map action handlers — only the presentation changed.
        tools.bindViews(root, live.showCurrentLocationOnMap, onMyLocationClick = { toggleMyLocationVisibility() })
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
        stakeout.bindViews(view)      // stakeout card + guidance controls; restores a running stakeout

        // Keep the latest stakeout settings in memory for the guidance loop, and apply changes to
        // running guidance immediately (e.g. toggling audio/compass/keep-screen-on mid-stakeout).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                stakeoutSettingsRepo.stakeoutSettings.collect {
                    stakeout.onSettingsChanged(it)
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
        live.observeProviderChanges(viewLifecycleOwner)
    }

    // ── Lifecycle pass-throughs ────────────────────────────────────────────────

    override fun onStart()  { super.onStart();  try { mapView?.onStart() } catch (e: Exception) { logFail("onStart", e) } }
    override fun onLowMemory() { super.onLowMemory(); try { mapView?.onLowMemory() } catch (e: Exception) { logFail("onLowMemory", e) } }
    override fun onResume() {
        super.onResume()
        try { mapView?.onResume(); fragmentResumed = true; stakeout.resumeGuidanceFeedback(); syncCompass() }
        catch (e: Exception) { logFail("onResume", e) }
    }
    override fun onPause() {
        try { camera.saveCameraState(); mapView?.onPause(); fragmentResumed = false; stakeout.pauseGuidanceFeedback(); syncCompass() }
        catch (e: Exception) { logFail("onPause", e) }
        super.onPause()
    }

    override fun onStop() {
        try { live.onStop(); mapView?.onStop() } catch (e: Exception) { logFail("onStop", e) }
        super.onStop()
    }

    override fun onDestroyView() {
        try {
            fragmentResumed = false
            compass.unregister()   // make sure the shared sensor is released with the view
        } catch (e: Exception) { logFail("onDestroyView cleanup", e) }
        // Map objects go before mapView.onDestroy(); each component catches/logs its own cleanup.
        // Stakeout: releases tone/keep-screen-on/stale watcher and the line, drops the marker + card
        // views; the stakeout (and guidance mode) is kept and restored by the next view.
        stakeout.onDestroyView()
        live.onDestroyView()       // trail, accuracy circle, current marker, heading arrow
        overlays.onDestroyView()   // grid lines, label markers + label cache, Grid/Labels rows
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

    /**
     * Single source of compass truth shared by stakeout guidance AND the My Location heading arrow.
     * Registers exactly ONE rotation-vector listener when any feature needs it and the fragment is
     * resumed; unregisters when nothing needs it. Idempotent — safe to call from any of the callers.
     */
    private fun syncCompass() {
        compass.sync(CompassHeadingController.isNeeded(
            resumed = fragmentResumed,
            guidanceActive = stakeout.isGuidanceActive,
            guidanceUsesCompass = stakeout.settings.guidanceUsesCompassHeading,
            showMyLocation = live.showCurrentLocationOnMap,
        ))
    }

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
                    stakeout.revalidateTarget(points)
                    return@observe
                }
                drawer.hidePlaceholder()

                // Remove markers for coordinates that were deleted
                val newIds = points.map { it.id }.toHashSet()
                markers.removeMissing(newIds)
                // Stakeout target: deleted (also while away from the map) → end stakeout with an
                // explanation; moved → follow it; first load after a restore → draw its marker.
                stakeout.revalidateTarget(points)

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
            MapRuntimeDiagnostics.update {
                it.copy(
                    mapReady = googleMap != null,
                    mapType = MapTypes.label(tools.currentMapType),
                    mapToolsOpen = tools.mapToolsVisible,
                    gridMode = overlays.gridMode.label,
                    gridSpacing = overlays.currentGridSpacingM?.let { m -> MapGrid.formatSpacing(m) },
                    pointLabelMode = overlays.pointLabelMode.label,
                    currentLocationVisible = live.showCurrentLocationOnMap,
                    headingAvailable = live.headingAvailable,
                    accuracyCircleVisible = live.accuracyCircleVisible,
                    markersTotal = total,
                    markersVisible = visible,
                    selectedActive = selection.selectedCoordinateId != null,
                    stakeoutActive = stakeout.isStakeoutMode,
                    guidanceActive = stakeout.isGuidanceActive,
                )
            }
        } catch (e: Exception) { logFail("diagnostic snapshot refresh", e) }
    }

    // ── My Location display toggle (map display only) ──────────────────────────

    private fun toggleMyLocationVisibility() {
        val show = !live.showCurrentLocationOnMap
        mapUiVm.update { it.copy(showCurrentLocation = show) }
        tools.updateMyLocationRowState(show)
        live.setShowCurrentLocation(show)   // shows/hides the live overlays only
        syncCompass()   // My Location heading shares the one compass listener
        logMapEvent("MAP", "Map location overlay ${if (show) "on" else "off"}")
        showSnackbar(if (show) "Showing my location" else "Hiding my location")
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

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
        stakeout.startForCoordinate(coord)
        selection.dismissSelectedCoordinate()
    }

    private fun setupMapClickListener() {
        googleMap?.setOnMapClickListener { latLng ->
            // In stakeout mode a tap sets a free target; otherwise it closes the selected-point card.
            if (!stakeout.onMapTap(latLng)) selection.dismissSelectedCoordinate()
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
