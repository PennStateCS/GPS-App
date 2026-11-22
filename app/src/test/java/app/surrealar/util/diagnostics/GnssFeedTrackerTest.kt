package app.surrealar.util.diagnostics

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GnssFeedTrackerTest {

    private val lines = mutableListOf<String>()
    private val t = GnssFeedTracker { level, m -> lines += "$level $m" }

    private fun fix(rtk: RtkStatus, diffAge: Double?, hAcc: Double = 0.014) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.2345678, lonDeg = -76.1234567, altEllipsoidalM = 312.345, altMslM = 345.678, geoidSeparationM = -33.0,
        hDop = 0.7, vDop = 1.2, pDop = null, hAccM = hAcc, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = rtk,
        satsUsed = 18, satsVisible = 27, diffAgeS = diffAge, speedMps = null, courseDeg = null,
    )

    @Test fun `transitions, correction thresholds and recovery, once each`() {
        t.onSource("EXTERNAL_TCP", 0)
        var now = 0L
        // 30 fixes at 5 Hz = 6 s, longer than the RTK hold time.
        fun feed(rtk: RtkStatus, age: Double?, n: Int = 30) = repeat(n) { now += 200; t.onFix(fix(rtk, age), now) }
        feed(RtkStatus.FLOAT, 1.0); feed(RtkStatus.FIX, 1.0); feed(RtkStatus.FIX, 6.0); feed(RtkStatus.FIX, 12.0)
        feed(RtkStatus.FLOAT, 31.0); feed(RtkStatus.FLOAT, 12.0); feed(RtkStatus.FIX, 1.0)
        assertEquals(listOf(
            "I RTK start → FLOAT sats=18 hdop=0.7 corrAge=<5s",
            "I RTK FLOAT → FIX sats=18 hdop=0.7 corrAge=<5s",
            "W Correction age over 5 s (6.0 s)",
            "W Correction age over 10 s (12.0 s)",
            "W Correction age over 30 s (31.0 s)",
            "W RTK FIX → FLOAT sats=18 hdop=0.7 corrAge=>30s",
            "I Correction age recovered (1.0 s)",
            "I RTK FLOAT → FIX sats=18 hdop=0.7 corrAge=<5s",
        ), lines)
        val s = t.stats(now)
        assertEquals(1, s.rtkDrops); assertEquals(31.0, s.longestCorrectionAgeS!!, 1e-9); assertEquals(210L, s.fixes)
        assertEquals("from the first fix to now", now - 200, s.timeInStatusMs.values.sum())
    }

    @Test fun `source switches are logged and the heartbeat has no coordinates`() {
        t.onSource("INTERNAL", 0); t.onSource("EXTERNAL_TCP", 10)
        t.onSky(18, 27); t.onNmea(59_800)
        repeat(300) { t.onFix(fix(RtkStatus.FIX, 1.2), 1_000L + it * 200) }
        val hb = t.heartbeat(60_000)
        assertTrue(lines.contains("I Source INTERNAL → EXTERNAL_TCP"))
        assertEquals("Heartbeat source=EXTERNAL_TCP rtk=FIX sats=18/27 hdop=0.7 hAcc=<2cm corrAge=1.2s fixes/s=5.0 lastNmea=0.2s", hb)
        listOf("41.23", "76.12", "312.3", "345.6").forEach { assertFalse(it, (lines + hb).any { l -> l.contains(it) }) }
    }

    @Test fun `hAcc buckets`() {
        assertEquals(listOf("<2cm", "<10cm", "<1m", ">=10m", "?"),
            listOf(0.01, 0.07, 0.5, 12.0, null).map(GnssFeedTracker::hAccBucket))
    }

    @Test fun `RTK flapping shorter than the hold time is not logged`() {
        t.onSource("EXTERNAL_TCP", 0)
        var now = 0L
        repeat(30) { now += 200; t.onFix(fix(RtkStatus.FIX, 1.0), now) }
        // FIX/FLOAT alternating every 1 s for a minute: never holds 5 s.
        repeat(60) { i -> repeat(5) { now += 200; t.onFix(fix(if (i % 2 == 0) RtkStatus.FLOAT else RtkStatus.FIX, 1.0), now) } }
        assertEquals(listOf("I RTK start → FIX sats=18 hdop=0.7 corrAge=<5s"), lines)
        assertTrue("drops are still counted for stats", t.stats(now).rtkDrops >= 30)
    }

    @Test fun `heartbeat only on a bucket change or every 10 minutes, never without fixes`() {
        assertEquals(null, t.heartbeatIfDue(60_000))
        t.onSource("EXTERNAL_TCP", 0)
        assertEquals("no fix yet", null, t.heartbeatIfDue(60_000))
        t.onFix(fix(RtkStatus.FIX, 1.0), 61_000)
        assertTrue(t.heartbeatIfDue(62_000)!!.startsWith("Heartbeat source=EXTERNAL_TCP rtk=FIX"))
        t.onFix(fix(RtkStatus.FIX, 1.0), 121_000)
        assertEquals("unchanged", null, t.heartbeatIfDue(122_000))
        t.onFix(fix(RtkStatus.FIX, 1.0, hAcc = 0.5), 181_000)
        assertTrue("hAcc bucket changed", t.heartbeatIfDue(182_000)!!.contains("hAcc=<1m"))
        t.onFix(fix(RtkStatus.FIX, 1.0, hAcc = 0.5), 781_000)
        assertTrue("10 min heartbeat", t.heartbeatIfDue(782_000) != null)
        assertEquals("fix older than 60 s", null, t.heartbeatIfDue(900_000))
    }
}
