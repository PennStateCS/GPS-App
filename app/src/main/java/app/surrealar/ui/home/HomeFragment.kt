/**
 * Fragment for the Home screen - the main entry point of the surveying app.
 *
 * This demonstrates key Android Fragment concepts:
 * - Fragment lifecycle: onCreateView, onDestroyView
 * - View binding: Safe way to access views without findViewById
 * - MVVM pattern: Fragment (View) observes ViewModel for data changes
 * - Observer pattern: UI automatically updates when LiveData changes
 */
package app.surrealar.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import app.surrealar.R
import app.surrealar.SurRealApplication
import app.surrealar.databinding.FragmentHomeBinding
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.repository.ModelRepository
import app.surrealar.domain.repository.SettingsRepository
import io.github.pennstatecs.geokit.gnss.model.FixSnapshot
import app.surrealar.gnss.diagnostics.NmeaLogStats
import app.surrealar.settings.model.ExternalReceiverSettings
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.ui.components.FixBadgeView
import app.surrealar.ui.map.MapThemeHelper
import app.surrealar.ui.navigation.topLevelNavOptions
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.LocationSource
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.DevLog
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HomeFragment"

/**
 * Max age of a fix (vs. its UTC timestamp) before the Home map ignores it. The fixes flow
 * has replay=1, so right after a source switch the previous provider's last fix can replay.
 * This gate keeps the map from jumping to that stale position. Mirrors the toolbar's guard.
 */
private const val MAP_FIX_MAX_AGE_MS = 15_000L

/**
 * Home dashboard screen: hosts the map and the plain-language Field Status card. Observes
 * `HomeViewModel` and renders the summary from `HomeFieldStatusMapper`; holds no GNSS logic itself.
 */
@AndroidEntryPoint
class HomeFragment : Fragment(), OnMapReadyCallback {

    // Inject FixSwitchboard using Hilt
    @Inject
    lateinit var fixSwitchboard: FixSwitchboard

    // Active-provider state, used to reset the live map location when the source switches.
    @Inject
    lateinit var sourceSettings: app.surrealar.gnss.source.SourceSettings

    // ViewModel injection using Hilt
    private val viewModel: HomeViewModel by viewModels()

    // View binding - safer than findViewById, automatically set to null when view is destroyed
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Fragment binding is null - view may have been destroyed")
    // Nullable and cleared in onDestroyView: Home stays on the back stack while other screens are open,
    // and a lateinit would keep the destroyed MapView (and its view tree) alive and callable.
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null

    // Custom LocationSource to feed app Fixes into the map's My Location dot
    private var mapLocationSource: LocationSource? = null
    @Volatile private var onLocationChangedListener: LocationSource.OnLocationChangedListener? = null
    private var hasCenteredCamera = false // Always starts false
    private val desiredFollowZoom = 18f
    private var lastCameraMoveMs = 0L
    private var lastCameraLocation: LatLng? = null
    private var loggedFirstMapFix = false // logs the first current-source fix applied to the map
    private var initialCameraKind = "none" // last initial-camera choice, reported in the MAP_LOADED line

    // Fix Badge component
    private var fixBadge: FixBadgeView? = null

    // Repositories — injected via Hilt (domain interfaces), no longer constructed from AppDatabase.
    @Inject lateinit var coordinateRepository: CoordinateRepository
    @Inject lateinit var lastKnownPositionStore: app.surrealar.gnss.lastknown.LastKnownPositionStore
    @Inject lateinit var modelRepository: ModelRepository
    @Inject lateinit var settingsRepo: SettingsRepository

    // Settings repository reference (still needed for settings)

    /**
     * Called when the fragment needs to create its view hierarchy.
     * This is where we inflate the layout and set up the UI components.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout using view binding
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Force reset of camera flag on view creation. loggedFirstMapFix is NOT reset here: the
        // fragment survives on the back stack, so the first-fix line is logged once per fragment
        // (and again after a provider switch), not on every return to Home.
        hasCenteredCamera = false


        // Initialize UI components
        fixBadge = binding.fixBadge

        // Initialize map
        mapView = binding.mapViewMini
        try {
            mapView?.onCreate(savedInstanceState)
            mapView?.getMapAsync(this)
        } catch (e: Exception) {
            DiagnosticsLogger.e(DiagTags.MAP, "Home map view init failed", e)
        }

        // Set up UI
        setupQuickActionButtons()
        setupLocationStatusObservers()
        setupFixBadgeObservers()
        setupFieldStatusObserver()
        setupMapUiControls()
        collectStatisticsFlows()


        return root
    }

    /** Opens a top-level screen the same way the drawer does (no stacked copies). */
    private fun navigateTopLevel(destId: Int) {
        val nav = findNavController()
        nav.navigate(destId, null, topLevelNavOptions(nav.graph.startDestinationId))
    }

    private fun logNavFailure(target: String, e: Exception) =
        DiagnosticsLogger.e(DiagTags.NAV, "Home navigation failed target=$target", e)

    private fun setupQuickActionButtons() {
        // Quick Actions chips
        binding.chipQuickMap.setOnClickListener {
            try { navigateTopLevel(R.id.nav_render_map) }
            catch (e: Exception) { logNavFailure("map", e) }
        }

        // Current Location card header action — same destination as the Quick Actions chip,
        // giving the prominent map preview a clear "this opens the full map" affordance.
        binding.btnOpenFullMap.setOnClickListener {
            try { navigateTopLevel(R.id.nav_render_map) }
            catch (e: Exception) { logNavFailure("map", e) }
        }

        binding.chipQuickAr.setOnClickListener {
            try { navigateTopLevel(R.id.nav_open_in_ar) }
            catch (e: Exception) { logNavFailure("AR", e) }
        }

        binding.chipQuickSettings.setOnClickListener {
            try { navigateTopLevel(R.id.nav_settings) }
            catch (e: Exception) { logNavFailure("settings", e) }
        }

        // View chips for Survey Data card
        binding.chipViewCoordinates.setOnClickListener {
            try { navigateTopLevel(R.id.nav_view_coordinates) }
            catch (e: Exception) { logNavFailure("coordinates", e) }
        }

        binding.chipViewModels.setOnClickListener {
            try { navigateTopLevel(R.id.nav_models) }
            catch (e: Exception) { logNavFailure("models", e) }
        }
    }

    private fun setupLocationStatusObservers() {
        // Drive the live map from switchboard.currentFix — the nullable, current-provider-only
        // state. It is cleared to null the instant a provider switch begins and is generation-
        // guarded at the source, so it can never carry the previous provider's last fix. A null
        // value means "no live fix from the current provider yet" → leave the map where it is and
        // wait. We still pair with the SELECTED source to drop internal fixes during the
        // External-connecting window, and re-apply the stale/valid guards defensively.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    combine(
                        fixSwitchboard.currentFix,
                        settingsRepo.locationSource
                    ) { fix, source -> fix to source }
                        .conflate()
                        .sample(500)
                        .collect { (fix, source) ->
                            // No live position from the GNSS feed (acquiring, signal lost, source
                            // switch): hide the dot instead of leaving it frozen where it last was.
                            if (fix == null) {
                                setLiveDotVisible(false); showGnssWaitingIfNoLiveFix(); return@collect
                            }
                            if (!fixMatchesSource(source, fix)) {
                                setLiveDotVisible(false); showGnssWaitingIfNoLiveFix()
                                logMapIgnore("wrong-provider"); return@collect
                            }
                            if (isFixStale(fix)) { logMapIgnore("stale"); return@collect }
                            if (fix.latDeg !in -90.0..90.0 || fix.lonDeg !in -180.0..180.0) {
                                logMapIgnore("invalid-coords"); return@collect
                            }
                            updateMapLocation(fix)
                        }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    DiagnosticsLogger.e(DiagTags.MAP, "Home map fix collector failed: map no longer follows GNSS", e)
                }
            }
        }

        // Reset the live map location whenever the active provider actually changes, so the
        // camera never animates from one provider's position toward another's, and re-centers
        // on the first valid fix from the new source. Saved coordinate markers are untouched.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previousProvider: app.surrealar.gnss.source.SourceSettings.ProviderChoice? = null
                sourceSettings.activeProvider.collect { provider ->
                    if (previousProvider != null && previousProvider != provider) {
                        // The source switch itself is logged once by GnssFeedTracker; this is detail.
                        val from = previousProvider
                        DiagnosticsLogger.d(DiagTags.MAP) { "Home map live location reset: provider $from → $provider" }
                        resetMapLiveLocation()
                    }
                    previousProvider = provider
                }
            }
        }
    }

    /**
     * Clears live-location tracking state so the next valid fix from the new provider re-centers
     * the camera instead of the map animating across from the old provider's position. Does NOT
     * remove saved coordinate markers.
     */
    private fun resetMapLiveLocation() {
        hasCenteredCamera = false
        lastCameraLocation = null
        loggedFirstMapFix = false
    }

    /**
     * Debug-only, throttled per reason (≤ once a minute) trace for fixes the map dropped. Dropping
     * wrong-provider fixes is normal while an External source connects, and the toolbar already logs
     * the same gate (MainActivity.logToolbarIgnore), so this is not an event-log line.
     */
    private fun logMapIgnore(reason: String) {
        DiagnosticsLogger.every(DiagTags.MAP, "home.ignore.$reason", 60_000L, 'D') { "Home map ignored fix reason=$reason" }
    }

    /** True when [fix]'s provider matches the user-selected [source]. */
    private fun fixMatchesSource(source: LocationSourceType, fix: Fix): Boolean =
        if (source == LocationSourceType.INTERNAL) {
            fix.provider == Provider.INTERNAL
        } else {
            fix.provider != Provider.INTERNAL
        }

    /**
     * Staleness is now enforced upstream: [app.surrealar.gnss.bus.FixSwitchboard.currentFix] goes null
     * when fixes stop arriving (judged by receive time on a monotonic clock). Comparing the fix's GNSS
     * timestamp with the tablet clock hid a perfectly live dot whenever the device clock was skewed.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun isFixStale(fix: Fix): Boolean = false

    private fun setupFixBadgeObservers() {
        // Observe fix snapshot for GNSS quality indicators
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.fixSnapshot.collect { fixSnapshot ->
                        fixBadge?.updateFixData(fixSnapshot)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    DiagnosticsLogger.e(DiagTags.GNSS, "Home fix badge collector failed: fix snapshot", e)
                }
            }
        }

        // Observe NMEA statistics for stream health indicators
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.nmeaStats.collect { nmeaStats ->
                        fixBadge?.updateStreamHealth(nmeaStats)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    DiagnosticsLogger.e(DiagTags.GNSS, "Home fix badge collector failed: NMEA stats", e)
                }
            }
        }
    }

    /**
     * Polished, plain-language "Field Status" summary. Combines the live fix snapshot, selected
     * source/receiver profile, and NMEA stream stats the rest of Home already observes, runs them
     * through the pure [HomeFieldStatusMapper], and renders a stable card: headline + two detail
     * lines + a compact chip row. Read-only: no GNSS state is changed here.
     */
    /** Current header-action destination for the Field Status card (updated on each render). */
    private var currentFieldStatusAction = HomeFieldStatusMapper.Action.OPEN_MAP

    private fun setupFieldStatusObserver() {
        binding.btnFieldStatusAction.setOnClickListener {
            val dest = when (currentFieldStatusAction) {
                HomeFieldStatusMapper.Action.RECEIVER_SETTINGS -> R.id.nav_settings
                HomeFieldStatusMapper.Action.OPEN_MAP -> R.id.nav_render_map
            }
            try { navigateTopLevel(dest) }
            catch (e: Exception) { logNavFailure("field status action", e) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    combine(
                        viewModel.fixSnapshot,
                        settingsRepo.locationSource,
                        settingsRepo.externalReceiverSettings,
                        viewModel.diagnosticData,
                        viewModel.sky
                    ) { snapshot, source, receiver, diag, sky ->
                        buildFieldStatus(snapshot, source, receiver, diag, sky)
                    }.distinctUntilChanged()   // diagnostics tick every second; rebuild the chips only on a real change
                        .collectLatest { status ->
                        val b = _binding ?: return@collectLatest
                        renderFieldStatus(b, status)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    DiagnosticsLogger.e(DiagTags.GNSS, "Home field status collector failed", e)
                }
            }
        }
    }

    private fun buildFieldStatus(
        snapshot: FixSnapshot,
        source: LocationSourceType,
        receiver: ExternalReceiverSettings,
        diag: app.surrealar.gnss.diagnostics.DiagnosticData,
        sky: io.github.pennstatecs.geokit.gnss.model.SkySnapshot,
    ): HomeFieldStatusMapper.FieldStatus {
        // A usable fix is signalled by valid coordinates — NOT by comparing the fix's GNSS/UTC
        // timestamp to the device wall clock, which can be skewed (or even ahead of) device time
        // and would wrongly report a live position as "waiting". The app elsewhere shows the fix
        // and merely annotates staleness rather than withholding it.
        val hasRecentFix = snapshot.lat != null && snapshot.lon != null && snapshot.timestampMillis > 0
        val address = receiver.tcpHost.takeIf { it.isNotBlank() }?.let { "$it:${receiver.tcpPort}" }
        return HomeFieldStatusMapper.map(
            HomeFieldStatusMapper.Inputs(
                isInternal = source == LocationSourceType.INTERNAL,
                externalLabel = receiver.profile.shortLabel,
                receiverName = receiver.displayName,
                connectionAddress = address,
                rtkStatus = RtkStatus.fromPrefKey(snapshot.rtkStatus),
                hAccM = snapshot.horizontalAccuracyM,
                vAccM = snapshot.verticalAccuracyM,
                satsUsed = snapshot.satsUsed,
                satsVisible = snapshot.satellitesInView,
                skyTotalUsed = sky.totalUsed,
                skyTotalVisible = sky.totalVisible,
                hdop = snapshot.hdop,
                pdop = snapshot.pDop,
                correctionAgeS = snapshot.correctionAgeS,
                correctionStationId = snapshot.correctionStationId,
                // Real live NMEA stream rate + parse-error count from the DiagnosticsService
                // singleton (the source the receiver pipeline actually feeds).
                nmeaLinesPerSecond = diag.linesPerSecond,
                nmeaParseErrors = diag.totalParseErrors,
                hasRecentFix = hasRecentFix,
            )
        ).also { status ->
            app.surrealar.gnss.debug.GnssDebugLog.every("home.fieldStatus", "toolbar") {
                "Home Field Status: '${status.headline}' / '${status.primaryDetail}' / '${status.receiverDetail}' " +
                    "chips=${status.chips.map { it.label }} | sky=${sky.totalUsed}/${sky.totalVisible} " +
                    "nmea=${"%.1f".format(diag.linesPerSecond)}/s errors=${diag.totalParseErrors}"
            }
        }
    }

    private fun renderFieldStatus(
        b: FragmentHomeBinding,
        status: HomeFieldStatusMapper.FieldStatus,
    ) {
        // Headline: neutral severity reads as the brand primary (calm "waiting"/"internal" states).
        val headlineColor = severityColor(b.root, status.severity, androidx.appcompat.R.attr.colorPrimary)
        b.textFieldStatusHeadline.text = status.headline
        b.textFieldStatusHeadline.setTextColor(headlineColor)
        b.fieldStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(headlineColor)

        b.textFieldStatusDetailPrimary.text = status.primaryDetail
        b.textFieldStatusReceiver.text = status.receiverDetail

        currentFieldStatusAction = status.actionDestination
        b.btnFieldStatusAction.text = status.actionLabel
        b.btnFieldStatusAction.contentDescription = status.actionLabel

        renderFieldStatusChips(b.groupFieldStatusChips, status.chips)
    }

    private fun renderFieldStatusChips(
        group: com.google.android.material.chip.ChipGroup,
        chips: List<HomeFieldStatusMapper.Chip>,
    ) {
        group.removeAllViews()
        val inflater = LayoutInflater.from(group.context)
        for (chip in chips) {
            val view = inflater.inflate(R.layout.item_home_status_chip, group, false)
                as com.google.android.material.chip.Chip
            view.text = chip.label
            // Chips keep a uniform subtle background; only the text is severity-colored (neutral =
            // onSurfaceVariant) so contrast stays strong and meaning never relies on color alone.
            view.setTextColor(
                severityColor(group, chip.severity, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            group.addView(view)
        }
    }

    /** Resolve a severity to a color; [neutralAttr] is the theme attr used for NEUTRAL. */
    private fun severityColor(view: View, severity: HomeFieldStatusMapper.Severity, neutralAttr: Int): Int =
        when (severity) {
            HomeFieldStatusMapper.Severity.GOOD    -> view.context.getColor(R.color.app_success)
            HomeFieldStatusMapper.Severity.CAUTION -> view.context.getColor(R.color.app_warning)
            HomeFieldStatusMapper.Severity.WARNING -> view.context.getColor(R.color.app_error)
            HomeFieldStatusMapper.Severity.NEUTRAL -> com.google.android.material.color.MaterialColors.getColor(view, neutralAttr)
        }

    private fun setupMapUiControls() {
        // Map type cycler
        binding.chipMapType.setOnClickListener {
            try {
                googleMap?.let { map ->
                    map.mapType = nextMapType(map.mapType)
                    context?.let { MapThemeHelper.applyTheme(it, map, map.mapType) }
                    binding.chipMapType.text = mapTypeName(map.mapType)
                }
            } catch (e: Exception) {
                DiagnosticsLogger.w(DiagTags.MAP, "Home map type change failed", e)
            }
        }

        // 3D toggle
        binding.chipToggle3d.setOnCheckedChangeListener { _, isChecked ->
            try { apply3D(enable = isChecked, animate = true) }
            catch (e: Exception) { DiagnosticsLogger.w(DiagTags.MAP, "Home map 3D toggle failed", e) }
        }
    }

    private fun nextMapType(current: Int): Int {
        val order = intArrayOf(
            GoogleMap.MAP_TYPE_NORMAL,
            GoogleMap.MAP_TYPE_SATELLITE,
            GoogleMap.MAP_TYPE_TERRAIN,
            GoogleMap.MAP_TYPE_HYBRID
        )
        val i = order.indexOf(current)
        return if (i == -1 || i == order.lastIndex) order.first() else order[i + 1]
    }

    private fun mapTypeName(mapType: Int): String = when (mapType) {
        GoogleMap.MAP_TYPE_NORMAL    -> "Normal"
        GoogleMap.MAP_TYPE_SATELLITE -> "Satellite"
        GoogleMap.MAP_TYPE_TERRAIN   -> "Terrain"
        GoogleMap.MAP_TYPE_HYBRID    -> "Hybrid"
        else -> "Map"
    }

    private fun apply3D(enable: Boolean, animate: Boolean) {
        try {
            val map = googleMap ?: return
            map.isBuildingsEnabled = true
            val cam = map.cameraPosition
            val desiredTilt = if (enable) 60f else 0f
            val desiredBearing = if (enable) cam.bearing.takeIf { it != 0f } ?: 0f else 0f
            val builder = CameraPosition.Builder(cam).tilt(desiredTilt).bearing(desiredBearing)
            // Slightly increase zoom for better perspective if enabling and too low
            val minZoomFor3D = 16f
            if (enable && cam.zoom < minZoomFor3D) builder.zoom(minZoomFor3D)
            val update = CameraUpdateFactory.newCameraPosition(builder.build())
            if (animate) map.animateCamera(update) else map.moveCamera(update)
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.MAP, "Home map 3D apply failed enable=$enable", e)
        }
    }

    private fun collectStatisticsFlows() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    coordinateRepository.coordinateCountFlow.collectLatest { count ->
                        _binding?.textCoordinatesCount?.text = count.toString()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    DiagnosticsLogger.e(DiagTags.DATA, "Home coordinate count collector failed", e)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    modelRepository.observeModelCount().collectLatest { count ->
                        _binding?.textModelsCount?.text = count.toString()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    DiagnosticsLogger.e(DiagTags.DATA, "Home model count collector failed", e)
                }
            }
        }
    }


    private fun shouldMoveHomeCamera(newLocation: LatLng): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCameraMoveMs < 2_000) return false
        val prev = lastCameraLocation ?: return true
        // WGS84 geodesic (geokit-core), the project's one distance function.
        return io.github.pennstatecs.geokit.ellipsoid.Geodesic.distanceM(
            prev.latitude, prev.longitude, newLocation.latitude, newLocation.longitude,
        ) >= 5.0
    }

    /**
     * Shows/hides Google Maps' My Location dot. The dot's position comes ONLY from the GNSS feed
     * (custom [LocationSource]); a LocationSource cannot "clear" a position, so hiding the layer is
     * the only way to stop showing a dot when the feed has no live fix. Requires location permission.
     */
    private fun setLiveDotVisible(visible: Boolean) {
        val map = googleMap ?: return
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return
        try {
            if (map.isMyLocationEnabled != visible) map.isMyLocationEnabled = visible
        } catch (e: SecurityException) {
            // Called per fix: one line a minute is enough to show a repeating failure.
            DiagnosticsLogger.every(DiagTags.MAP, "home.myLocation", 60_000L, 'W', e) { "Home map My Location layer toggle failed visible=$visible" }
        }
    }

    private fun updateMapLocation(fix: Fix?) {
        try {
            if (fix != null && googleMap != null) {
                if (fix.latDeg !in -90.0..90.0 || fix.lonDeg !in -180.0..180.0) return
                val locationForDot = fixToLocation(fix)
                setLiveDotVisible(true)
                showGnssStatusChip(null)   // live position: no waiting chip
                onLocationChangedListener?.onLocationChanged(locationForDot)

                if (!loggedFirstMapFix) {
                    loggedFirstMapFix = true
                    // One line per fragment / provider switch. No coordinates in the diagnostic log (project rule).
                    DiagnosticsLogger.i(DiagTags.MAP, "Home map first fix applied provider=${fix.provider} " +
                        "status=${fix.rtkStatus} hAccM=${fix.hAccM?.let { "%.3f".format(java.util.Locale.US, it) } ?: "unknown"} " +
                        "sats=${fix.satsUsed ?: "unknown"}")
                }

                val location = LatLng(fix.latDeg, fix.lonDeg)
                val map = googleMap
                if (map != null) {
                    if (!hasCenteredCamera) {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(location, desiredFollowZoom))
                        hasCenteredCamera = true
                        lastCameraMoveMs = System.currentTimeMillis()
                        lastCameraLocation = location
                    } else if (shouldMoveHomeCamera(location)) {
                        val currentZoom = map.cameraPosition.zoom
                        val update = if (currentZoom < desiredFollowZoom)
                            CameraUpdateFactory.newLatLngZoom(location, desiredFollowZoom)
                        else
                            CameraUpdateFactory.newLatLng(location)
                        map.animateCamera(update)
                        lastCameraMoveMs = System.currentTimeMillis()
                        lastCameraLocation = location
                    }
                }

                binding.layoutMapPlaceholder.visibility = View.GONE
                binding.mapViewMini.visibility = View.VISIBLE
            } else if (googleMap != null) {
                binding.layoutMapPlaceholder.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            // Runs per fix: log the failure at most once a minute.
            DiagnosticsLogger.every(DiagTags.MAP, "home.updateMap", 60_000L, 'E', e) { "Home map location update failed" }
        }
    }

    /**
     * Positions the camera before the first live fix. The camera is moved instantly (no animation)
     * so the map never visibly starts somewhere unrelated; the first live fix then animates to the
     * user as before ([hasCenteredCamera] stays false for non-live choices).
     */
    private fun applyInitialCamera(map: GoogleMap) {
        val live = fixSwitchboard.currentFix.value
            ?.takeIf { it.latDeg in -90.0..90.0 && it.lonDeg in -180.0..180.0 }
            ?.let { it.latDeg to it.lonDeg }
        val lastKnown = runCatching { lastKnownPositionStore.get() }
            .onFailure { DiagnosticsLogger.w(DiagTags.MAP, "Home map last-known position read failed", it) }
            .getOrNull()
        // Saved points are only needed when there is neither a live fix nor a last position.
        if (live != null || lastKnown != null) {
            moveToInitial(map, HomeInitialCamera.choose(live, lastKnown, emptyList()))
            return
        }
        moveToInitial(map, HomeInitialCamera.Overview)   // instant placeholder while points load
        viewLifecycleOwner.lifecycleScope.launch {
            val points = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    coordinateRepository.getAllCoordinatesList().map { it.latitude to it.longitude }
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                DiagnosticsLogger.w(DiagTags.MAP, "Home map saved points read failed", it)
            }.getOrDefault(emptyList())
            // A live fix may have arrived meanwhile — don't pull the camera away from it.
            if (!hasCenteredCamera && googleMap === map) {
                moveToInitial(map, HomeInitialCamera.choose(null, null, points))
            }
        }
    }

    private fun moveToInitial(map: GoogleMap, choice: HomeInitialCamera) {
        try {
            when (choice) {
                is HomeInitialCamera.Live -> {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(choice.lat, choice.lon), desiredFollowZoom))
                    hasCenteredCamera = true
                    lastCameraLocation = LatLng(choice.lat, choice.lon)
                    lastCameraMoveMs = System.currentTimeMillis()
                }
                is HomeInitialCamera.LastKnown ->
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(choice.lat, choice.lon), desiredFollowZoom))
                is HomeInitialCamera.SavedPoints -> {
                    if (choice.points.size == 1) {
                        val (lat, lon) = choice.points.first()
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16f))
                    } else {
                        val bounds = com.google.android.gms.maps.model.LatLngBounds.builder().apply {
                            choice.points.forEach { (lat, lon) -> include(LatLng(lat, lon)) }
                        }.build()
                        // newLatLngBounds(bounds, w, h, padding) works before the map is laid out.
                        val w = mapView?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                        val h = mapView?.height?.takeIf { it > 0 } ?: (220 * resources.displayMetrics.density).toInt()
                        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, w, h, (32 * resources.displayMetrics.density).toInt()))
                    }
                }
                HomeInitialCamera.Overview ->
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(HomeInitialCamera.Overview.LAT, HomeInitialCamera.Overview.LON), HomeInitialCamera.Overview.ZOOM))
            }
            showGnssStatusChip(choice.waitingText)
            // Reported once in the MAP_LOADED line; the step itself is debug detail.
            initialCameraKind = choice::class.simpleName ?: "unknown"
            DiagnosticsLogger.d(DiagTags.MAP) { "Home map initial camera set camera=$initialCameraKind" }
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.MAP, "Home map initial camera move failed choice=${choice::class.simpleName}", e)
        }
    }

    /**
     * No live fix from the selected source (acquiring, signal lost, source switch): say so on the map.
     * Keeps the more specific "Last known position" text if the camera is still parked there.
     */
    private fun showGnssWaitingIfNoLiveFix() {
        if (googleMap == null) return
        val chip = _binding?.chipMapGnssStatus ?: return
        if (chip.visibility == View.VISIBLE && !hasCenteredCamera) return
        showGnssStatusChip(if (hasCenteredCamera) "GNSS signal lost · waiting…" else "Waiting for GNSS…")
    }

    /** Shows [text] on the map's GNSS status chip, or hides it when null. */
    private fun showGnssStatusChip(text: String?) {
        val chip = _binding?.chipMapGnssStatus ?: return
        if (text == null) {
            chip.visibility = View.GONE
        } else {
            chip.text = text
            chip.visibility = View.VISIBLE
        }
    }

    private fun fixToLocation(fix: Fix): Location {
        return try {
            val loc = Location("LocationManagerFix")
            loc.latitude = fix.latDeg
            loc.longitude = fix.lonDeg
            fix.altEllipsoidalM?.let { loc.altitude = it }
            fix.hAccM?.let { loc.accuracy = it.toFloat() }
            fix.courseDeg?.let { loc.bearing = it.toFloat() }
            fix.speedMps?.let { loc.speed = it.toFloat() }
            loc.time = fix.timeUtc.toEpochMilli()
            loc
        } catch (e: Exception) {
            DiagnosticsLogger.every(DiagTags.MAP, "home.fixToLocation", 60_000L, 'W', e) { "Home map fix conversion failed: using bare location" }
            Location("LocationManagerFix").also {
                it.latitude = fix.latDeg
                it.longitude = fix.lonDeg
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(map: GoogleMap) {
        try {
            // MAP_READY is debug detail; the one event-log line per map open is MAP_LOADED (or the
            // 10 s timeout warning), which carries the renderer, network and initial camera.
            DiagnosticsLogger.d(DiagTags.MAP) { "MAP_READY map=home renderer=${SurRealApplication.activeMapsRenderer}" }
            SurRealApplication.reportMapLoadStatus("MAP_READY")
            googleMap = map

            DevLog.d(TAG) {
                val playServicesVersion = try {
                    requireContext().packageManager.getPackageInfo("com.google.android.gms", 0).versionName ?: "unknown"
                } catch (_: Exception) { "unavailable" }   // best-effort trace only
                "onMapReady: Play Services version=$playServicesVersion"
            }

            // Network state at map open — internet-without-validation means tiles won't load (e.g. the
            // device is on a receiver Wi-Fi with no real internet). Network changes themselves are
            // logged by SystemEventsLogger; here it only goes into the MAP_LOADED / timeout line.
            val network = run {
                val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val active = cm.activeNetwork
                val caps = active?.let { cm.getNetworkCapabilities(it) }
                val hasInternet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val hasValidated = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                val isVpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                val isWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                val isCellular = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
                val transport = when { active == null -> "none"; isVpn -> "VPN"; isWifi -> "WiFi"; isCellular -> "cellular"; else -> "other" }
                "network=$transport internet=$hasInternet validated=$hasValidated"
            }
            DiagnosticsLogger.d(DiagTags.MAP) { "Home map connectivity $network" }

            // Set map type to Normal (shows streets/terrain instead of blank)
            map.mapType = GoogleMap.MAP_TYPE_NORMAL
            DevLog.d(TAG) {
                val nightMode = (requireContext().resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                "onMapReady: mapType=${map.mapType} nightMode=$nightMode, applying theme"
            }
            MapThemeHelper.applyTheme(requireContext(), map, map.mapType)

            // Opening camera (replaces the old hardcoded campus position): live fix → last GNSS
            // position → saved points → regional overview. See HomeInitialCamera.
            applyInitialCamera(map)

            // Log when tiles finish loading — a very short elapsed time (<100ms) means tiles
            // came back empty/gray rather than being fetched from the network.
            val mapReadyAt = System.currentTimeMillis()
            var mapTilesLoaded = false
            map.setOnMapLoadedCallback {
                mapTilesLoaded = true
                val elapsed = System.currentTimeMillis() - mapReadyAt
                val cam = map.cameraPosition
                // The one event-log line per Home map open. No camera target (coordinates).
                DiagnosticsLogger.i(DiagTags.MAP, "MAP_LOADED map=home elapsedMs=$elapsed renderer=${SurRealApplication.activeMapsRenderer} " +
                    "camera=$initialCameraKind zoom=${"%.1f".format(cam.zoom)} $network")
                SurRealApplication.reportMapLoadStatus("MAP_LOADED (${elapsed}ms)")
            }
            // Warn if tiles never load within 10 s (blank/gray map scenario)
            viewLifecycleOwner.lifecycleScope.launch {
                delay(10_000)
                if (!mapTilesLoaded) {
                    DiagnosticsLogger.w(DiagTags.MAP, "Home map tile load timed out: MAP_LOADED did not fire, tiles may be blank timeoutMs=10000 " +
                        "renderer=${SurRealApplication.activeMapsRenderer} $network")
                    SurRealApplication.reportMapLoadStatus("MAP_READY but MAP_LOADED did not fire (10s)")
                }
            }

            // Provide a custom LocationSource so the blue dot follows our GNSS location data
            mapLocationSource = object : LocationSource {
                override fun activate(listener: LocationSource.OnLocationChangedListener) {
                    DevLog.d(TAG) { "LocationSource activated — blue dot wired to GNSS stream" }
                    onLocationChangedListener = listener
                }
                override fun deactivate() {
                    DevLog.d(TAG) { "LocationSource deactivated" }
                    onLocationChangedListener = null
                }
            }
            map.setLocationSource(mapLocationSource)

            // Check location permission (required to show My Location layer)
            if (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                DevLog.d(TAG) { "onMapReady: location permission granted — My Location layer fed by GNSS" }
                // Shown once the GNSS feed has a live fix (setLiveDotVisible); hidden until then so
                // the map never shows a dot from any other source or a frozen position.
                map.isMyLocationEnabled = fixSwitchboard.currentFix.value != null
                map.uiSettings.isMyLocationButtonEnabled = false
                map.uiSettings.isZoomControlsEnabled = false
                map.uiSettings.isMapToolbarEnabled = false
                map.uiSettings.isTiltGesturesEnabled = true
                map.uiSettings.isRotateGesturesEnabled = true
                map.uiSettings.isCompassEnabled = true
                map.isBuildingsEnabled = true

                _binding?.let {
                    apply3D(enable = it.chipToggle3d.isChecked, animate = false)
                    it.layoutMapPlaceholder.visibility = View.GONE
                    it.mapViewMini.visibility = View.VISIBLE
                    it.chipMapType.text = mapTypeName(map.mapType)
                }
            } else {
                _binding?.let {
                    it.layoutMapPlaceholder.visibility = View.VISIBLE
                    it.root.findViewById<android.widget.TextView>(R.id.text_map_placeholder)
                        ?.text = "Location services required"
                }
                // Location permission state is logged by SystemEventsLogger; this is a developer trace.
                // If you see this and the map is blank: grant location permission and restart.
                DevLog.d(TAG) { "onMapReady: location permission not granted — placeholder shown, My Location disabled" }
            }

            // Apply the current-provider fix right now (if one exists), without waiting for the
            // 500ms sample window, so the camera centers immediately on first load. This reads
            // switchboard.currentFix — NOT the replay=1 buffer — so it can only ever apply a fix
            // from the active provider (it is null after a switch until the new source emits),
            // never a stale previous-provider position.
            fixSwitchboard.currentFix.value?.let { fix ->
                val validCoords = fix.latDeg in -90.0..90.0 && fix.lonDeg in -180.0..180.0
                when {
                    isFixStale(fix) ->
                        DevLog.d(TAG) { "onMapReady: current fix is stale — ignoring" }
                    !validCoords ->
                        DevLog.d(TAG) { "onMapReady: current fix has invalid coords — ignoring" }
                    else -> {
                        DevLog.d(TAG) { "onMapReady: current fix available — applying immediately" }
                        updateMapLocation(fix)
                    }
                }
            }
        } catch (e: Exception) {
            DiagnosticsLogger.e(DiagTags.MAP, "Home map ready handling failed", e)
        }
    }

    // Map lifecycle methods
    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView?.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onDestroyView() {
        googleMap?.setLocationSource(null)
        googleMap = null
        onLocationChangedListener = null
        mapLocationSource = null
        mapView?.onDestroy()
        mapView = null
        fixBadge = null
        super.onDestroyView()
        _binding = null
    }
}
