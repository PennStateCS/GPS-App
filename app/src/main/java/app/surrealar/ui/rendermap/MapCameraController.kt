package app.surrealar.ui.rendermap

import android.view.View
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds

/** Saved session camera → Google Maps camera position. */
internal fun MapCameraState.toCameraPosition(): CameraPosition =
    CameraPosition.Builder()
        .target(LatLng(lat, lng))
        .zoom(zoom).bearing(bearing).tilt(tilt).build()

/** Google Maps camera position → plain session snapshot (no map references). */
internal fun CameraPosition.toMapCameraState(): MapCameraState =
    MapCameraState(
        lat = target.latitude, lng = target.longitude,
        zoom = zoom, bearing = bearing, tilt = tilt,
    )

/**
 * Camera placement for the survey map: restores the session camera (or the default view) when the
 * map is ready, auto-fits the visible points once, recenter / fit / centre-on-point, and saves the
 * camera into [mapUiVm] on pause and camera idle. Owns the visible point positions of the last
 * coordinate load ([lastLatLngs]) used for the first auto-fit.
 */
internal class MapCameraController(
    private val map: () -> GoogleMap?,
    private val mapView: () -> View?,
    private val mapUiVm: MapUiStateViewModel,
    private val markers: CoordinateMarkerLayer,
    private val liveLatLng: () -> LatLng?,
    private val showSnackbar: (String) -> Unit,
) {
    /** True once the camera was restored or auto-fitted. Kept for the fragment's lifetime (as before). */
    private var cameraInitialized = false
    private var lastLatLngs: List<LatLng> = emptyList()

    /**
     * Restores the saved camera if returning to the map; else the default view (the saved-coord
     * auto-fit in [onMapLoaded] only runs when no camera was restored, via cameraInitialized).
     * Returns whether a saved camera was restored.
     */
    fun restoreOrDefault(map: GoogleMap): Boolean {
        val savedCam = mapUiVm.state.camera
        if (savedCam != null) {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(savedCam.toCameraPosition()))
            cameraInitialized = true
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.7963, -77.8570), 15f))
        }
        return savedCam != null
    }

    /** Map tiles loaded: fit the visible points unless a camera was already placed. */
    fun onMapLoaded() {
        if (!cameraInitialized && lastLatLngs.isNotEmpty()) updateCamera(lastLatLngs)
    }

    /** A coordinate load finished with these visible positions. */
    fun onCoordinatesLoaded(latLngsVisible: List<LatLng>) {
        lastLatLngs = latLngsVisible
        updateCamera(latLngsVisible)
    }

    /** No saved coordinates (or the view went away). */
    fun clearCoordinates() { lastLatLngs = emptyList() }

    fun updateCamera(latLngs: List<LatLng>) {
        val map = map() ?: return
        if (latLngs.isEmpty()) return
        try {
            if (latLngs.size == 1) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 20f))
            } else if (!cameraInitialized) {
                val bounds = LatLngBounds.builder().also { b -> latLngs.forEach { b.include(it) } }.build()
                mapView()?.post { try { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100)) } catch (_: Exception) {} }
            }
            cameraInitialized = true
        } catch (e: Exception) { logFail("camera update", e) }
    }

    /** Saves the current camera into the session ViewModel so it can be restored on return. */
    fun saveCameraState() {
        val cam = map()?.cameraPosition ?: return
        mapUiVm.update { it.copy(camera = cam.toMapCameraState()) }
    }

    fun recenterMap() {
        val map = map() ?: return
        val live = liveLatLng()
        if (live != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(live, 20f))
            return
        }
        val visiblePositions = markers.visibleMarkerPositions()
        if (visiblePositions.isNotEmpty()) {
            updateCamera(visiblePositions)
        } else {
            showSnackbar("Current location unavailable")
        }
    }

    fun fitVisibleCoordinates() {
        val visiblePositions = markers.visibleMarkerPositions()
        if (visiblePositions.isEmpty()) {
            showSnackbar("No visible coordinates")
            return
        }
        if (visiblePositions.size == 1) {
            map()?.animateCamera(CameraUpdateFactory.newLatLngZoom(visiblePositions.first(), 18f))
            return
        }
        val bounds = LatLngBounds.builder().also { b -> visiblePositions.forEach { b.include(it) } }.build()
        mapView()?.post {
            try { map()?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80)) }
            catch (_: Exception) { map()?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0)) }
        }
    }

    /** Centres on a point, keeping zoom at least 16 (selected point / its Center button). */
    fun centerOn(lat: Double, lon: Double) {
        map()?.animateCamera(CameraUpdateFactory.newLatLngZoom(
            LatLng(lat, lon),
            (map()?.cameraPosition?.zoom ?: 18f).coerceAtLeast(16f)
        ))
    }

    fun zoomIn() { map()?.animateCamera(CameraUpdateFactory.zoomIn()) }
    fun zoomOut() { map()?.animateCamera(CameraUpdateFactory.zoomOut()) }

    fun onDestroyView() { lastLatLngs = emptyList() }
}
