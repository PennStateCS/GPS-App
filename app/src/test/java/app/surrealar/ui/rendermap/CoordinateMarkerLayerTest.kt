package app.surrealar.ui.rendermap

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.repository.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** Visibility bookkeeping of the map's coordinate markers (no GoogleMap: it is final). */
class CoordinateMarkerLayerTest {

    @Test fun `a never-seen point defaults to visible and is remembered`() {
        val v = CoordinateVisibility()
        assertNull(v["a"])
        assertTrue(v.getOrInit("a"))
        assertEquals(true, v["a"])
        assertEquals(1, v.visibleCount)
    }

    @Test fun `getOrInit keeps an existing hidden choice`() {
        val v = CoordinateVisibility()
        v["a"] = false
        assertFalse(v.getOrInit("a"))
        assertFalse(v.isVisible("a"))
        assertEquals(0, v.visibleCount)
    }

    @Test fun `visible count includes every shown id, as the panel count always has`() {
        val v = CoordinateVisibility()
        v["a"] = true; v["b"] = false; v["c"] = true
        assertEquals(2, v.visibleCount)
        assertTrue(v.isVisible("a")); assertFalse(v.isVisible("b")); assertFalse(v.isVisible("unknown"))
    }

    private fun layer() = CoordinateMarkerLayer(
        map = { null },
        context = { null },
        scope = { CoroutineScope(Dispatchers.Unconfined) },
        modelRepository = Mockito.mock(ModelRepository::class.java),
    )

    private fun coord(id: String) = Coordinate(
        id = id, name = "P$id", latitude = 41.0, longitude = -76.0, altitude = 300.0,
        timestamp = 0L, icon = "ic_pin", color = 0,
    )

    @Test fun `without a map a point is skipped and not recorded`() {
        val l = layer()
        assertNull(l.upsert(coord("1")))
        assertTrue(l.coordinates.isEmpty())
        assertTrue(l.visibleMarkerPositions().isEmpty())
        // The visibility default is still initialised (same order as before the split).
        assertEquals(true, l.visibility["1"])
    }

    @Test fun `visibility choices survive view destruction`() {
        val l = layer()
        l.setVisible("1", false)
        l.setVisible("2", true)
        l.onDestroyView()
        assertEquals(false, l.visibility["1"])
        assertEquals(true, l.visibility["2"])
        assertTrue(l.coordinates.isEmpty())
    }

    @Test fun `bulk visibility only touches loaded coordinates`() {
        val l = layer()
        l.setVisible("stale", true)
        l.setAllVisible { false }   // nothing loaded → nothing changes
        assertEquals(true, l.visibility["stale"])
        assertTrue(l.removeMissing(setOf("x")).isEmpty())
    }
}
