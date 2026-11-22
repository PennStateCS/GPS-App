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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.LocationSource
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import app.surrealar.util.DiagnosticsLogger
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

private const val LOG_GNSS_UI = false
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
    private var lastMapIgnoreLogMs = 0L   // throttle for ignored-fix diagnostics

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
        if (LOG_GNSS_UI) android.util.Log.d("HomeFragment", "onCreateView called, hasCenteredCamera=$hasCenteredCamera")

        // Inflate the layout using view binding
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Force reset of camera flag on view creation
        hasCenteredCamera = false
        loggedFirstMapFix = false
        if (LOG_GNSS_UI) android.util.Log.d("HomeFragment", "onCreateView: hasCenteredCamera reset to false")


        // Initialize UI components
        fixBadge = binding.fixBadge

        // Initialize map
        mapView = binding.mapViewMini
        try {
            mapView?.onCreate(savedInstanceState)
            mapView?.getMapAsync(this)
        } catch (e: Exception) {
            android.util.Log.e("HomeFragment", "MapView init failed", e)
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

    private fun setupQuickActionButtons() {
        // Quick Actions chips
        binding.chipQuickMap.setOnClickListener {
            try { findNavController().navigate(R.id.nav_render_map) }
            catch (e: Exception) { android.util.Log.e("HomeFragment", "Navigation to map failed", e) }
        }

        // Current Location card header action — same destination as the Quick Actions chip,
        // giving the prominent map preview a clear "this opens the full map" affordance.
        binding.btnOpenFullMap.setOnClickListener {
            try { findNavController().navigate(R.id.nav_render_map) }
            catch (e: Exception) { android.util.Log.e("HomeFragment", "Navigation to map failed", e) }
        }

        binding.chipQuickAr.setOnClickListener {
            try { findNavController().navigate(R.id.nav_open_in_ar) }
            catch (e: Exception) { android.util.Log.e("HomeFragment", "Navigation to AR failed", e) }
        }

        binding.chipQuickSettings.setOnClickListener {
            try { findNavController().navigate(R.id.nav_settings) }
            catch (e: Exception) { android.util.Log.e("HomeFragment", "Navigation to settings failed", e) }
        }

        // View chips for Survey Data card
        binding.chipViewCoordinates.setOnClickListener {
            try { findNavController().navigate(R.id.nav_view_coordinates) }
            catch (e: Exception) { android.util.Log.e("HomeFragment", "Navigation to coordinates failed", e) }
        }

        binding.chipViewModels.setOnClickListener {
            try { findNavController().navigate(R.id.nav_models) }
            catch (e: Exception) { android.util.Log.e("HomeFragment", "Navigation to models failed", e) }
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
                            if (!loggedFirstMapFix) {
                                loggedFirstMapFix = true
                                DiagnosticsLogger.i("MAP", "First current-source fix applied to map" +
                                    " provider=${fix.provider} lat=%.6f lon=%.6f".format(fix.latDeg, fix.lonDeg))
                            }
                            updateMapLocation(fix)
                        }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("HomeFragment", "Error collecting GNSS fixes", e)
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
                        DiagnosticsLogger.i("MAP", "Live location reset due to provider switch $previousProvider -> $provider")
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

    /** Throttled (≤ once / 10 s) diagnostic for fixes the map dropped. */
    private fun logMapIgnore(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastMapIgnoreLogMs >= 10_000L) {
            lastMapIgnoreLogMs = now
            DiagnosticsLogger.w("MAP", "Ignored $reason fix for live map")
        }
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
                    android.util.Log.e("HomeFragment", "Error collecting fix snapshot", e)
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
                    android.util.Log.e("HomeFragment", "Error collecting NMEA stats", e)
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
            try { findNavController().navigate(dest) }
            catch (e: Exception) { android.util.Log.e(TAG, "Field status navigation failed", e) }
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
                    android.util.Log.e(TAG, "Error collecting field status", e)
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
                android.util.Log.e("HomeFragment", "Error changing map type", e)
            }
        }

        // 3D toggle
        binding.chipToggle3d.setOnCheckedChangeListener { _, isChecked ->
            try { apply3D(enable = isChecked, animate = true) }
            catch (e: Exception) { android.util.Log.e("HomeFragment", "Error toggling 3D", e) }
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
            android.util.Log.e("HomeFragment", "apply3D failed", e)
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
                    android.util.Log.e("HomeFragment", "Error collecting coordinate count", e)
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
                    android.util.Log.e("HomeFragment", "Error collecting model count", e)
                }
            }
        }
    }


    private fun shouldMoveHomeCamera(newLocation: LatLng): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCameraMoveMs < 2_000) return false
        val prev = lastCameraLocation ?: return true
        val result = FloatArray(1)
        android.location.Location.distanceBetween(prev.latitude, prev.longitude, newLocation.latitude, newLocation.longitude, result)
        return result[0] >= 5f
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
            android.util.Log.w(TAG, "My Location layer toggle failed", e)
        }
    }

    private fun updateMapLocation(fix: Fix?) {
        try {
            if (LOG_GNSS_UI) android.util.Log.d("HomeFragment", "updateMapLocation: fix=${fix?.let { "lat=${it.latDeg}, lon=${it.lonDeg}" } ?: "null"}")

            if (fix != null && googleMap != null) {
                if (fix.latDeg !in -90.0..90.0 || fix.lonDeg !in -180.0..180.0) return
                val locationForDot = fixToLocation(fix)
                setLiveDotVisible(true)
                showGnssStatusChip(null)   // live position: no waiting chip
                onLocationChangedListener?.onLocationChanged(locationForDot)

                val location = LatLng(fix.latDeg, fix.lonDeg)
                val map = googleMap
                if (map != null) {
                    if (!hasCenteredCamera) {
                        android.util.Log.d(TAG, "First fix to map: hAcc=${fix.hAccM} status=${fix.rtkStatus} sats=${fix.satsUsed}")
                        // Privacy: do not log exact live coordinates to the persistent diagnostic ZIP.
                        DiagnosticsLogger.i("MAP", "First fix applied to map status=${fix.rtkStatus} hAcc=${fix.hAccM?.let { "%.3fm".format(it) } ?: "?"} sats=${fix.satsUsed}")
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
            android.util.Log.e("HomeFragment", "updateMapLocation failed", e)
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
        val lastKnown = runCatching { lastKnownPositionStore.get() }.getOrNull()
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
            DiagnosticsLogger.i("MAP", "Initial camera: ${choice::class.simpleName}")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "applyInitialCamera failed", e)
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
            android.util.Log.e("HomeFragment", "fixToLocation failed, returning bare location", e)
            Location("LocationManagerFix").also {
                it.latitude = fix.latDeg
                it.longitude = fix.lonDeg
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(map: GoogleMap) {
        try {
            android.util.Log.d(TAG, "onMapReady: map ready, renderer=${map.javaClass.simpleName}, mapsRenderer=${app.surrealar.SurRealApplication.activeMapsRenderer}")
            DiagnosticsLogger.i("MAP", "MAP_READY mapsRenderer=${SurRealApplication.activeMapsRenderer}")
            SurRealApplication.reportMapLoadStatus("MAP_READY")
            googleMap = map

            val playServicesVersion = try {
                requireContext().packageManager.getPackageInfo("com.google.android.gms", 0).versionName ?: "unknown"
            } catch (_: Exception) { "unavailable" }
            android.util.Log.d(TAG, "onMapReady: Play Services version=$playServicesVersion")

            // Log network connectivity state — internet-without-validation means tiles won't load
            // (e.g. device is on a receiver Wi-Fi network with no real internet access)
            run {
                val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val active = cm.activeNetwork
                val caps = active?.let { cm.getNetworkCapabilities(it) }
                val hasInternet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val hasValidated = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                val isVpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                val isWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                val isCellular = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
                val transport = when { active == null -> "none"; isVpn -> "VPN"; isWifi -> "WiFi"; isCellular -> "cellular"; else -> "other" }
                android.util.Log.d(TAG, "onMapReady: network transport=$transport internet=$hasInternet validated=$hasValidated")
                if (hasInternet && !hasValidated) {
                    android.util.Log.w(TAG, "onMapReady: network not validated — likely receiver WiFi; map tiles may not load")
                    DiagnosticsLogger.w("SYSTEM", "internet=true validated=false transport=$transport — tiles may not load (receiver WiFi?)")
                }
                DiagnosticsLogger.i("MAP", "Network transport=$transport internet=$hasInternet validated=$hasValidated")
            }

            // Set map type to Normal (shows streets/terrain instead of blank)
            map.mapType = GoogleMap.MAP_TYPE_NORMAL
            val nightMode = (requireContext().resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            android.util.Log.d(TAG, "onMapReady: mapType=${map.mapType} nightMode=$nightMode, applying theme")
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
                android.util.Log.d(TAG, "Map tiles loaded: ${elapsed}ms after onMapReady, " +
                    "zoom=${cam.zoom}, lat=${cam.target.latitude}, lon=${cam.target.longitude}")
                DiagnosticsLogger.i("MAP", "MAP_LOADED elapsed=${elapsed}ms zoom=%.1f".format(cam.zoom))
                SurRealApplication.reportMapLoadStatus("MAP_LOADED (${elapsed}ms)")
            }
            // Warn if tiles never load within 10 s (blank/gray map scenario)
            viewLifecycleOwner.lifecycleScope.launch {
                delay(10_000)
                if (!mapTilesLoaded) {
                    DiagnosticsLogger.w("MAP", "MAP_LOADED did not fire after 10 seconds — tiles may be blank")
                    SurRealApplication.reportMapLoadStatus("MAP_READY but MAP_LOADED did not fire (10s)")
                }
            }

            // Provide a custom LocationSource so the blue dot follows our GNSS location data
            mapLocationSource = object : LocationSource {
                override fun activate(listener: LocationSource.OnLocationChangedListener) {
                    android.util.Log.d(TAG, "LocationSource activated — blue dot wired to GNSS stream")
                    onLocationChangedListener = listener
                }
                override fun deactivate() {
                    if (LOG_GNSS_UI) android.util.Log.d(TAG, "LocationSource deactivated")
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
                android.util.Log.d(TAG, "onMapReady: location permission granted — My Location layer fed by GNSS")
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
                // If you see this log and the map is blank: grant location permission and restart
                android.util.Log.w(TAG, "onMapReady: location permission not granted — placeholder shown, map tiles visible but My Location disabled")
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
                        android.util.Log.d(TAG, "onMapReady: current fix is stale — ignoring")
                    !validCoords ->
                        android.util.Log.d(TAG, "onMapReady: current fix has invalid coords — ignoring")
                    else -> {
                        android.util.Log.d(TAG, "onMapReady: current fix available lat=${fix.latDeg}, lon=${fix.lonDeg} — applying immediately")
                        updateMapLocation(fix)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeFragment", "onMapReady failed", e)
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
