/**
 * Fragment for the Home screen - the main entry point of the surveying app.
 *
 * This demonstrates key Android Fragment concepts:
 * - Fragment lifecycle: onCreateView, onDestroyView
 * - View binding: Safe way to access views without findViewById
 * - MVVM pattern: Fragment (View) observes ViewModel for data changes
 * - Observer pattern: UI automatically updates when LiveData changes
 */
package com.example.surveyingapp.ui.home

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
import com.example.surveyingapp.R
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.CoordinateRepositoryImpl
import com.example.surveyingapp.data.repository.impl.ModelRepositoryImpl
import com.example.surveyingapp.databinding.FragmentHomeBinding
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.ui.components.FixBadgeView
import com.example.surveyingapp.ui.map.MapThemeHelper
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.LocationSource
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.util.DiagnosticsLogger
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

@AndroidEntryPoint
class HomeFragment : Fragment(), OnMapReadyCallback {

    // Inject FixSwitchboard using Hilt
    @Inject
    lateinit var fixSwitchboard: FixSwitchboard

    // Active-provider state, used to reset the live map location when the source switches.
    @Inject
    lateinit var sourceSettings: com.example.surveyingapp.gnss.source.SourceSettings

    // ViewModel injection using Hilt
    private val viewModel: HomeViewModel by viewModels()

    // View binding - safer than findViewById, automatically set to null when view is destroyed
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Fragment binding is null - view may have been destroyed")
    private lateinit var mapView: MapView
    private var googleMap: GoogleMap? = null

    // Custom LocationSource to feed app Fixes into the map's My Location dot
    private var mapLocationSource: LocationSource? = null
    @Volatile private var onLocationChangedListener: LocationSource.OnLocationChangedListener? = null
    private var hasCenteredCamera = false // Always starts false
    private val desiredFollowZoom = 18f
    private var lastCameraMoveMs = 0L
    private var lastCameraLocation: LatLng? = null
    private var lastRtkStatus: RtkStatus? = null
    private var loggedFirstMapFix = false // logs the first current-source fix applied to the map
    private var lastMapIgnoreLogMs = 0L   // throttle for ignored-fix diagnostics

    // Fix Badge component
    private lateinit var fixBadge: FixBadgeView

    // Repositories — nullable so a DB-init failure doesn't leave lateinit vars uninitialised
    private var coordinateRepository: CoordinateRepositoryImpl? = null
    private var modelRepository: ModelRepositoryImpl? = null

    // Settings repository reference (still needed for settings)
    private val settingsRepo by lazy { SurveyingApp.settingsRepo }

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

        // Initialise repositories — guard against DB creation failure
        try {
            val database = AppDatabase.getDatabase(requireContext())
            coordinateRepository = CoordinateRepositoryImpl(database.coordinateDao())
            modelRepository = ModelRepositoryImpl(database.modelDao())
        } catch (e: Exception) {
            android.util.Log.e("HomeFragment", "Failed to initialise repositories", e)
        }

        // Initialize UI components
        fixBadge = binding.fixBadge

        // Initialize map
        mapView = binding.mapViewMini
        try {
            mapView.onCreate(savedInstanceState)
            mapView.getMapAsync(this)
        } catch (e: Exception) {
            android.util.Log.e("HomeFragment", "MapView init failed", e)
        }

        // Set up UI
        setupQuickActionButtons()
        setupLocationStatusObservers()
        setupFixBadgeObservers()
        setupRs2SummaryObservers()
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
                            if (fix == null) return@collect            // no current-source fix yet
                            if (!fixMatchesSource(source, fix)) { logMapIgnore("wrong-provider"); return@collect }
                            if (isFixStale(fix)) { logMapIgnore("stale"); return@collect }
                            if (fix.latDeg !in -90.0..90.0 || fix.lonDeg !in -180.0..180.0) {
                                logMapIgnore("invalid-coords"); return@collect
                            }
                            if (!loggedFirstMapFix) {
                                loggedFirstMapFix = true
                                DiagnosticsLogger.i("HomeMap", "First current-source fix applied to map" +
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
                var previousProvider: com.example.surveyingapp.gnss.source.SourceSettings.ProviderChoice? = null
                sourceSettings.activeProvider.collect { provider ->
                    if (previousProvider != null && previousProvider != provider) {
                        DiagnosticsLogger.i("HomeMap", "Live location reset due to provider switch $previousProvider -> $provider")
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
        lastRtkStatus = null
        lastCameraLocation = null
        loggedFirstMapFix = false
    }

    /** Throttled (≤ once / 10 s) diagnostic for fixes the map dropped. */
    private fun logMapIgnore(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastMapIgnoreLogMs >= 10_000L) {
            lastMapIgnoreLogMs = now
            DiagnosticsLogger.w("HomeMap", "Ignored $reason fix for live map")
        }
    }

    /** True when [fix]'s provider matches the user-selected [source]. */
    private fun fixMatchesSource(source: LocationSourceType, fix: Fix): Boolean =
        if (source == LocationSourceType.INTERNAL) {
            fix.provider == Provider.INTERNAL
        } else {
            fix.provider != Provider.INTERNAL
        }

    /** True when [fix] is older than the live-display window (stale replay buffer guard). */
    private fun isFixStale(fix: Fix): Boolean =
        try {
            java.time.Duration.between(fix.timeUtc, java.time.Instant.now()).toMillis() > MAP_FIX_MAX_AGE_MS
        } catch (_: Exception) {
            false
        }

    private fun setupFixBadgeObservers() {
        // Observe fix snapshot for GNSS quality indicators
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.fixSnapshot.collect { fixSnapshot ->
                        fixBadge.updateFixData(fixSnapshot)
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
                        fixBadge.updateStreamHealth(nmeaStats)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("HomeFragment", "Error collecting NMEA stats", e)
                }
            }
        }
    }

    private fun setupRs2SummaryObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    combine(
                        settingsRepo.locationSource,
                        settingsRepo.externalTcpName,
                        settingsRepo.externalTcpHost,
                        settingsRepo.externalTcpPort
                    ) { source, name, host, port ->
                        val address = if (!host.isNullOrBlank() && port != null) "$host:$port" else "--"
                        Triple(source, name ?: "--", address)
                    }.collectLatest { triple ->
                        // Check if binding is still available before accessing UI
                        val currentBinding = _binding ?: return@collectLatest

                        val source = triple.first
                        val name = triple.second
                        val address = triple.third
                        val show = source == LocationSourceType.EXTERNAL
                        val visibility = if (show) View.VISIBLE else View.GONE

                        currentBinding.textRs2SummaryHeader.visibility = visibility
                        currentBinding.containerRs2Summary.visibility = visibility
                        if (show) {
                            currentBinding.textRs2Name.text = name
                            currentBinding.textRs2Address.text = address
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("HomeFragment", "Error collecting RS2 summary", e)
                }
            }
        }
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
                    coordinateRepository?.coordinateCountFlow?.collectLatest { count ->
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
                    modelRepository?.observeModelCount()?.collectLatest { count ->
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

    private fun updateMapLocation(fix: Fix?) {
        try {
            if (LOG_GNSS_UI) android.util.Log.d("HomeFragment", "updateMapLocation: fix=${fix?.let { "lat=${it.latDeg}, lon=${it.lonDeg}" } ?: "null"}")

            if (fix != null && googleMap != null) {
                if (fix.latDeg !in -90.0..90.0 || fix.lonDeg !in -180.0..180.0) return
                val locationForDot = fixToLocation(fix)
                onLocationChangedListener?.onLocationChanged(locationForDot)

                // Log RTK status transitions (not every fix)
                if (fix.rtkStatus != lastRtkStatus) {
                    val prev = lastRtkStatus?.name ?: "NONE"
                    DiagnosticsLogger.i("Fix", "Status $prev → ${fix.rtkStatus.name} sats=${fix.satsUsed} hAcc=${fix.hAccM?.let { "%.3fm".format(it) } ?: "unknown"}")
                    lastRtkStatus = fix.rtkStatus
                }

                val location = LatLng(fix.latDeg, fix.lonDeg)
                val map = googleMap
                if (map != null) {
                    if (!hasCenteredCamera) {
                        android.util.Log.d(TAG, "First fix to map: lat=${fix.latDeg}, lon=${fix.lonDeg}, hAcc=${fix.hAccM}")
                        DiagnosticsLogger.i("HomeMap", "First fix lat=%.6f lon=%.6f hAcc=${fix.hAccM?.let { "%.3fm".format(it) } ?: "?"} status=${fix.rtkStatus}".format(fix.latDeg, fix.lonDeg))
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
            android.util.Log.d(TAG, "onMapReady: map ready, renderer=${map.javaClass.simpleName}, mapsRenderer=${com.example.surveyingapp.SurveyingApp.activeMapsRenderer}")
            DiagnosticsLogger.i("HomeMap", "MAP_READY mapsRenderer=${SurveyingApp.activeMapsRenderer}")
            SurveyingApp.reportMapLoadStatus("MAP_READY")
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
                    DiagnosticsLogger.w("Network", "internet=true validated=false transport=$transport — tiles may not load (receiver WiFi?)")
                }
                DiagnosticsLogger.i("HomeMap", "Network transport=$transport internet=$hasInternet validated=$hasValidated")
            }

            // Set map type to Normal (shows streets/terrain instead of blank)
            map.mapType = GoogleMap.MAP_TYPE_NORMAL
            val nightMode = (requireContext().resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            android.util.Log.d(TAG, "onMapReady: mapType=${map.mapType} nightMode=$nightMode, applying theme")
            MapThemeHelper.applyTheme(requireContext(), map, map.mapType)

            // Set default camera position (will be overridden when fix arrives)
            val defaultLocation = LatLng(40.7963, -77.8570)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 15f))
            android.util.Log.d(TAG, "onMapReady: default camera set at Penn State zoom=15")

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
                DiagnosticsLogger.i("HomeMap", "MAP_LOADED elapsed=${elapsed}ms zoom=%.1f".format(cam.zoom))
                SurveyingApp.reportMapLoadStatus("MAP_LOADED (${elapsed}ms)")
            }
            // Warn if tiles never load within 10 s (blank/gray map scenario)
            viewLifecycleOwner.lifecycleScope.launch {
                delay(10_000)
                if (!mapTilesLoaded) {
                    DiagnosticsLogger.w("HomeMap", "MAP_LOADED did not fire after 10 seconds — tiles may be blank")
                    SurveyingApp.reportMapLoadStatus("MAP_READY but MAP_LOADED did not fire (10s)")
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
                android.util.Log.d(TAG, "onMapReady: location permission granted — enabling My Location layer")
                map.isMyLocationEnabled = true
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
        if (::mapView.isInitialized) mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (::mapView.isInitialized) mapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) mapView.onLowMemory()
    }

    override fun onDestroyView() {
        googleMap?.setLocationSource(null)
        googleMap = null
        onLocationChangedListener = null
        mapLocationSource = null
        lastRtkStatus = null
        if (::mapView.isInitialized) mapView.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}