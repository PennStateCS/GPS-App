package app.surrealar.util.diagnostics

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ClockOffsetTest {
    private fun fix(src: TimestampSource) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.ofEpochMilli(1_000_000L), timestampSource = src,
        latDeg = 1.0, lonDeg = 2.0, altEllipsoidalM = null, altMslM = null, geoidSeparationM = null,
        hDop = null, vDop = null, pDop = null, hAccM = null, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = RtkStatus.FIX,
        satsUsed = null, satsVisible = null, diffAgeS = null, speedMps = null, courseDeg = null,
    )

    @Test fun `only GNSS-timed fixes give an offset`() {
        assertEquals(2_500L, ClockOffset.offsetMs(fix(TimestampSource.NMEA_ZDA), 1_002_500L))
        assertEquals(-300L, ClockOffset.offsetMs(fix(TimestampSource.GNSS_PROVIDER), 999_700L))
        assertNull(ClockOffset.offsetMs(fix(TimestampSource.DEVICE), 1_002_500L))
    }

    @Test fun `described with sign and source`() {
        assertEquals("Clock offset device−GNSS +2.5 s (NMEA_ZDA)", ClockOffset.describe(2_500L, TimestampSource.NMEA_ZDA))
        assertEquals("Clock offset device−GNSS -0.3 s (GNSS_PROVIDER)", ClockOffset.describe(-300L, TimestampSource.GNSS_PROVIDER))
    }

    @Test fun `change needs a move of a full second from the last logged offset`() {
        assertTrue("first offset always logs", ClockOffset.isChange(null, 400L))
        // Jitter across the x.5 s rounding edge is not a change.
        assertFalse(ClockOffset.isChange(400L, 600L))
        assertFalse(ClockOffset.isChange(400L, -500L))
        assertFalse(ClockOffset.isChange(1_400L, 1_600L))
        assertTrue(ClockOffset.isChange(400L, 1_400L))
        assertTrue(ClockOffset.isChange(400L, -700L))
    }
}
