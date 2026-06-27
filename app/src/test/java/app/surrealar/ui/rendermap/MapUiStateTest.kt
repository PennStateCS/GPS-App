package app.surrealar.ui.rendermap

import com.google.android.gms.maps.GoogleMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapUiStateTest {

    @Test
    fun `defaults are sensible for a fresh map`() {
        val s = MapUiState()
        assertFalse(s.isMapToolsOpen)
        assertEquals(GoogleMap.MAP_TYPE_NORMAL, s.mapType)
        assertEquals(MapGridMode.OFF, s.gridMode)
        assertTrue(s.showCurrentLocation)
        assertNull(s.selectedCoordinateId)
        assertNull(s.camera)
    }

    @Test
    fun `viewModel retains map type, grid, my-location, tools, and selection`() {
        val vm = MapUiStateViewModel()
        vm.update { it.copy(mapType = GoogleMap.MAP_TYPE_HYBRID) }
        vm.update { it.copy(gridMode = MapGridMode.FINE) }
        vm.update { it.copy(showCurrentLocation = false) }
        vm.update { it.copy(isMapToolsOpen = true) }
        vm.update { it.copy(selectedCoordinateId = "coord-42") }
        vm.update { it.copy(camera = MapCameraState(40.0, -76.0, 18f, 30f, 0f)) }

        val s = vm.state
        assertEquals(GoogleMap.MAP_TYPE_HYBRID, s.mapType)
        assertEquals(MapGridMode.FINE, s.gridMode)
        assertFalse(s.showCurrentLocation)
        assertTrue(s.isMapToolsOpen)
        assertEquals("coord-42", s.selectedCoordinateId)
        assertEquals(18f, s.camera!!.zoom, 1e-6f)
        assertEquals(40.0, s.camera!!.lat, 1e-9)
    }

    @Test
    fun `selected coordinate id can be cleared`() {
        val vm = MapUiStateViewModel()
        vm.update { it.copy(selectedCoordinateId = "coord-42") }
        vm.update { it.copy(selectedCoordinateId = null) }
        assertNull(vm.state.selectedCoordinateId)
    }
}
