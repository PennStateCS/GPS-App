package com.example.surveyingapp.ui.rendermap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.surveyingapp.R
import com.example.surveyingapp.ui.viewpoints.CoordinatesViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import android.widget.Toast
import java.util.Locale

class RenderMapFragment : Fragment() {

    private var mapView: MapView? = null
    private var placeholder: View? = null

    // Keep overlays/components around
    private var locationOverlay: MyLocationNewOverlay? = null
    private val markers = mutableListOf<Marker>()           // track only markers
    private val markerIconCache = mutableMapOf<String, Drawable>()

    private var isSatellite = false
    private var lastGeoPoints: List<GeoPoint> = emptyList()

    private val requestLocationPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) enableMyLocationOverlay()
            else Toast.makeText(requireContext(), "Location permission denied", Toast.LENGTH_SHORT).show()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_render_map, container, false)
        mapView = root.findViewById(R.id.mapView)
        placeholder = root.findViewById(R.id.text_render_map)

        setupMap(requireContext())
        bindData()

        root.findViewById<FloatingActionButton>(R.id.fab_toggle_sat)
            ?.setOnClickListener { toggleSatellite() }
        root.findViewById<FloatingActionButton>(R.id.fab_recenter)
            ?.setOnClickListener { recenterMap() }

        enableMyLocationIfPermitted()
        return root
    }

    private fun setupMap(context: Context) {
        // IMPORTANT: set user agent so tile servers don’t block
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        if (Configuration.getInstance().userAgentValue.isNullOrBlank()) {
            Configuration.getInstance().userAgentValue = context.packageName
        }

        mapView?.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)

            // Reasonable default: continental US
            controller.setZoom(4.0)
            controller.setCenter(GeoPoint(39.5, -98.35))
        }
    }

    private fun bindData() {
        val viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory(requireActivity().application)
        ).get(CoordinatesViewModel::class.java)

        viewModel.allCoordinates.observe(viewLifecycleOwner) { points ->
            val map = mapView ?: return@observe

            // remove old markers only, keep other overlays (e.g., location)
            if (markers.isNotEmpty()) {
                markers.forEach { map.overlays.remove(it) }
                markers.clear()
            }

            if (points.isEmpty()) {
                placeholder?.visibility = View.VISIBLE
                lastGeoPoints = emptyList()
            } else {
                placeholder?.visibility = View.GONE

                val geoPoints = mutableListOf<GeoPoint>()
                points.forEach { p ->
                    val gp = GeoPoint(p.latitude, p.longitude)
                    geoPoints.add(gp)

                    val m = Marker(map).apply {
                        position = gp
                        title = p.name
                        subDescription = String.format(
                            Locale.US,
                            "%.6f, %.6f\nAlt: %.2f m",
                            p.latitude, p.longitude, p.altitude
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = getTintedMarkerDrawable(requireContext(), p.icon, p.color)
                        setOnMarkerClickListener { _, _ ->
                            if (isAdded) {
                                CoordinateInfoBottomSheet
                                    .newInstance(p)
                                    .show(parentFragmentManager, "coordinate_info")
                            }
                            true
                        }
                    }
                    markers += m
                    map.overlays.add(m)
                }
                lastGeoPoints = geoPoints

                // Keep location overlay on top of markers if already present
                locationOverlay?.let {
                    map.overlays.remove(it)
                    map.overlays.add(it)
                }

                // Auto-fit
                if (geoPoints.size == 1) {
                    map.controller?.setZoom(16.0)
                    map.controller?.setCenter(geoPoints.first())
                } else {
                    val bb = BoundingBox.fromGeoPointsSafe(geoPoints)
                    // add small padding; zoomToBoundingBox signature varies by version
                    val padded = BoundingBox(
                        bb.latNorth + 0.01, bb.lonEast + 0.01,
                        bb.latSouth - 0.01, bb.lonWest - 0.01
                    )
                    map.zoomToBoundingBox(padded, true) // animate
                }
            }
            map.invalidate()
        }
    }

    private fun getTintedMarkerDrawable(context: Context, iconName: String, color: Int): Drawable? {
        val key = "$iconName-$color"
        markerIconCache[key]?.let { return it }

        val resId = context.resources.getIdentifier(iconName, "drawable", context.packageName)
        val base = (if (resId != 0)
            ContextCompat.getDrawable(context, resId)
        else
            ContextCompat.getDrawable(context, R.drawable.ic_menu_camera))?.mutate()

        if (base == null) return null

        // Use DrawableCompat for broad tint support
        val wrapped = DrawableCompat.wrap(base)
        try {
            DrawableCompat.setTint(wrapped, color)
            DrawableCompat.setTintMode(wrapped, PorterDuff.Mode.SRC_IN)
        } catch (_: Exception) {
            // fallback
            @Suppress("DEPRECATION")
            base.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        markerIconCache[key] = wrapped
        return wrapped
    }

    private fun toggleSatellite() {
        val map = mapView ?: return
        isSatellite = !isSatellite

        // USGS_SAT is bundled with osmdroid; no API key required.
        map.setTileSource(if (isSatellite) TileSourceFactory.USGS_SAT else TileSourceFactory.MAPNIK)

        view?.findViewById<FloatingActionButton>(R.id.fab_toggle_sat)?.apply {
            setImageResource(
                if (isSatellite) android.R.drawable.ic_menu_mapmode
                else android.R.drawable.ic_menu_gallery
            )
            contentDescription = if (isSatellite) "Switch to standard" else "Switch to satellite"
        }
        Toast.makeText(requireContext(),
            if (isSatellite) "Satellite view" else "Standard view",
            Toast.LENGTH_SHORT
        ).show()

        map.invalidate()
    }

    private fun recenterMap() {
        val map = mapView ?: return
        val points = lastGeoPoints
        if (points.isEmpty()) {
            Toast.makeText(requireContext(), "No points to recenter", Toast.LENGTH_SHORT).show()
            return
        }
        if (points.size == 1) {
            map.controller?.setZoom(16.0)
            map.controller?.setCenter(points.first())
        } else {
            val bb = BoundingBox.fromGeoPointsSafe(points)
            val padded = BoundingBox(
                bb.latNorth + 0.01, bb.lonEast + 0.01,
                bb.latSouth - 0.01, bb.lonWest - 0.01
            )
            map.zoomToBoundingBox(padded, true)
        }
        map.invalidate()
    }

    private fun enableMyLocationIfPermitted() {
        val ctx = requireContext()
        val fineGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            requestLocationPermissions.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            return
        }
        enableMyLocationOverlay()
    }

    private fun enableMyLocationOverlay() {
        val mv = mapView ?: return
        if (locationOverlay == null) {
            locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), mv).apply {
                enableMyLocation()
                // leave "follow" off to honor your comment; user can pan freely
            }
            mv.overlays.add(locationOverlay)
        } else {
            // ensure it’s present (if overlays were rebuilt)
            if (!mv.overlays.contains(locationOverlay)) mv.overlays.add(locationOverlay)
            locationOverlay?.enableMyLocation()
        }
        mv.invalidate()
    }

    // ---- Lifecycle ----

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        locationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        locationOverlay?.disableMyLocation()
        mapView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        // Clear references
        markers.clear()
        locationOverlay = null
        mapView = null
        placeholder = null
        super.onDestroyView()
    }
}
