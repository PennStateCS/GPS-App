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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.surveyingapp.R
import com.example.surveyingapp.data.Point
import com.example.surveyingapp.ui.viewpoints.CoordinatesViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.util.BoundingBox
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.widget.Toast
import java.util.Locale
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class RenderMapFragment : Fragment() {

    private var mapView: MapView? = null
    private var placeholder: View? = null
    private val markerIconCache = mutableMapOf<String, Drawable>()
    private var isSatellite = false
    private var lastGeoPoints: List<GeoPoint> = emptyList()
    private var locationOverlay: MyLocationNewOverlay? = null
    private val locationRequestCode = 3001

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_render_map, container, false)
        mapView = root.findViewById(R.id.mapView)
        placeholder = root.findViewById(R.id.text_render_map)
        setupMap(root.context)
        bindData()
        root.findViewById<FloatingActionButton>(R.id.fab_toggle_sat)?.setOnClickListener { toggleSatellite() }
        root.findViewById<FloatingActionButton>(R.id.fab_recenter)?.setOnClickListener { recenterMap() }
        enableMyLocationIfPermitted()
        return root
    }

    private fun setupMap(context: Context) {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        mapView?.apply {
            setMultiTouchControls(true)
            // Enable zoom buttons (fade out) for quick access
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            // Optional: increase fade delay (default 1.5s) if desired via reflection or leave default
            controller.setZoom(4.0)
            controller.setCenter(GeoPoint(39.5, -98.35))
        }
    }

    private fun bindData() {
        val viewModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(requireActivity().application))
            .get(CoordinatesViewModel::class.java)
        viewModel.allPoints.observe(viewLifecycleOwner) { points ->
            val map = mapView ?: return@observe
            map.overlays.clear()
            if (points.isEmpty()) {
                placeholder?.visibility = View.VISIBLE
                lastGeoPoints = emptyList()
            } else {
                placeholder?.visibility = View.GONE
                val geoPoints = mutableListOf<GeoPoint>()
                points.forEach { p ->
                    val gp = GeoPoint(p.latitude, p.longitude)
                    geoPoints.add(gp)
                    val marker = Marker(map).apply {
                        position = gp
                        title = p.name
                        subDescription = String.format(Locale.US, "%.6f, %.6f\nAlt: %.2f m", p.latitude, p.longitude, p.altitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = getTintedMarkerDrawable(requireContext(), p.icon, p.color)
                        setOnMarkerClickListener { _, _ ->
                            if (isAdded) {
                                CoordinateInfoBottomSheet.newInstance(p).show(parentFragmentManager, "coordinate_info")
                            }
                            true
                        }
                    }
                    map.overlays.add(marker)
                }
                lastGeoPoints = geoPoints
                // Initial auto-fit remains as before
                if (geoPoints.size == 1) {
                    map.controller?.setZoom(16.0)
                    map.controller?.setCenter(geoPoints.first())
                } else {
                    val bb = BoundingBox.fromGeoPointsSafe(geoPoints)
                    val padded = BoundingBox(bb.latNorth + 0.01, bb.lonEast + 0.01, bb.latSouth - 0.01, bb.lonWest - 0.01)
                    map.zoomToBoundingBox(padded, true, 60)
                }
            }
            map.invalidate()
        }
    }

    private fun getTintedMarkerDrawable(context: Context, iconName: String, color: Int): Drawable? {
        val key = "$iconName-$color"
        markerIconCache[key]?.let { return it }
        val resId = context.resources.getIdentifier(iconName, "drawable", context.packageName)
        val base = if (resId != 0) ContextCompat.getDrawable(context, resId) else ContextCompat.getDrawable(context, R.drawable.ic_menu_camera)
        val drawable = base?.mutate()
        try {
            if (drawable != null) {
                drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
                markerIconCache[key] = drawable
            }
        } catch (_: Exception) { }
        return drawable
    }

    private fun toggleSatellite() {
        val map = mapView ?: return
        isSatellite = !isSatellite
        map.setTileSource(if (isSatellite) TileSourceFactory.USGS_SAT else TileSourceFactory.MAPNIK)
        val fab = view?.findViewById<FloatingActionButton>(R.id.fab_toggle_sat)
        // Satellite state -> show mapmode icon; Standard state -> show gallery icon
        fab?.setImageResource(if (isSatellite) android.R.drawable.ic_menu_mapmode else android.R.drawable.ic_menu_gallery)
        fab?.contentDescription = if (isSatellite) "Switch to standard" else "Switch to satellite"
        Toast.makeText(requireContext(), if (isSatellite) "Satellite view" else "Standard view", Toast.LENGTH_SHORT).show()
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
            val padded = BoundingBox(bb.latNorth + 0.01, bb.lonEast + 0.01, bb.latSouth - 0.01, bb.lonWest - 0.01)
            map.zoomToBoundingBox(padded, true, 60)
        }
        map.invalidate()
    }

    private fun enableMyLocationIfPermitted() {
        val ctx = requireContext()
        val fineGranted = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), locationRequestCode)
            return
        }
        if (locationOverlay == null) {
            mapView?.let { mv ->
                val provider = GpsMyLocationProvider(ctx)
                locationOverlay = MyLocationNewOverlay(provider, mv).apply {
                    enableMyLocation()
                    // Intentionally NOT enabling follow or recentering to honor requirement.
                }
                mv.overlays.add(locationOverlay)
                mv.invalidate()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationRequestCode) {
            if (grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                enableMyLocationIfPermitted()
            } else {
                Toast.makeText(requireContext(), "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        locationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        locationOverlay?.disableMyLocation()
        mapView?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView = null
        placeholder = null
    }
}
