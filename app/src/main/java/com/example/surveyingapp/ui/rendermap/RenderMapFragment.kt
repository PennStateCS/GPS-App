package com.example.surveyingapp.ui.rendermap

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
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
import java.util.Locale

class RenderMapFragment : Fragment() {

    private var mapView: MapView? = null
    private var placeholder: View? = null

    // Keep overlays/components around
    private var locationOverlay: MyLocationNewOverlay? = null
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

    // Data class for displaying points with visibility
    data class MapPointDisplay(
        val geoPoint: GeoPoint,
        val name: String,
        val iconName: String,
        val color: Int,
        var isVisible: Boolean = true,
        var marker: Marker? = null
    )

    private lateinit var pointsAdapter: PointsAdapter
    private val displayPoints = mutableListOf<MapPointDisplay>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_render_map, container, false)
        mapView = root.findViewById(R.id.mapView)
        placeholder = root.findViewById(R.id.text_render_map)

        setupMap(requireContext())

        root.findViewById<FloatingActionButton>(R.id.fab_toggle_sat)
            ?.setOnClickListener { toggleSatellite() }
        root.findViewById<FloatingActionButton>(R.id.fab_recenter)
            ?.setOnClickListener { recenterMap() }

        enableMyLocationIfPermitted()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize RecyclerView and adapter
        val recyclerView = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pointsRecyclerView)
        pointsAdapter = PointsAdapter(displayPoints) { position, isChecked ->
            displayPoints[position].isVisible = isChecked
            updateMarkerVisibility(position)
        }
        recyclerView.adapter = pointsAdapter
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())

        // Setup collapsible points list
        setupCollapsiblePointsList(view)

        // Now that adapter is initialized, bind data
        bindData()
    }

    private fun setupCollapsiblePointsList(view: View) {
        val pointsListContainer = view.findViewById<View>(R.id.pointsListContainer)
        val btnTogglePointsList = view.findViewById<ImageButton>(R.id.btnTogglePointsList)
        val btnShowPointsList = view.findViewById<ImageButton>(R.id.btnShowPointsList)

        // Initially show the list
        var isListExpanded = true

        // Toggle button (collapse) click listener
        btnTogglePointsList.setOnClickListener {
            isListExpanded = false
            animateListCollapse(pointsListContainer, btnShowPointsList)
        }

        // Show button (expand) click listener
        btnShowPointsList.setOnClickListener {
            isListExpanded = true
            animateListExpand(pointsListContainer, btnShowPointsList)
        }
    }

    private fun animateListCollapse(container: View, showButton: ImageButton) {
        // Animate the container sliding out to the left
        ObjectAnimator.ofFloat(container, "translationX", 0f, -container.width.toFloat()).apply {
            duration = 300
            start()
        }

        // Show the expand button after animation
        showButton.postDelayed({
            showButton.visibility = View.VISIBLE
            showButton.alpha = 0f
            ObjectAnimator.ofFloat(showButton, "alpha", 0f, 1f).apply {
                duration = 200
                start()
            }
        }, 300)
    }

    private fun animateListExpand(container: View, showButton: ImageButton) {
        // Hide the show button first
        ObjectAnimator.ofFloat(showButton, "alpha", 1f, 0f).apply {
            duration = 200
            start()
        }

        showButton.postDelayed({
            showButton.visibility = View.GONE

            // Animate the container sliding in from the left
            ObjectAnimator.ofFloat(container, "translationX", -container.width.toFloat(), 0f).apply {
                duration = 300
                start()
            }
        }, 200)
    }

    private fun updateMarkerVisibility(position: Int) {
        val point = displayPoints[position]
        val map = mapView ?: return

        if (point.isVisible) {
            if (point.marker == null) {
                val marker = Marker(map)
                marker.position = point.geoPoint
                marker.title = point.name
                marker.icon = getTintedMarkerDrawable(requireContext(), point.iconName, point.color)
                map.overlays.add(marker)
                point.marker = marker
            }
        } else {
            point.marker?.let {
                map.overlays.remove(it)
                point.marker = null
            }
        }
        map.invalidate()
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

            // Remove all markers managed by displayPoints
            for (point in displayPoints) {
                point.marker?.let { map.overlays.remove(it) }
                point.marker = null
            }
            displayPoints.clear()

            if (points.isEmpty()) {
                placeholder?.visibility = View.VISIBLE
                lastGeoPoints = emptyList()
            } else {
                placeholder?.visibility = View.GONE
                val geoPoints = mutableListOf<GeoPoint>()
                points.forEachIndexed { i, p ->
                    val gp = GeoPoint(p.latitude, p.longitude)
                    geoPoints.add(gp)
                    displayPoints.add(MapPointDisplay(gp, p.name ?: "Point ${i + 1}", p.icon ?: "default_marker", p.color ?: 0, true))
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
                    val padded = BoundingBox(
                        bb.latNorth + 0.01, bb.lonEast + 0.01,
                        bb.latSouth - 0.01, bb.lonWest - 0.01
                    )
                    map.zoomToBoundingBox(padded, true)
                }
            }
            pointsAdapter.notifyDataSetChanged()
            updateAllMarkers()
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

    private fun updateAllMarkers() {
        val map = mapView ?: return
        // Remove all markers from overlays
        for (point in displayPoints) {
            point.marker?.let { map.overlays.remove(it) }
            point.marker = null
        }
        // Add visible markers with custom icons
        for (point in displayPoints) {
            if (point.isVisible) {
                val marker = Marker(map)
                marker.position = point.geoPoint
                marker.title = point.name
                marker.icon = getTintedMarkerDrawable(requireContext(), point.iconName, point.color)
                map.overlays.add(marker)
                point.marker = marker
            }
        }
        map.invalidate()
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
        locationOverlay = null
        mapView = null
        placeholder = null
        super.onDestroyView()
    }

    // RecyclerView Adapter for points
    class PointsAdapter(
        private val points: List<MapPointDisplay>,
        private val onToggle: (Int, Boolean) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<PointsAdapter.PointViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PointViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_map_point, parent, false)
            return PointViewHolder(view)
        }
        override fun getItemCount() = points.size
        override fun onBindViewHolder(holder: PointViewHolder, position: Int) {
            val point = points[position]
            holder.title.text = point.name

            // Set the colored icon
            val context = holder.itemView.context
            val drawable = getTintedMarkerDrawable(context, point.iconName, point.color)
            holder.icon.setImageDrawable(drawable)

            // Clear any previous listener to avoid issues
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = point.isVisible

            // Set the listener after setting the checked state
            holder.switch.setOnCheckedChangeListener { _, isChecked ->
                onToggle(position, isChecked)
            }
        }

        private fun getTintedMarkerDrawable(context: Context, iconName: String, color: Int): Drawable? {
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
            return wrapped
        }

        class PointViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val title: android.widget.TextView = view.findViewById(R.id.map_point_title)
            val icon: android.widget.ImageView = view.findViewById(R.id.map_point_icon)
            val switch: android.widget.Switch = view.findViewById(R.id.map_point_visibility_switch)
        }
    }
}
