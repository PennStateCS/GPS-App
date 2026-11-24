package app.surrealar.ui.rendermap

import android.content.Context
import android.view.View
import android.widget.TextView
import app.surrealar.R
import app.surrealar.ui.map.MapThemeHelper
import com.google.android.gms.maps.GoogleMap

/** Map type cycle and labels for the Map Tools "Map type" row. Pure. */
internal object MapTypes {
    // Cycle order (no MAP_TYPE_NONE): Normal → Satellite → Hybrid → Terrain → Normal.
    private val mapTypeCycle = intArrayOf(
        GoogleMap.MAP_TYPE_NORMAL,
        GoogleMap.MAP_TYPE_SATELLITE,
        GoogleMap.MAP_TYPE_HYBRID,
        GoogleMap.MAP_TYPE_TERRAIN,
    )

    /** The type after [type] in the cycle (an unknown type continues from Normal). */
    fun next(type: Int): Int {
        val idx = mapTypeCycle.indexOf(type).coerceAtLeast(0)
        return mapTypeCycle[(idx + 1) % mapTypeCycle.size]
    }

    fun label(type: Int): String = when (type) {
        GoogleMap.MAP_TYPE_SATELLITE -> "Satellite"
        GoogleMap.MAP_TYPE_HYBRID    -> "Hybrid"
        GoogleMap.MAP_TYPE_TERRAIN   -> "Terrain"
        else                         -> "Normal"
    }
}

/**
 * The Map Tools overlay (collapsible, AR-style toolbar): its open/closed state, the map type row
 * (cycle + theme) and the My Location row's look. Session state goes to [mapUiVm]. The other rows
 * (center, zoom, fit, grid, labels) are wired by their owners.
 */
internal class MapToolsController(
    private val map: () -> GoogleMap?,
    private val context: () -> Context?,
    private val mapUiVm: MapUiStateViewModel,
    private val logMapEvent: (category: String, message: String) -> Unit,
    private val showSnackbar: (String) -> Unit,
) {
    var currentMapType = GoogleMap.MAP_TYPE_NORMAL
        private set
    private var mapTypeRow: View? = null
    private var txtMapType: TextView? = null

    // Map Tools overlay (collapsible toolbar modeled on the AR floating toolbar)
    private var mapToolsToggle: View? = null
    private var mapToolsPanel: View? = null
    var mapToolsVisible = false
        private set

    private var myLocationRow: View? = null

    private fun requireContext(): Context = checkNotNull(context()) { "Fragment not attached to a context." }

    /** Restores the session state before the views and map are wired. */
    fun restoreState(mapType: Int, toolsOpen: Boolean) {
        currentMapType = mapType
        mapToolsVisible = toolsOpen
    }

    /**
     * Wires the toggle + panel, the map type row and the My Location row. The toggle slides the panel
     * in/out (AR pattern); the panel is re-opened/closed just as the user left it.
     */
    fun bindViews(root: View, showCurrentLocation: Boolean, onMyLocationClick: () -> Unit) {
        mapToolsToggle = root.findViewById(R.id.btnMapToolsToggle)
        mapToolsPanel = root.findViewById(R.id.layoutMapTools)
        mapToolsToggle?.setOnClickListener { toggleMapTools() }
        restoreMapToolsState()   // re-open/close just as the user left it before navigating away
        mapTypeRow = root.findViewById(R.id.btnMapType)
        txtMapType = root.findViewById(R.id.txt_map_type)
        mapTypeRow?.setOnClickListener { cycleMapType() }
        updateMapTypeButton()
        myLocationRow = root.findViewById(R.id.btnMapMyLocation)
        myLocationRow?.setOnClickListener { onMyLocationClick() }
        updateMyLocationRowState(showCurrentLocation)
    }

    /** onMapReady: applies the session map type and its theme. */
    fun applyToMap(map: GoogleMap) {
        map.mapType = currentMapType
        MapThemeHelper.applyTheme(requireContext(), map, currentMapType)
    }

    fun onDestroyView() {
        mapTypeRow = null; txtMapType = null
        mapToolsToggle = null; mapToolsPanel = null
        myLocationRow = null
    }

    // ── Map Tools overlay (collapsible, AR-style) ──────────────────────────────

    /** Slides the Map Tools panel in/out, mirroring the AR floating-toolbar toggle. */
    private fun toggleMapTools() {
        setMapToolsOpen(!mapToolsVisible, animate = true)
    }

    /** Applies the open/closed state to the panel + toggle, persisting it to the session ViewModel. */
    private fun setMapToolsOpen(open: Boolean, animate: Boolean) {
        val panel = mapToolsPanel ?: return
        mapToolsVisible = open
        mapUiVm.update { it.copy(isMapToolsOpen = open) }
        if (animate) logMapEvent("MAP", "Map tools ${if (open) "opened" else "closed"}")
        if (open) {
            mapToolsToggle?.contentDescription = "Hide map tools"
            if (animate) {
                panel.translationX = panel.width.toFloat().coerceAtLeast(200f)
                panel.alpha = 0f
                panel.visibility = View.VISIBLE
                panel.animate().translationX(0f).alpha(1f).setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                mapToolsToggle?.animate()?.rotation(90f)?.setDuration(200)?.start()
            } else {
                panel.translationX = 0f; panel.alpha = 1f; panel.visibility = View.VISIBLE
                mapToolsToggle?.rotation = 90f
            }
        } else {
            mapToolsToggle?.contentDescription = "Show map tools"
            if (animate) {
                panel.animate().translationX(panel.width.toFloat().coerceAtLeast(200f)).alpha(0f)
                    .setDuration(160).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { panel.visibility = View.GONE }.start()
                mapToolsToggle?.animate()?.rotation(0f)?.setDuration(160)?.start()
            } else {
                panel.visibility = View.GONE
                mapToolsToggle?.rotation = 0f
            }
        }
    }

    /** Re-applies the saved Map Tools open/closed state to the freshly created views (no animation). */
    private fun restoreMapToolsState() {
        val panel = mapToolsPanel ?: return
        // Defer until the panel has been measured so a "closed" state lays out correctly.
        panel.post { setMapToolsOpen(mapToolsVisible, animate = false) }
    }

    // ── Map layers ─────────────────────────────────────────────────────────────

    /** Advances the map type one step through the cycle and applies it. */
    private fun cycleMapType() {
        applyMapType(MapTypes.next(currentMapType))
        showSnackbar("Map type: ${MapTypes.label(currentMapType)}")
    }

    private fun applyMapType(type: Int) {
        currentMapType = type
        mapUiVm.update { it.copy(mapType = type) }
        map()?.mapType = type
        map()?.let { MapThemeHelper.applyTheme(requireContext(), it, type) }
        updateMapTypeButton()
        logMapEvent("MAP", "Map type changed type=${MapTypes.label(type)}")
    }

    /** Shows the current map type on the toolbar row and announces the next one for accessibility. */
    private fun updateMapTypeButton() {
        val current = MapTypes.label(currentMapType)
        val next = MapTypes.label(MapTypes.next(currentMapType))
        txtMapType?.text = current
        mapTypeRow?.contentDescription = "Map type: $current. Tap to switch to $next."
    }

    // ── My Location row ────────────────────────────────────────────────────────

    /** Reflects the toggle state on the toolbar row (dimmed = off) and swaps its content description. */
    fun updateMyLocationRowState(showCurrentLocation: Boolean) {
        myLocationRow?.alpha = if (showCurrentLocation) 1.0f else 0.4f
        myLocationRow?.contentDescription =
            if (showCurrentLocation) "Hide my location and direction on map"
            else "Show my location and direction on map"
    }
}
