package io.github.pennstatecs.geokit.ar.accuracy

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.tan

class ArGnssOffsetTest {

    private val lat = 41.3477
    private val lon = -76.0226
    // Inputs built on the WGS84 ellipsoid (the old 111,132 m/° sphere read a true 0.4013 m east offset as 0.4000 m).
    private val mPerDegLon = Ellipsoid.WGS84.metersPerDegLon(lat)
    private val mPerDegLat = Ellipsoid.WGS84.metersPerDegLat(lat)

    @Test fun `ARCore 0_4 m east of the fix reads 0_4 m at 90 degrees`() {
        val c = ArGnssOffset.compute(lat, lon + 0.4 / mPerDegLon, 390.0, lat, lon, 390.6, antennaAboveCameraM = 0.6)!!
        assertEquals(0.4, c.horizontalM, 1e-3)
        assertEquals(0.4, c.eastM, 1e-3)
        assertEquals(0.0, c.northM, 1e-6)
        assertEquals(90.0, c.bearingDeg, 0.1)
        assertEquals(0.0, c.verticalM!!, 1e-9)          // 390.6 antenna − 0.6 offset = 390.0 camera
    }

    @Test fun `ARCore north of the fix reads north`() {
        val c = ArGnssOffset.compute(lat + 1.5 / mPerDegLat, lon, null, lat, lon, null, 0.0)!!
        assertEquals(1.5, c.northM, 1e-3)
        assertEquals(1.5, c.horizontalM, 1e-3)
        assertEquals(0.0, c.bearingDeg, 0.1)
    }

    @Test fun `vertical is ARCore camera height minus GNSS camera height`() {
        val c = ArGnssOffset.compute(lat, lon, 392.0, lat, lon, 391.0, antennaAboveCameraM = 1.8)!!
        assertEquals(392.0 - (391.0 - 1.8), c.verticalM!!, 1e-12)
    }

    @Test fun `missing or non-finite heights leave vertical empty`() {
        assertNull(ArGnssOffset.compute(lat, lon, null, lat, lon, 390.0, 0.0)!!.verticalM)
        assertNull(ArGnssOffset.compute(lat, lon, 390.0, lat, lon, Double.NaN, 0.0)!!.verticalM)
    }

    @Test fun `non-finite positions give no comparison`() {
        assertNull(ArGnssOffset.compute(Double.NaN, lon, null, lat, lon, null, 0.0))
        assertNull(ArGnssOffset.compute(lat, lon, null, lat, Double.POSITIVE_INFINITY, null, 0.0))
    }

    @Test fun `expected error is horizontal accuracy plus the heading lever`() {
        assertEquals(1.0 + 20.0 * tan(Math.toRadians(5.0)), ArPlacementError.expectedErrorM(1.0, 5.0, 20.0)!!, 1e-9)
        assertEquals(0.8 + 20.0 * tan(Math.toRadians(3.0)), ArPlacementError.expectedErrorM(0.8, 3.0, 20.0)!!, 1e-9)
        assertNull(ArPlacementError.expectedErrorM(Double.NaN, 5.0, 20.0))
        assertNull(ArPlacementError.expectedErrorM(1.0, Double.NaN, 20.0))
        assertNull(ArPlacementError.expectedErrorM(1.0, 5.0, Double.NaN))
    }
}
