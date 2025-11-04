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
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    // Map and location
    private lateinit var mapView: MapView
    private var googleMap: GoogleMap? = null

    // Custom LocationSource to feed app Fixes into the map's My Location dot
    private var mapLocationSource: LocationSource? = null
    @Volatile private var onLocationChangedListener: LocationSource.OnLocationChangedListener? = null
    private var hasCenteredCamera = false // Always starts false
    private val desiredFollowZoom = 18f

    // Fix Badge component
    private lateinit var fixBadge: FixBadgeView

    // Repositories
    private lateinit var coordinateRepository: CoordinateRepositoryImpl
    private lateinit var modelRepository: ModelRepositoryImpl

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
        android.util.Log.d("HomeFragment", "onCreateView called, hasCenteredCamera=$hasCenteredCamera")

        // Inflate the layout using view binding
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Force reset of camera flag on view creation
        hasCenteredCamera = false
        android.util.Log.d("HomeFragment", "onCreateView: hasCenteredCamera reset to false")

        // Initialize repositories
        val database = AppDatabase.getDatabase(requireContext())
        coordinateRepository = CoordinateRepositoryImpl(database.coordinateDao())
        modelRepository = ModelRepositoryImpl(database.modelDao())

        // Initialize UI components
        fixBadge = binding.fixBadge

        // Initialize map
        mapView = binding.mapViewMini
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        // Set up UI
        setupQuickActionButtons()
        setupLocationStatusObservers()
        setupFixBadgeObservers()
        setupRs2SummaryObservers()
        setupMapUiControls()
        loadStatistics()

        return root
    }

    private fun setupQuickActionButtons() {
        // Quick Actions chips
        binding.chipQuickMap.setOnClickListener {
            findNavController().navigate(R.id.nav_render_map)
        }

        binding.chipQuickAr.setOnClickListener {
            findNavController().navigate(R.id.nav_open_in_ar)
        }

        binding.chipQuickSettings.setOnClickListener {
            findNavController().navigate(R.id.nav_settings)
        }

        // View chips for Survey Data card
        binding.chipViewCoordinates.setOnClickListener {
            findNavController().navigate(R.id.nav_view_coordinates)
        }

        binding.chipViewModels.setOnClickListener {
            findNavController().navigate(R.id.nav_models)
        }
    }

    private fun setupLocationStatusObservers() {
        // Observe current location fix
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                android.util.Log.d("HomeFragment", "Started collecting fixes from switchboard")
                fixSwitchboard.fixes.collect { fix: Fix ->
                    android.util.Log.d("HomeFragment", "Received fix: lat=${fix.latDeg}, lon=${fix.lonDeg}, provider=${fix.provider}")
                    updateLocationDisplay(fix)
                    updateMapLocation(fix)
                    updateStatusDisplay(fix)
                }
            }
        }

        // Observe location source (still needed for RS2 summary / visibility logic)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.locationSource.collectLatest { source ->
                    updateLocationSourceDisplay(source)
                }
            }
        }
    }

    private fun setupFixBadgeObservers() {
        // Observe fix snapshot for GNSS quality indicators
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.fixSnapshot.collect { fixSnapshot ->
                    fixBadge.updateFixData(fixSnapshot)
                }
            }
        }

        // Observe NMEA statistics for stream health indicators
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nmeaStats.collect { nmeaStats ->
                    fixBadge.updateStreamHealth(nmeaStats)
                }
            }
        }
    }

    private fun setupRs2SummaryObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
            }
        }
    }

    private fun setupMapUiControls() {
        // Map type cycler
        binding.chipMapType.setOnClickListener {
            googleMap?.let { map ->
                map.mapType = nextMapType(map.mapType)
            }
        }

        // 3D toggle
        binding.chipToggle3d.setOnCheckedChangeListener { _, isChecked ->
            apply3D(enable = isChecked, animate = true)
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
        val map = googleMap ?: return
        map.isBuildingsEnabled = true
        val cam = map.cameraPosition
        val desiredTilt = if (enable) 60f else 0f
        val desiredBearing = if (enable) cam.bearing.takeIf { it != 0f } ?: 0f else 0f
        val builder = CameraPosition.Builder(cam)
            .tilt(desiredTilt)
            .bearing(desiredBearing)
        // Slightly increase zoom for better perspective if enabling and too low
        val minZoomFor3D = 16f
        if (enable && cam.zoom < minZoomFor3D) builder.zoom(minZoomFor3D)
        val update = CameraUpdateFactory.newCameraPosition(builder.build())
        if (animate) map.animateCamera(update) else map.moveCamera(update)
    }

    private fun loadStatistics() {
        lifecycleScope.launch {
            // Load coordinates count using the correct Flow method
            coordinateRepository.allCoordinatesFlow.collectLatest { coordinates ->
                binding.textCoordinatesCount.text = coordinates.size.toString()

                // Welcome subtitle was removed with the header section
                // No need to update subtitle anymore since it doesn't exist in layout
            }
        }

        lifecycleScope.launch {
            // Load models count using the correct method
            modelRepository.getAllModels().collectLatest { models ->
                binding.textModelsCount.text = models.size.toString()
            }
        }
    }

    private fun updateLocationDisplay(fix: Fix?) {
        if (fix != null) {
            binding.textLocationStatus.text = getString(R.string.location_acquired)
            binding.textLocationStatus.setTextColor(
                ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
            )
        } else {
            binding.textLocationStatus.text = getString(R.string.no_location)
            binding.textLocationStatus.setTextColor(
                ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
            )
        }
    }

    private fun updateMapLocation(fix: Fix?) {
        android.util.Log.d("HomeFragment", "updateMapLocation: fix=${fix?.let { "lat=${it.latDeg}, lon=${it.lonDeg}" } ?: "null"}, googleMap=${googleMap != null}, hasCenteredCamera=$hasCenteredCamera")

        if (fix != null && googleMap != null) {
            // Update the My Location dot
            val locationForDot = fixToLocation(fix)
            onLocationChangedListener?.onLocationChanged(locationForDot)
            android.util.Log.d("HomeFragment", "Updated My Location dot")

            val location = LatLng(fix.latDeg, fix.lonDeg)
            val map = googleMap
            if (map != null) {
                if (!hasCenteredCamera) {
                    android.util.Log.d("HomeFragment", "First fix - centering camera at lat/lng: ($location) with zoom $desiredFollowZoom")
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(location, desiredFollowZoom))
                    hasCenteredCamera = true
                    android.util.Log.d("HomeFragment", "Camera centered, hasCenteredCamera now set to true")
                } else {
                    val currentZoom = map.cameraPosition.zoom
                    android.util.Log.d("HomeFragment", "Subsequent fix - updating camera, current zoom: $currentZoom, desired: $desiredFollowZoom")
                    val update = if (currentZoom < desiredFollowZoom)
                        CameraUpdateFactory.newLatLngZoom(location, desiredFollowZoom)
                    else
                        CameraUpdateFactory.newLatLng(location)
                    map.animateCamera(update)
                }
            }

            // Hide placeholder, show map
            binding.layoutMapPlaceholder.visibility = View.GONE
            binding.mapViewMini.visibility = View.VISIBLE
            android.util.Log.d("HomeFragment", "Map visibility updated: placeholder=GONE, map=VISIBLE")
        } else {
            binding.layoutMapPlaceholder.visibility = View.VISIBLE
            android.util.Log.d("HomeFragment", "Showing placeholder (no fix or map not ready)")
        }
    }

    private fun updateStatusDisplay(_fix: Fix?) { /* no-op */ }

    private fun updateLocationSourceDisplay(_source: LocationSourceType) { /* no-op */ }

    private fun fixToLocation(fix: Fix): Location {
        val loc = Location("LocationManagerFix")
        loc.latitude = fix.latDeg
        loc.longitude = fix.lonDeg
        fix.altEllipsoidalM?.let { loc.altitude = it }
        fix.hAccM?.let { loc.accuracy = it.toFloat() }
        fix.courseDeg?.let { loc.bearing = it.toFloat() }
        fix.speedMps?.let { loc.speed = it.toFloat() }
        loc.time = fix.timeUtc.toEpochMilli()
        return loc
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(map: GoogleMap) {
        android.util.Log.d("HomeFragment", "onMapReady called, hasCenteredCamera was: $hasCenteredCamera")
        googleMap = map

        // Set map type to Normal (shows streets/terrain instead of blank)
        map.mapType = GoogleMap.MAP_TYPE_NORMAL

        // Set default camera position (will be overridden when fix arrives)
        // Start at a reasonable default location (e.g., San Francisco)
        val defaultLocation = LatLng(37.7749, -122.4194)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10f))

        // Provide a custom LocationSource so the blue dot follows our GNSS location data
        mapLocationSource = object : LocationSource {
            override fun activate(listener: LocationSource.OnLocationChangedListener) {
                android.util.Log.d("HomeFragment", "LocationSource activated")
                onLocationChangedListener = listener
            }
            override fun deactivate() {
                android.util.Log.d("HomeFragment", "LocationSource deactivated")
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

            // Reset camera centering flag - will center on first GPS fix
            hasCenteredCamera = false
            android.util.Log.d("HomeFragment", "Map configured: hasCenteredCamera reset to false")
        } else {
            android.util.Log.w("HomeFragment", "Location permission not granted, map features limited")
        }
    }

    // Map lifecycle methods
    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()

        // Reset camera centering flag when fragment resumes
        // This ensures the map will center on GPS location when user returns to home
        hasCenteredCamera = false
        android.util.Log.d("HomeFragment", "onResume: hasCenteredCamera reset to false")

        // Refresh statistics when returning to home
        loadStatistics()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear custom LocationSource references to avoid leaks
        onLocationChangedListener = null
        mapLocationSource = null
        mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
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