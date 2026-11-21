package app.surrealar.coords

import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.math.cos

class FeedDatumTest {

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
        val w = FeedDatum.nad83ToWgs84(nad)
        val back = Itrf2014ToNad83.forward(GeodeticPosition(w.latDeg, w.lonDeg, w.altEllipsoidalM!!),
            Itrf2014ToNad83.decimalYear(nad.timeUtc.toEpochMilli()))
        val mPerDegLat = 111_000.0
        assertEquals(0.0, (back.latDeg - nad.latDeg) * mPerDegLat, 1e-4)
        assertEquals(0.0, (back.lonDeg - nad.lonDeg) * mPerDegLat * cos(Math.toRadians(41.3)), 1e-4)
        assertEquals(360.0, back.heightM, 1e-4)
    }

    @Test fun `shift is about a metre and a half and MSL is kept with the separation recomputed`() {
        val nad = fix(41.3477, -76.0226, 360.0, 391.6)
        val w = FeedDatum.nad83ToWgs84(nad)
        val dn = (w.latDeg - nad.latDeg) * 111_000.0
        val de = (w.lonDeg - nad.lonDeg) * 111_000.0 * cos(Math.toRadians(41.3))
        val shift = kotlin.math.hypot(dn, de)
        assert(shift in 0.8..2.0) { "horizontal NAD83→WGS84 shift $shift m" }
        assertEquals(391.6, w.altMslM!!, 0.0)
        assertEquals(w.altEllipsoidalM!! - 391.6, w.geoidSeparationM!!, 1e-9)
    }

    @Test fun `stored NAD83 point plots at the same place as the WGS84 feed fix it came from`() {
        val nadFix = fix(41.3477, -76.0226, 360.0, 391.6)
        val feed = FeedDatum.nad83ToWgs84(nadFix)
        val stored = app.surrealar.domain.model.Coordinate(
            id = "p", name = "p", latitude = 41.3477, longitude = -76.0226, altitude = 360.0,
            timestamp = nadFix.timeUtc.toEpochMilli(), icon = "", color = 0, crsEpsg = StoredDatum.NAD83_2011_3D)
        val plotted = StoredDatum.toWgs84(stored)
        assertEquals(feed.latDeg, plotted.latitude, 1e-12)
        assertEquals(feed.lonDeg, plotted.longitude, 1e-12)
        assertEquals(StoredDatum.WGS84_3D, plotted.crsEpsg)
        val wgs = stored.copy(crsEpsg = StoredDatum.WGS84_3D)
        assert(StoredDatum.toWgs84(wgs) === wgs) { "WGS84 points are returned unchanged" }
    }

    @Test fun `missing ellipsoidal height stays missing`() {
        val w = FeedDatum.nad83ToWgs84(fix(41.3477, -76.0226, null, 391.6))
        assertNull(w.altEllipsoidalM)
    }
}
