package app.surrealar.ui.rendermap

import app.surrealar.coords.CoordinateSystemService
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.repository.ModelRepository
import com.google.android.gms.maps.GoogleMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito

/** Pure logic behind the map controls: map type cycle, camera snapshot, point text, selection. */
class MapControlsTest {

    // ── Map type ──────────────────────────────────────────────────────────────

    @Test fun `map type cycles Normal - Satellite - Hybrid - Terrain - Normal`() {
        assertEquals(GoogleMap.MAP_TYPE_SATELLITE, MapTypes.next(GoogleMap.MAP_TYPE_NORMAL))
        assertEquals(GoogleMap.MAP_TYPE_HYBRID, MapTypes.next(GoogleMap.MAP_TYPE_SATELLITE))
        assertEquals(GoogleMap.MAP_TYPE_TERRAIN, MapTypes.next(GoogleMap.MAP_TYPE_HYBRID))
        assertEquals(GoogleMap.MAP_TYPE_NORMAL, MapTypes.next(GoogleMap.MAP_TYPE_TERRAIN))
    }

    @Test fun `an unknown map type continues from Normal and never selects NONE`() {
        assertEquals(GoogleMap.MAP_TYPE_SATELLITE, MapTypes.next(GoogleMap.MAP_TYPE_NONE))
        assertEquals(GoogleMap.MAP_TYPE_SATELLITE, MapTypes.next(42))
    }

    @Test fun `map type labels`() {
        assertEquals("Normal", MapTypes.label(GoogleMap.MAP_TYPE_NORMAL))
        assertEquals("Satellite", MapTypes.label(GoogleMap.MAP_TYPE_SATELLITE))
        assertEquals("Hybrid", MapTypes.label(GoogleMap.MAP_TYPE_HYBRID))
        assertEquals("Terrain", MapTypes.label(GoogleMap.MAP_TYPE_TERRAIN))
        assertEquals("Normal", MapTypes.label(GoogleMap.MAP_TYPE_NONE))
    }

    // ── Camera snapshot ───────────────────────────────────────────────────────

    @Test fun `saved camera round-trips through a CameraPosition`() {
        val saved = MapCameraState(lat = 41.2459, lng = -75.8813, zoom = 18.5f, bearing = 45f, tilt = 30f)
        val restored = saved.toCameraPosition().toMapCameraState()
        assertEquals(saved, restored)
    }

    // ── Saved point text ──────────────────────────────────────────────────────

    private fun coord(
        provider: String = "fused", rtk: String? = null, device: String? = null, modelId: String? = null,
    ) = Coordinate(
        id = "1", name = "P1", latitude = 41.0, longitude = -76.0, altitude = 300.0,
        timestamp = 0L, icon = "ic_pin", color = 0, provider = provider, rtkStatus = rtk,
        sourceDevice = device, modelId = modelId,
    )

    @Test fun `fix labels`() {
        assertEquals("Fix", CoordinateText.friendlyFix("fix"))
        assertEquals("Float", CoordinateText.friendlyFix("FLOAT"))
        assertEquals("DGPS", CoordinateText.friendlyFix("dgps"))
        assertEquals("Single", CoordinateText.friendlyFix("SINGLE"))
        assertEquals("No fix", CoordinateText.friendlyFix("NONE"))
        assertEquals("No fix", CoordinateText.friendlyFix("INVALID"))
        assertEquals("PPP", CoordinateText.friendlyFix("PPP"))
    }

    @Test fun `source labels never claim an unverifiable source`() {
        assertNull(CoordinateText.friendlySource(null, null))
        assertNull(CoordinateText.friendlySource("fused", null))
        assertEquals("Internal", CoordinateText.friendlySource("INTERNAL", null))
        assertEquals("RS2+", CoordinateText.friendlySource("EXTERNAL_TCP", "Emlid Reach RS2+"))
        assertEquals("External", CoordinateText.friendlySource("EXTERNAL_TCP", "Unknown box"))
    }

    @Test fun `capture source text`() {
        assertNull(CoordinateText.captureSourceText(coord()))
        assertEquals("Source: RS2+ Float",
            CoordinateText.captureSourceText(coord(provider = "EXTERNAL_TCP", rtk = "FLOAT", device = "Emlid Reach RS2+")))
        assertEquals("Source: Fix", CoordinateText.captureSourceText(coord(rtk = "FIX")))
    }

    @Test fun `drawer row meta`() {
        assertEquals("Elev 300.000 m", CoordinateText.rowMeta(coord(), "300.000 m"))
        assertEquals("Elev 300.000 m · Fix · Internal · Model",
            CoordinateText.rowMeta(coord(provider = "INTERNAL", rtk = "FIX", modelId = "m1"), "300.000 m"))
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    private class Harness {
        val vm = MapUiStateViewModel()
        val selectionEvents = mutableListOf<String?>()
        var diagRefreshes = 0
        val markers = CoordinateMarkerLayer(
            map = { null }, context = { null }, scope = { CoroutineScope(Dispatchers.Unconfined) },
            modelRepository = Mockito.mock(ModelRepository::class.java),
        )
        val camera = MapCameraController(
            map = { null }, mapView = { null }, mapUiVm = vm, markers = markers,
            liveLatLng = { null }, showSnackbar = {},
        )
        val selection = SelectedCoordinateController(
            context = { null }, scope = { CoroutineScope(Dispatchers.Unconfined) },
            markers = markers, camera = camera, mapUiVm = vm,
            coordinateSystemService = Mockito.mock(CoordinateSystemService::class.java),
            modelRepository = Mockito.mock(ModelRepository::class.java),
            liveLatLng = { null },
            onSelectionChanged = { selectionEvents += it },
            logMapEvent = { _, _ -> },
            refreshMapDiag = { diagRefreshes++ },
        )
    }

    @Test fun `selecting an unknown point changes nothing`() {
        val h = Harness()
        h.selection.selectCoordinate("missing")
        assertNull(h.selection.selectedCoordinateId)
        assertNull(h.vm.state.selectedCoordinateId)
        assertEquals(emptyList<String?>(), h.selectionEvents)
    }

    @Test fun `a restored selection whose point no longer exists is cleared`() {
        val h = Harness()
        h.vm.update { it.copy(selectedCoordinateId = "gone") }
        h.selection.restoreState("gone")
        h.selection.restoreSelectedCard()
        assertNull(h.selection.selectedCoordinateId)
        assertNull(h.vm.state.selectedCoordinateId)
        assertEquals(listOf<String?>(null), h.selectionEvents)
        assertEquals(1, h.diagRefreshes)
    }

    @Test fun `no restored selection leaves the card alone`() {
        val h = Harness()
        h.selection.restoreSelectedCard()
        assertEquals(emptyList<String?>(), h.selectionEvents)
        assertEquals(0, h.diagRefreshes)
    }

    @Test fun `camera save without a map keeps the previous session camera`() {
        val h = Harness()
        val cam = MapCameraState(1.0, 2.0, 3f, 4f, 5f)
        h.vm.update { it.copy(camera = cam) }
        h.camera.saveCameraState()
        assertEquals(cam, h.vm.state.camera)
    }
}
