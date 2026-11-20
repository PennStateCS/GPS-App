package app.surrealar.ui.home

import app.surrealar.gnss.lastknown.LastKnownPosition
import app.surrealar.gnss.lastknown.LastKnownPositionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeInitialCameraTest {

    private val last = LastKnownPosition(41.3477, -76.0225, 1_000L)
    private val points = listOf(41.30 to -76.10, 41.31 to -76.11)

    @Test fun `live fix wins and shows no waiting chip`() {
        val c = HomeInitialCamera.choose(41.0 to -76.0, last, points)
        assertEquals(HomeInitialCamera.Live(41.0, -76.0), c)
        assertNull(c.waitingText)
    }

    @Test fun `without a live fix the last GNSS position is used`() {
        val c = HomeInitialCamera.choose(null, last, points)
        assertEquals(HomeInitialCamera.LastKnown(41.3477, -76.0225), c)
        assertEquals("Last known position · waiting for GNSS", c.waitingText)
    }

    @Test fun `first launch frames saved points`() {
        assertEquals(HomeInitialCamera.SavedPoints(points), HomeInitialCamera.choose(null, null, points))
    }

    @Test fun `nothing known falls back to the overview, never a specific site`() {
        assertEquals(HomeInitialCamera.Overview, HomeInitialCamera.choose(null, null, emptyList()))
    }

    @Test fun `invalid and null-island inputs are skipped`() {
        val c = HomeInitialCamera.choose(0.0 to 0.0, LastKnownPosition(200.0, 0.0, 0L), listOf(0.0 to 0.0))
        assertEquals(HomeInitialCamera.Overview, c)
    }

    @Test fun `last position is saved on first fix, after moving 10 m, or every minute`() {
        val a = LastKnownPosition(41.0, -76.0, 0L)
        assertTrue(LastKnownPositionStore.shouldPersist(null, a))
        assertFalse("1 m later, 5 s later", LastKnownPositionStore.shouldPersist(a, LastKnownPosition(41.000009, -76.0, 5_000L)))
        assertTrue("~11 m north", LastKnownPositionStore.shouldPersist(a, LastKnownPosition(41.0001, -76.0, 5_000L)))
        assertTrue("stationary, 60 s later", LastKnownPositionStore.shouldPersist(a, LastKnownPosition(41.0, -76.0, 60_000L)))
    }
}
