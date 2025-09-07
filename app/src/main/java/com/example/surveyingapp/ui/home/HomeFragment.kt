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
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.surveyingapp.R
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.CoordinateRepositoryImpl
import com.example.surveyingapp.data.repository.impl.ModelRepositoryImpl
import com.example.surveyingapp.databinding.FragmentHomeBinding
import com.example.surveyingapp.domain.model.Fix
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.LocationStatus
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomeFragment : Fragment(), OnMapReadyCallback {

    // View binding - safer than findViewById, automatically set to null when view is destroyed
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // Map and location
    private lateinit var mapView: MapView
    private var googleMap: GoogleMap? = null

    // Custom LocationSource to feed app Fixes into the map's My Location dot
    private var mapLocationSource: LocationSource? = null
    @Volatile private var onLocationChangedListener: LocationSource.OnLocationChangedListener? = null
    private var hasCenteredCamera = false
    private val desiredFollowZoom = 18f

    // Repositories
    private lateinit var coordinateRepository: CoordinateRepositoryImpl
    private lateinit var modelRepository: ModelRepositoryImpl

    // Location manager reference
    private val locationManager by lazy { SurveyingApp.locationManager }
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

        // Inflate the layout using view binding
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Initialize repositories
        val database = AppDatabase.getDatabase(requireContext())
        coordinateRepository = CoordinateRepositoryImpl(database.coordinateDao())
        modelRepository = ModelRepositoryImpl(database.modelDao())

        // Initialize map
        mapView = binding.mapViewMini
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        // Set up UI
        setupQuickActionButtons()
        setupLocationStatusObservers()
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
        lifecycleScope.launch {
            locationManager.fixFlow.collectLatest { fix ->
                updateLocationDisplay(fix)
                updateMapLocation(fix)
                updateStatusDisplay(fix)
            }
        }

        // Observe location status
        lifecycleScope.launch {
            locationManager.statusFlow.collectLatest { status ->
                updateLocationStatusText(status)
            }
        }

        // Observe location source
        lifecycleScope.launch {
            settingsRepo.locationSource.collectLatest { source ->
                updateLocationSourceDisplay(source)
            }
        }
    }

    private fun setupRs2SummaryObservers() {
        lifecycleScope.launch {
            combine(
                settingsRepo.locationSource,
                settingsRepo.externalTcpName,
                settingsRepo.externalTcpHost,
                settingsRepo.externalTcpPort
            ) { source, name, host, port ->
                val address = if (!host.isNullOrBlank() && port != null) "$host:$port" else "--"
                Triple(source, name ?: "--", address)
            }.collectLatest { triple ->
                val source = triple.first
                val name = triple.second
                val address = triple.third
                val show = source == LocationSourceType.EXTERNAL
                val visibility = if (show) View.VISIBLE else View.GONE
                binding.textRs2SummaryHeader.visibility = visibility
                binding.containerRs2Summary.visibility = visibility
                if (show) {
                    binding.textRs2Name.text = name
                    binding.textRs2Address.text = address
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

    private fun showMapTypePicker() {
        val map = googleMap ?: return
        val items = arrayOf("Normal", "Satellite", "Terrain", "Hybrid", "None")
        val types = intArrayOf(
            GoogleMap.MAP_TYPE_NORMAL,
            GoogleMap.MAP_TYPE_SATELLITE,
            GoogleMap.MAP_TYPE_TERRAIN,
            GoogleMap.MAP_TYPE_HYBRID,
            GoogleMap.MAP_TYPE_NONE
        )
        val currentIdx = types.indexOf(map.mapType).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("Map type")
            .setSingleChoiceItems(items, currentIdx) { dialog, which ->
                map.mapType = types[which]
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            // Removed coordinates text box updates
            binding.textLocationStatus.text = "Location acquired"
            binding.textLocationStatus.setTextColor(
                ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
            )
        } else {
            // Removed coordinates text box updates
            binding.textLocationStatus.text = "No location"
            binding.textLocationStatus.setTextColor(
                ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
            )
        }
    }

    private fun updateMapLocation(fix: Fix?) {
        if (fix != null && googleMap != null) {
            // Push this Fix into the map's My Location layer
            onLocationChangedListener?.onLocationChanged(fixToLocation(fix))

            val location = LatLng(fix.lat, fix.lon)
            googleMap?.apply {
                // Follow user with tighter zoom
                if (!hasCenteredCamera) {
                    animateCamera(CameraUpdateFactory.newLatLngZoom(location, desiredFollowZoom))
                    hasCenteredCamera = true
                } else {
                    val currentZoom = cameraPosition.zoom
                    val update = if (currentZoom < desiredFollowZoom)
                        CameraUpdateFactory.newLatLngZoom(location, desiredFollowZoom)
                    else
                        CameraUpdateFactory.newLatLng(location)
                    animateCamera(update)
                }
            }

            // Hide placeholder and show map
            binding.layoutMapPlaceholder.visibility = View.GONE
            binding.mapViewMini.visibility = View.VISIBLE
        } else {
            // Show placeholder when no location
            binding.layoutMapPlaceholder.visibility = View.VISIBLE
        }
    }

    private fun updateLocationStatusText(status: LocationStatus) {
        val statusText = when (status) {
            is LocationStatus.Connecting -> "Connecting to GPS..."
            is LocationStatus.Error -> "GPS Error"
            is LocationStatus.Streaming -> "GPS Active"
            is LocationStatus.Idle -> "GPS Idle"
        }

        val statusColor = when (status) {
            is LocationStatus.Streaming -> android.R.color.holo_green_dark
            is LocationStatus.Connecting -> android.R.color.holo_orange_dark
            is LocationStatus.Error -> android.R.color.holo_red_dark
            is LocationStatus.Idle -> android.R.color.darker_gray
        }

        binding.textLocationStatus.text = statusText
        binding.textLocationStatus.setTextColor(
            ContextCompat.getColor(requireContext(), statusColor)
        )
    }

    private fun updateStatusDisplay(fix: Fix?) {
        // No-op; status card removed. Keep for compatibility.
    }

    private fun updateLocationSourceDisplay(source: LocationSourceType) {
        // No-op; display removed. Keep for compatibility.
    }

    private fun fixToLocation(fix: Fix): Location {
        val loc = Location("LocationManagerFix")
        loc.latitude = fix.lat
        loc.longitude = fix.lon
        fix.altEllipsoidalM?.let { loc.altitude = it }
        (fix.hAccM ?: fix.accuracyM)?.let { loc.accuracy = it.toFloat() }
        fix.bearingDeg?.let { loc.bearing = it.toFloat() }
        fix.speedMps?.let { loc.speed = it.toFloat() }
        loc.time = fix.timestamp.toEpochMilli()
        return loc
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Provide a custom LocationSource so the blue dot follows our LocationSourceManager Fix
        mapLocationSource = object : LocationSource {
            override fun activate(listener: LocationSource.OnLocationChangedListener) {
                onLocationChangedListener = listener
            }
            override fun deactivate() {
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

            // Initial camera will be centered on first Fix in updateMapLocation()
            hasCenteredCamera = false
        }
    }

    // Map lifecycle methods
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        // Refresh statistics when returning to home
        loadStatistics()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
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