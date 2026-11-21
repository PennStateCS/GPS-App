package app.surrealar.geo.heading

import app.surrealar.geo.ellipsoid.Geodesic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compass labels and distance sanity checks (moved from the app's GeoUtilsTest when GeoUtils was removed). */
class CompassTest {

    // ── distance (was haversineM; now the WGS84 geodesic) ─────────────────────

    @Test
    fun `distance between the same point is zero`() {
        assertEquals(0.0, Geodesic.distanceM(40.0, -74.0, 40.0, -74.0), 0.001)
    }

    @Test
    fun `distance between New York and Los Angeles is approximately correct`() {
        // ~3,944 km on the WGS84 ellipsoid (the sphere gave ~3,936 km).
        val distM = Geodesic.distanceM(40.7128, -74.0060, 34.0522, -118.2437)
        assertTrue("Expected ~3940 km, got ${distM / 1000} km", distM in 3_900_000.0..3_980_000.0)
    }

    @Test
    fun `distance returns metres for a known one-degree arc`() {
        // One degree of latitude at the equator: 110,574 m on WGS84 (the 6,371 km sphere gave 111,195 m).
        val distM = Geodesic.distanceM(0.0, 0.0, 1.0, 0.0)
        assertTrue("Expected ~110574 m, got $distM", distM in 110_500.0..110_650.0)
    }

    // ── compass points (was bearingToCompass) ─────────────────────────────────

    @Test fun `bearingToCompass 0 is N`()   { assertEquals("N",  Compass.point8(0.0))   }
    @Test fun `bearingToCompass 45 is NE`() { assertEquals("NE", Compass.point8(45.0))  }
    @Test fun `bearingToCompass 90 is E`()  { assertEquals("E",  Compass.point8(90.0))  }
    @Test fun `bearingToCompass 135 is SE`(){ assertEquals("SE", Compass.point8(135.0)) }
    @Test fun `bearingToCompass 180 is S`() { assertEquals("S",  Compass.point8(180.0)) }
    @Test fun `bearingToCompass 225 is SW`(){ assertEquals("SW", Compass.point8(225.0)) }
    @Test fun `bearingToCompass 270 is W`() { assertEquals("W",  Compass.point8(270.0)) }
    @Test fun `bearingToCompass 315 is NW`(){ assertEquals("NW", Compass.point8(315.0)) }

    @Test fun `bearingToCompass 360 wraps to N`() { assertEquals("N", Compass.point8(360.0)) }

    @Test fun `bearingToCompass handles negative and large angles`() {
        assertEquals("W", Compass.point8(-90.0))     // used to crash (index −1)
        assertEquals("NW", Compass.point8(-30.0))    // used to read "N"
        assertEquals("E", Compass.point8(450.0))
        assertEquals("N", Compass.point8(Double.NaN))
    }
}
