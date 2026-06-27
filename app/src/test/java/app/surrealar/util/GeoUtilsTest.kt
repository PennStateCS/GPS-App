package app.surrealar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsTest {

    // ── haversineM ────────────────────────────────────────────────────────────

    @Test
    fun `haversineM between same point is zero`() {
        assertEquals(0.0, haversineM(40.0, -74.0, 40.0, -74.0), 0.001)
    }

    @Test
    fun `haversineM between New York and Los Angeles is approximately correct`() {
        // ~3,940 km between NYC and LA; tolerance ±20 km covers WGS84 sphere approximation.
        val distM = haversineM(40.7128, -74.0060, 34.0522, -118.2437)
        assertTrue("Expected ~3940 km, got ${distM / 1000} km", distM in 3_900_000.0..3_980_000.0)
    }

    @Test
    fun `haversineM returns meters for a short known distance`() {
        // One degree of latitude ≈ 111,195 m at the equator
        val distM = haversineM(0.0, 0.0, 1.0, 0.0)
        assertTrue("Expected ~111195 m, got $distM", distM in 111_000.0..111_400.0)
    }

    // ── bearingToCompass ──────────────────────────────────────────────────────

    @Test fun `bearingToCompass 0 is N`()   { assertEquals("N",  bearingToCompass(0.0))   }
    @Test fun `bearingToCompass 45 is NE`() { assertEquals("NE", bearingToCompass(45.0))  }
    @Test fun `bearingToCompass 90 is E`()  { assertEquals("E",  bearingToCompass(90.0))  }
    @Test fun `bearingToCompass 135 is SE`(){ assertEquals("SE", bearingToCompass(135.0)) }
    @Test fun `bearingToCompass 180 is S`() { assertEquals("S",  bearingToCompass(180.0)) }
    @Test fun `bearingToCompass 225 is SW`(){ assertEquals("SW", bearingToCompass(225.0)) }
    @Test fun `bearingToCompass 270 is W`() { assertEquals("W",  bearingToCompass(270.0)) }
    @Test fun `bearingToCompass 315 is NW`(){ assertEquals("NW", bearingToCompass(315.0)) }

    @Test fun `bearingToCompass 360 wraps to N`() { assertEquals("N", bearingToCompass(360.0)) }
}
