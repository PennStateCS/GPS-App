package app.surrealar.coords

import app.surrealar.settings.model.ReceiverDatum
import io.github.pennstatecs.geokit.gnss.datum.FixDatum
import io.github.pennstatecs.geokit.gnss.datum.FixDatumNormalizer
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

/**
 * App side of the feed datum normalisation: the settings mapping and agreement with StoredDatum.
 * The conversion itself is tested in geokit-gnss (FixDatumNormalizerTest).
 */
class FeedDatumAppTest {

    private fun fix(lat: Double, lon: Double, ellH: Double?, msl: Double?) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.parse("2026-11-19T12:00:00Z"),
        timestampSource = TimestampSource.UNKNOWN, latDeg = lat, lonDeg = lon,
        altEllipsoidalM = ellH, altMslM = msl, geoidSeparationM = null,
        hDop = null, vDop = null, pDop = null, hAccM = null, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = RtkStatus.FIX, satsUsed = 20, satsVisible = 30,
        diffAgeS = null, speedMps = null, courseDeg = null,
    )

    @Test fun `receiver datum setting maps to the matching fix datum`() {
        assertEquals(FixDatum.ITRF2014, ReceiverDatum.ITRF2014.toFixDatum())
        assertEquals(FixDatum.NAD83_2011, ReceiverDatum.NAD83_2011.toFixDatum())
        assertEquals(ReceiverDatum.entries.size, FixDatum.entries.size)
    }

    @Test fun `ITRF2014 setting leaves the feed fix untouched`() {
        val f = fix(41.3477, -76.0226, 360.0, 391.6)
        assertSame(f, FixDatumNormalizer.toWgs84(f, ReceiverDatum.ITRF2014.toFixDatum()))
    }

    @Test fun `stored NAD83 point plots at the same place as the WGS84 feed fix it came from`() {
        val nadFix = fix(41.3477, -76.0226, 360.0, 391.6)
        val feed = FixDatumNormalizer.nad83ToWgs84(nadFix)
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
}
