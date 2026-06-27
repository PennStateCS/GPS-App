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
 * Intentionally holds only plain values: no live [app.surrealar.gnss.model.Fix], Android
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

    fun update(transform: (MapUiState) -> MapUiState) { state = transform(state) }

    /** One-time seed of session state from durable defaults (only on the first map open of a session). */
    fun seedFromDefaults(s: MapSettings) {
        if (seededFromDefaults) return
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
