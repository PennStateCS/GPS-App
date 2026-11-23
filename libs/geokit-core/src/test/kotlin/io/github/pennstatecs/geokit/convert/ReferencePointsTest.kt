package io.github.pennstatecs.geokit.convert

import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.geoid.Geoid18Grid
import io.github.pennstatecs.geokit.projection.Utm
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Datum, ECEF and UTM reference checks (values captured 2026-11-19; moved here from the app's
 * CoordinateSystemReferenceTest, which needed no app types for them):
 *  - PROJ 9.8.1 (pyproj 3.8.0) "ITRF2014 to NAD83(2011) (1)" at epoch 2026.886;
 *  - NGS NCAT for GRS80 XYZ and UTM 18N.
 */
class ReferencePointsTest {

    @Test fun `ITRF2014 to NAD83(2011) matches PROJ at epoch 2026_886`() {
        val out = Itrf2014ToNad83.forward(GeodeticPosition(41.3477, -76.0226, 390.0), 2026.886)
        // 1e-8 degrees ≈ 1.1 mm.
        assertEquals(41.3476902755, out.latDeg, 2e-9)
        assertEquals(-76.0225917754, out.lonDeg, 2e-9)
        assertEquals(391.2061, out.heightM, 0.001)
    }

    @Test fun `inverse transformation round-trips to sub-millimetre`() {
        val src = GeodeticPosition(41.3477, -76.0226, 390.0)
        val back = Itrf2014ToNad83.inverse(Itrf2014ToNad83.forward(src, 2026.886), 2026.886)
        assertEquals(src.latDeg, back.latDeg, 1e-10)
        assertEquals(src.lonDeg, back.lonDeg, 1e-10)
        assertEquals(src.heightM, back.heightM, 0.0005)
    }

    @Test fun `decimal year`() {
        assertEquals(2026.0, Itrf2014ToNad83.decimalYear(java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()), 1e-9)
        assertEquals(2026.886, Itrf2014ToNad83.decimalYear(java.time.Instant.parse("2026-11-20T04:00:00Z").toEpochMilli()), 0.001)
    }

    @Test fun `GRS80 geocentric matches NCAT XYZ`() {
        val xyz = Ellipsoid.GRS80.toEcef(GeodeticPosition(41.3477, -76.0226, 390.0))
        assertEquals(1_158_291.825, xyz.xM, 0.001)
        assertEquals(-4_653_473.583, xyz.yM, 0.001)
        assertEquals(4_191_746.616, xyz.zM, 0.001)
    }

    @Test fun `UTM 18N matches NCAT`() {
        val u = Utm.forward(41.3477, -76.0226)
        assertEquals(18, u.zone)
        assertEquals(414_450.726, u.eastingM, 0.002)
        assertEquals(4_577_860.600, u.northingM, 0.002)
    }

    @Test fun `GEOID18 names the vertical datum by region`() {
        assertEquals("NAVD88", Geoid18Grid.verticalDatumName(41.3, -76.0))
        assertEquals("PRVD02", Geoid18Grid.verticalDatumName(18.4655, -66.1057))
        assertEquals("VIVD09", Geoid18Grid.verticalDatumName(18.3419, -64.9307))
    }
}
