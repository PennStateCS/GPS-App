package app.surrealar.gnss.capture

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.TimestampSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FixFreshnessGateTest {

    private fun fix(gnssMs: Long) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.ofEpochMilli(gnssMs),
        timestampSource = TimestampSource.NMEA_ZDA, latDeg = 41.0, lonDeg = -76.0,
        altEllipsoidalM = 100.0, altMslM = null, geoidSeparationM = null,
        hDop = null, vDop = null, pDop = null, hAccM = null, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = RtkStatus.FIX, satsUsed = 20, satsVisible = null, diffAgeS = null,
        speedMps = null, courseDeg = null,
    )

    @Test fun `tablet clock far off GNSS time still accepts a live stream`() {
        val skewMs = -90_000L // tablet clock 90 s behind GNSS
        var gnss = 1_000_000L
        var now = gnss + skewMs
        val gate = FixFreshnessGate(maxAgeMs = 3_000, nowMs = { now })
        repeat(10) {
            assertTrue(gate.isFresh(fix(gnss)))
            gnss += 1_000; now += 1_000
        }
    }

    @Test fun `a fix delivered late relative to the stream is rejected`() {
        var now = 0L
        val gate = FixFreshnessGate(maxAgeMs = 3_000, nowMs = { now })
        now = 10_000; assertTrue(gate.isFresh(fix(10_000)))
        now = 11_000; assertTrue(gate.isFresh(fix(11_000)))
        // Arrives at 20 s but stamped 12 s: buffered 8 s → stale.
        now = 20_000; assertFalse(gate.isFresh(fix(12_000)))
    }

    @Test fun `GNSS time jumping backwards is rejected`() {
        var now = 0L
        val gate = FixFreshnessGate(maxAgeMs = 3_000, nowMs = { now })
        now = 10_000; assertTrue(gate.isFresh(fix(10_000)))
        now = 10_500; assertFalse(gate.isFresh(fix(5_000)))
    }
}
