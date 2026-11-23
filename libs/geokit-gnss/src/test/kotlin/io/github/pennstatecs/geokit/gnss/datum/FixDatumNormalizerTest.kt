package io.github.pennstatecs.geokit.gnss.datum

import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant
import kotlin.math.cos

class FixDatumNormalizerTest {

    private fun fix(lat: Double, lon: Double, ellH: Double?, msl: Double?) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.parse("2026-11-19T12:00:00Z"),
        timestampSource = TimestampSource.UNKNOWN, latDeg = lat, lonDeg = lon,
        altEllipsoidalM = ellH, altMslM = msl, geoidSeparationM = null,
        hDop = null, vDop = null, pDop = null, hAccM = null, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = RtkStatus.FIX, satsUsed = 20, satsVisible = 30,
        diffAgeS = null, speedMps = null, courseDeg = null,
    )

    @Test fun `NAD83 receiver output round-trips back to the same NAD83 values on display`() {
        val nad = fix(41.3477, -76.0226, 360.0, 391.6)
        val w = FixDatumNormalizer.nad83ToWgs84(nad)
        val back = Itrf2014ToNad83.forward(GeodeticPosition(w.latDeg, w.lonDeg, w.altEllipsoidalM!!),
            Itrf2014ToNad83.decimalYear(nad.timeUtc.toEpochMilli()))
        val mPerDegLat = 111_000.0
        assertEquals(0.0, (back.latDeg - nad.latDeg) * mPerDegLat, 1e-4)
        assertEquals(0.0, (back.lonDeg - nad.lonDeg) * mPerDegLat * cos(Math.toRadians(41.3)), 1e-4)
        assertEquals(360.0, back.heightM, 1e-4)
    }

    @Test fun `shift is about a metre and a half and MSL is kept with the separation recomputed`() {
        val nad = fix(41.3477, -76.0226, 360.0, 391.6)
        val w = FixDatumNormalizer.nad83ToWgs84(nad)
        val dn = (w.latDeg - nad.latDeg) * 111_000.0
        val de = (w.lonDeg - nad.lonDeg) * 111_000.0 * cos(Math.toRadians(41.3))
        val shift = kotlin.math.hypot(dn, de)
        assert(shift in 0.8..2.0) { "horizontal NAD83→WGS84 shift $shift m" }
        assertEquals(391.6, w.altMslM!!, 0.0)
        assertEquals(w.altEllipsoidalM!! - 391.6, w.geoidSeparationM!!, 1e-9)
    }

    @Test fun `missing ellipsoidal height stays missing`() {
        val w = FixDatumNormalizer.nad83ToWgs84(fix(41.3477, -76.0226, null, 391.6))
        assertNull(w.altEllipsoidalM)
    }

    @Test fun `ITRF2014 output is returned unchanged`() {
        val f = fix(41.3477, -76.0226, 360.0, 391.6)
        assertSame(f, FixDatumNormalizer.toWgs84(f, FixDatum.ITRF2014))
    }

    @Test fun `NAD83 datum selects the NAD83 conversion`() {
        val f = fix(41.3477, -76.0226, 360.0, 391.6)
        assertEquals(FixDatumNormalizer.nad83ToWgs84(f), FixDatumNormalizer.toWgs84(f, FixDatum.NAD83_2011))
    }
}
