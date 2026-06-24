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
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.ui.components.FixBadgeView
import com.example.surveyingapp.ui.map.MapThemeHelper
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val LOG_GNSS_UI = false
private const val TAG = "HomeFragment"

@AndroidEntryPoint
class HomeFragment : Fragment(), OnMapReadyCallback {

    // Inject FixSwitchboard using Hilt
    @Inject
    lateinit var fixSwitchboard: FixSwitchboard

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
        // Observe current location fix
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    fixSwitchboard.fixes
                        .conflate()
                        .sample(500)
                        .collect { fix: Fix ->
                        updateMapLocation(fix)
                        updateStatusDisplay(fix)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("HomeFragment", "Error collecting GNSS fixes", e)
                }
            }
        }

        // Observe location source (still needed for RS2 summary / visibility logic)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    settingsRepo.locationSource.collectLatest { source ->
                        updateLocationSourceDisplay(source)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("HomeFragment", "Error collecting location source", e)
                }
            }
        }
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
            GoogleMap.MAP_TYPE_HYBRID,
            GoogleMap.MAP_TYPE_NONE
        )
        val i = order.indexOf(current)
        return if (i == -1 || i == order.lastIndex) order.first() else order[i + 1]
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

    private fun loadStatistics() {
        // Statistics flows are collected inline in onCreateView alongside the other
        // viewLifecycleOwner-scoped observers (see setupLocationStatusObservers etc.)
        // This function is intentionally empty – see collectStatisticsFlows() called from onCreateView.
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
            android.util.Log.e("HomeFragment", "updateMapLocation failed", e)
        }
    }

    private fun updateStatusDisplay(_fix: Fix?) { /* no-op */ }

    private fun updateLocationSourceDisplay(_source: LocationSourceType) { /* no-op */ }

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
            android.util.Log.d(TAG, "onMapReady: map ready, renderer=${map.javaClass.simpleName}")
            if (LOG_GNSS_UI) android.util.Log.d(TAG, "onMapReady: hasCenteredCamera was $hasCenteredCamera")
            googleMap = map

            // Set map type to Normal (shows streets/terrain instead of blank)
            map.mapType = GoogleMap.MAP_TYPE_NORMAL
            android.util.Log.d(TAG, "onMapReady: mapType set to NORMAL, applying theme")
            MapThemeHelper.applyTheme(requireContext(), map, map.mapType)

            // Set default camera position (will be overridden when fix arrives)
            val defaultLocation = LatLng(40.7963, -77.8570)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 15f))
            android.util.Log.d(TAG, "onMapReady: default camera set at Penn State zoom=15")

            // Provide a custom LocationSource so the blue dot follows our GNSS location data
            mapLocationSource = object : LocationSource {
                override fun activate(listener: LocationSource.OnLocationChangedListener) {
                    if (LOG_GNSS_UI) android.util.Log.d("HomeFragment", "LocationSource activated")
                    onLocationChangedListener = listener
                }
                override fun deactivate() {
                    if (LOG_GNSS_UI) android.util.Log.d("HomeFragment", "LocationSource deactivated")
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

                // Apply 3D state based on chip
                apply3D(enable = binding.chipToggle3d.isChecked, animate = false)

                // Permission is granted — hide the placeholder so the map tiles show
                // immediately while we wait for the first GPS fix.
                binding.layoutMapPlaceholder.visibility = View.GONE
                binding.mapViewMini.visibility = View.VISIBLE

                // Reset camera centering flag - will center on first GPS fix
                hasCenteredCamera = false
                if (LOG_GNSS_UI) android.util.Log.d(TAG, "onMapReady: hasCenteredCamera reset to false")
            } else {
                _binding?.let {
                    it.layoutMapPlaceholder.visibility = View.VISIBLE
                    it.root.findViewById<android.widget.TextView>(R.id.text_map_placeholder)
                        ?.text = "Location services required"
                }
                // If you see this log and the map is blank: grant location permission and restart
                android.util.Log.w(TAG, "onMapReady: location permission not granted — placeholder shown, map tiles visible but My Location disabled")
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

        // Reset camera centering flag when fragment resumes
        // This ensures the map will center on GPS location when user returns to home
        hasCenteredCamera = false
        if (LOG_GNSS_UI) android.util.Log.d("HomeFragment", "onResume: hasCenteredCamera reset to false")

        // Note: loadStatistics() is set up once in onCreateView via viewLifecycleOwner.lifecycleScope
        // with repeatOnLifecycle(STARTED) - no need to call it again here.
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

    override fun onDestroy() {
        super.onDestroy()
        // Clear custom LocationSource references to avoid leaks
        onLocationChangedListener = null
        mapLocationSource = null
        if (::mapView.isInitialized) mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) mapView.onLowMemory()
    }

    /**
     * Called when the view hierarchy is being destroyed.
     * IMPORTANT: Always set binding to null to prevent memory leaks!
     */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null  // Prevents memory leaks by releasing view references
    }
}