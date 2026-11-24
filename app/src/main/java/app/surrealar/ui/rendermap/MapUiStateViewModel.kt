package app.surrealar.ui.rendermap

import androidx.lifecycle.ViewModel
import com.google.android.gms.maps.GoogleMap

/**
 * A lightweight saved camera snapshot. Plain values only — no Google Map / Marker / view references.
 */
data class MapCameraState(
    val lat: Double,
    val lng: Double,
    val zoom: Float,
    val bearing: Float,
    val tilt: Float,
)

/**
 * Transient map-page UI/display state that should survive navigating away from the map (to point
 * details, AR, etc.) and back, plus configuration changes — but NOT a full app restart.
 *
 * Intentionally holds only plain values: no live [io.github.pennstatecs.geokit.gnss.model.Fix], Android
 * views, `GoogleMap`, `Marker`, `Circle`, or `Context`. `mapType` uses the project's existing
 * `GoogleMap.MAP_TYPE_*` integer (never `MAP_TYPE_NONE`); the selected point is referenced by ID only.
 */
data class MapUiState(
    val isMapToolsOpen: Boolean = false,
    val mapType: Int = GoogleMap.MAP_TYPE_NORMAL,
    val gridMode: MapGridMode = MapGridMode.OFF,
    val pointLabelMode: PointLabelMode = PointLabelMode.OFF,
    val showCurrentLocation: Boolean = true,
    val isLeftPanelCollapsed: Boolean = false,
    val selectedCoordinateId: String? = null,
    val camera: MapCameraState? = null,
    /** The running stakeout, so it continues after Details/AR/tab switches; null = no stakeout. */
    val stakeout: StakeoutSession? = null,
    /**
     * Last Settings → Stakeout values seen by the map, so a restored guidance in a NEW fragment
     * (tab switch) uses them instead of the defaults until DataStore emits. A cache, never written back.
     */
    val stakeoutSettings: app.surrealar.settings.model.StakeoutSettings? = null,
)

/**
 * The stakeout operation to restore when the map view comes back. Plain values only. A
 * coordinate-backed target ([targetId] != null) is re-checked against the database on return
 * (deleted → stakeout ends, moved → follows the point); the readout is never restored, it is
 * recomputed from the next live fix.
 */
data class StakeoutSession(
    /** Saved coordinate being staked out; null for a free map-tap target. */
    val targetId: String?,
    /** Target position (WGS84, as plotted); null only when stakeout mode is on without a target yet. */
    val targetLat: Double?,
    val targetLng: Double?,
    /** Text shown as the card's target line (point name, or the tapped lat/lon). */
    val targetLabel: String,
    /** Guidance mode was on; its resources are re-acquired by the normal onResume path. */
    val guidanceActive: Boolean,
)

/**
 * Holder for [MapUiState]. Scoped to the ACTIVITY (`by activityViewModels()`) so the state survives
 * bottom-nav tab switches (Home ⇄ Map recreate the map fragment), forward navigation (Details/AR),
 * and config changes — cleared only when the activity finishes. That gives the "session, not
 * permanent" lifetime we want. Permanent user preferences stay in DataStore; this never touches it.
 */
class MapUiStateViewModel : ViewModel() {
    var state: MapUiState = MapUiState()
        private set

    /** True once the session state has been seeded from persistent [MapSettings] defaults. */
    var seededFromDefaults: Boolean = false
        private set

    /** The defaults the session was last seeded from; a different value means Settings changed. */
    private var lastSeededDefaults: MapSettings? = null

    fun update(transform: (MapUiState) -> MapUiState) { state = transform(state) }

    /**
     * Seeds session state from durable defaults on the first map open of a session, and again
     * whenever those defaults have CHANGED in Settings since the last seed — so a new default takes
     * effect the next time the map opens. Otherwise the user's in-session choices are kept.
     */
    fun seedFromDefaults(s: MapSettings) {
        if (s == lastSeededDefaults) return
        lastSeededDefaults = s
        state = state.copy(
            mapType = s.defaultMapType,
            gridMode = s.defaultGridMode,
            pointLabelMode = s.defaultPointLabelMode,
            showCurrentLocation = s.showMyLocationByDefault,
            isMapToolsOpen = s.keepMapToolsOpenByDefault,
            isLeftPanelCollapsed = !s.mapPointsDrawerExpandedByDefault,
        )
        seededFromDefaults = true
    }
}
