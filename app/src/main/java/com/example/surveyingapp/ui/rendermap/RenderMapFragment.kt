package com.example.surveyingapp.ui.rendermap

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import com.example.surveyingapp.R
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.ui.viewpoints.CoordinatesViewModel
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.animation.doOnEnd
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView

class RenderMapFragment : Fragment() {
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var placeholder: View? = null
    private var lastLatLngs: List<LatLng> = emptyList()
    private val markers = mutableListOf<Marker>()
    private var isSatellite = false
    private var dataObserved = false
    private var cameraInitialized = false

    private var leftPanel: View? = null
    private var panelHandle: View? = null
    private var collapseBtn: ImageButton? = null
    private var expandBtn: ImageButton? = null

    private var panelWidthPx: Int = 0
    private var panelCollapsed = false

    private val minPanelDp = 160f
    private val maxPanelDp = 480f

    private var toggleRecycler: RecyclerView? = null
    private lateinit var toggleAdapter: CoordinateToggleAdapter
    private val markerMap = mutableMapOf<String, Marker>()
    private val visibilityMap = mutableMapOf<String, Boolean>() // id -> visible
    private val coordinateMap = mutableMapOf<String, Coordinate>() // id -> coordinate data
    private var toggleSatBtn: FloatingActionButton? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_render_map, container, false)
        mapView = root.findViewById(R.id.mapView)
        placeholder = root.findViewById(R.id.text_render_map)
        toggleRecycler = root.findViewById(R.id.coordinate_toggle_list)
        toggleSatBtn = root.findViewById(R.id.fab_toggle_sat)
        toggleAdapter = CoordinateToggleAdapter { id, checked ->
            visibilityMap[id] = checked
            markerMap[id]?.isVisible = checked
        }
        toggleRecycler?.layoutManager = LinearLayoutManager(requireContext())
        toggleRecycler?.adapter = toggleAdapter
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync { map ->
            googleMap = map
            googleMap?.mapType = if (isSatellite) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL

            // Allow much closer zooming - set maximum zoom level to 22 (very close)
            googleMap?.setMaxZoomPreference(22f)
            googleMap?.setMinZoomPreference(2f)

            // Set up custom info window adapter
            googleMap?.setInfoWindowAdapter(object : GoogleMap.InfoWindowAdapter {
                override fun getInfoWindow(marker: Marker): View? = null // Use default frame

                override fun getInfoContents(marker: Marker): View? {
                    return createInfoWindowView(marker)
                }
            })

            // Set up marker click listener to show info window
            googleMap?.setOnMarkerClickListener { marker ->
                marker.showInfoWindow()
                true // Return true to consume the event
            }

            Log.d("RenderMap", "GoogleMap ready")
            googleMap?.setOnMapLoadedCallback {
                Log.d("RenderMap", "Map loaded callback")
                if (!cameraInitialized && lastLatLngs.isNotEmpty()) updateCamera(lastLatLngs)
            }
            bindData()
        }
        toggleSatBtn?.setOnClickListener { toggleSatellite() }
        root.findViewById<FloatingActionButton>(R.id.fab_recenter)?.setOnClickListener { recenterMap() }
        root.findViewById<FloatingActionButton>(R.id.fab_zoom_in)?.setOnClickListener { googleMap?.animateCamera(CameraUpdateFactory.zoomIn()) }
        root.findViewById<FloatingActionButton>(R.id.fab_zoom_out)?.setOnClickListener { googleMap?.animateCamera(CameraUpdateFactory.zoomOut()) }
        if (savedInstanceState != null) {
            panelWidthPx = savedInstanceState.getInt("panelWidthPx", 0)
            panelCollapsed = savedInstanceState.getBoolean("panelCollapsed", false)
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // init panel references
        leftPanel = view.findViewById(R.id.left_panel)
        panelHandle = view.findViewById(R.id.panel_handle)
        collapseBtn = view.findViewById(R.id.btn_collapse_panel)
        expandBtn = view.findViewById(R.id.btn_expand_panel)

        // Set initial satellite button icon
        updateSatelliteButtonIcon()

        if (panelWidthPx == 0) {
            panelWidthPx = dpToPx(260f)
        }
        applyPanelState()
        setupPanelInteractions()
    }

    // Lifecycle pass-throughs
    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onDestroy() { super.onDestroy(); mapView?.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("panelWidthPx", panelWidthPx)
        outState.putBoolean("panelCollapsed", panelCollapsed)
        mapView?.onSaveInstanceState(outState)
    }
    override fun onDestroyView() { mapView = null; placeholder = null; super.onDestroyView() }

    private fun bindData() {
        if (dataObserved) return
        dataObserved = true
        val vm = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(requireActivity().application))
            .get(CoordinatesViewModel::class.java)
        vm.allCoordinates.observe(viewLifecycleOwner) { points ->
            if (googleMap == null) return@observe
            markerMap.values.forEach { it.remove() }
            markerMap.clear()
            if (points.isEmpty()) {
                placeholder?.visibility = View.VISIBLE
                lastLatLngs = emptyList()
                toggleAdapter.submit(emptyList())
                return@observe
            }
            placeholder?.visibility = View.GONE
            val latLngsVisible = ArrayList<LatLng>()
            val toggleItems = mutableListOf<CoordinateToggleItem>()
            points.forEach { p ->
                val ll = LatLng(p.latitude, p.longitude)
                val visible = visibilityMap[p.id] ?: true
                visibilityMap.putIfAbsent(p.id, visible)
                val descriptor = buildMarkerDescriptor(p.icon, p.color)
                val opts = MarkerOptions().position(ll).title(p.name)
                if (descriptor != null) opts.icon(descriptor)
                val marker = googleMap!!.addMarker(opts)
                if (marker != null) {
                    marker.isVisible = visible
                    marker.tag = p.id // Store coordinate ID in marker tag
                    markerMap[p.id] = marker
                    if (visible) latLngsVisible.add(ll)
                }
                toggleItems += CoordinateToggleItem(p.id, p.name, visible, p.icon, p.color)
                coordinateMap[p.id] = p // Add to coordinate map
            }
            lastLatLngs = latLngsVisible
            toggleAdapter.submit(toggleItems)
            updateCamera(latLngsVisible)
        }
    }

    private fun buildMarkerDescriptor(iconName: String?, colorInt: Int): BitmapDescriptor? {
        val ctx = context ?: return null
        if (iconName.isNullOrBlank()) return null
        val resId = ctx.resources.getIdentifier(iconName, "drawable", ctx.packageName)
        if (resId == 0) return null
        val d = ContextCompat.getDrawable(ctx, resId) ?: return null
        val size = dpToPx(32f) // uniform size
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        try {
            d.mutate().setColorFilter(colorInt, PorterDuff.Mode.SRC_IN)
        } catch (_: Exception) {}
        d.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private fun updateCamera(latLngs: List<LatLng>) {
        val map = googleMap ?: return
        if (latLngs.isEmpty()) return
        try {
            if (latLngs.size == 1) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 20f))
            } else if (!cameraInitialized) {
                val builder = LatLngBounds.builder()
                latLngs.forEach { builder.include(it) }
                val bounds = builder.build()
                mapView?.post {
                    try { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100)) } catch (_: Exception) {}
                }
            }
            cameraInitialized = true
        } catch (e: Exception) {
            Log.e("RenderMap", "Camera update error", e)
        }
    }

    private fun recenterMap() { updateCamera(markerMap.filter { it.value.isVisible }.values.map { it.position }) }
    private fun toggleSatellite() {
        isSatellite = !isSatellite
        googleMap?.mapType = if (isSatellite) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL
        updateSatelliteButtonIcon()
    }

    private fun updateSatelliteButtonIcon() {
        toggleSatBtn?.setImageResource(
            if (isSatellite) {
                android.R.drawable.ic_menu_mapmode // Map icon when in satellite mode (to switch back to map)
            } else {
                android.R.drawable.ic_menu_gallery // Satellite/gallery icon when in map mode (to switch to satellite)
            }
        )
    }

    private fun setupPanelInteractions() {
        collapseBtn?.setOnClickListener { collapsePanel() }
        expandBtn?.setOnClickListener { expandPanel() }
        panelHandle?.setOnTouchListener { v, event ->
            if (panelCollapsed) return@setOnTouchListener false
            val lp = leftPanel?.layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    lastDragX = event.rawX
                    startDragWidth = lp.width
                    dragStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastDragX).toInt()
                    var newWidth = startDragWidth + dx
                    val minPx = dpToPx(minPanelDp)
                    val maxPx = dpToPx(maxPanelDp)
                    if (newWidth < minPx) newWidth = minPx
                    if (newWidth > maxPx) newWidth = maxPx
                    panelWidthPx = newWidth
                    lp.width = newWidth
                    leftPanel?.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val totalDx = event.rawX - lastDragX
                    val dt = (System.currentTimeMillis() - dragStartTime).coerceAtLeast(1)
                    val velocity = totalDx / dt.toFloat()
                    if (totalDx < -dpToPx(80f) || velocity < -0.6f) {
                        collapsePanel()
                    } else {
                        // treat as click if minimal movement
                        if (kotlin.math.abs(totalDx) < dpToPx(4f)) v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        leftPanel?.setOnTouchListener { v, ev ->
            if (panelCollapsed) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    panelSwipeDownX = ev.rawX
                    panelSwipeDownTime = System.currentTimeMillis()
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - panelSwipeDownX
                    val dt = (System.currentTimeMillis() - panelSwipeDownTime).coerceAtLeast(1)
                    val vVel = dx / dt.toFloat()
                    if (dx < -dpToPx(100f) || vVel < -0.7f) {
                        collapsePanel(); true
                    } else {
                        if (kotlin.math.abs(dx) < dpToPx(8f)) v.performClick()
                        false
                    }
                }
                else -> false
            }
        }
    }

    private var lastDragX: Float = 0f
    private var startDragWidth: Int = 0
    private var dragStartTime: Long = 0L
    private var panelSwipeDownX: Float = 0f
    private var panelSwipeDownTime: Long = 0L

    private fun collapsePanel() {
        if (panelCollapsed) return
        val lp = leftPanel?.layoutParams ?: return
        val start = lp.width
        val anim = ValueAnimator.ofInt(start, 0)
        anim.duration = 200
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.addUpdateListener {
            val v = it.animatedValue as Int
            lp.width = v
            leftPanel?.layoutParams = lp
        }
        anim.doOnEnd {
            panelCollapsed = true
            leftPanel?.visibility = View.GONE
            panelHandle?.visibility = View.GONE
            expandBtn?.visibility = View.VISIBLE
        }
        anim.start()
    }

    private fun expandPanel() {
        if (!panelCollapsed) return
        val target = if (panelWidthPx <= 0) dpToPx(260f) else panelWidthPx
        panelWidthPx = target
        leftPanel?.visibility = View.VISIBLE
        panelHandle?.visibility = View.VISIBLE
        expandBtn?.visibility = View.GONE
        val lp = leftPanel?.layoutParams ?: return
        lp.width = 0
        leftPanel?.layoutParams = lp
        val anim = ValueAnimator.ofInt(0, target)
        anim.duration = 220
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.addUpdateListener {
            val v = it.animatedValue as Int
            lp.width = v
            leftPanel?.layoutParams = lp
        }
        anim.doOnEnd { panelCollapsed = false }
        anim.start()
    }

    private fun applyPanelState() {
        if (panelCollapsed) {
            leftPanel?.visibility = View.GONE
            panelHandle?.visibility = View.GONE
            expandBtn?.visibility = View.VISIBLE
        } else {
            val lp = leftPanel?.layoutParams
            if (lp != null) {
                if (panelWidthPx <= 0) panelWidthPx = dpToPx(260f)
                lp.width = panelWidthPx
                leftPanel?.layoutParams = lp
            }
            leftPanel?.visibility = View.VISIBLE
            panelHandle?.visibility = View.VISIBLE
            expandBtn?.visibility = View.GONE
        }
    }

    private fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun createInfoWindowView(marker: Marker): View? {
        val ctx = context ?: return null
        val coordinateId = marker.tag as? String
        val coordinate = coordinateId?.let { coordinateMap[it] }

        val view = layoutInflater.inflate(R.layout.custom_info_window, null)
        val titleView = view.findViewById<TextView>(R.id.info_window_title)
        val contentView = view.findViewById<TextView>(R.id.info_window_content)

        titleView.text = coordinate?.name ?: "Unknown"
        contentView.text = buildString {
            if (coordinate != null) {
                appendLine("Lat: ${"%.6f".format(coordinate.latitude)}")
                appendLine("Lng: ${"%.6f".format(coordinate.longitude)}")
                append("Alt: ${"%.2f".format(coordinate.altitude)} m")
            } else {
                append("No data available")
            }
        }

        return view
    }
}
