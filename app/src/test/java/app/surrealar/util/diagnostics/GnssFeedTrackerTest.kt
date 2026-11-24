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

    @Test fun `latest gives fix quality and age without position, null before a fix`() {
        assertEquals(null, t.latest(0))
        t.onSource("EXTERNAL_TCP", 0)
        t.onFix(fix(RtkStatus.FIX, 1.5, hAcc = 0.012), 1_000)
        assertEquals(GnssFeedTracker.Latest(RtkStatus.FIX, 2_500, 0.012, 0.03, 1.5), t.latest(3_500))
        t.onSource("INTERNAL", 4_000)
        assertEquals("a source switch forgets the old fix", null, t.latest(4_000))
    }

    @Test fun `transitions, correction thresholds and recovery, once each`() {
        t.onSource("EXTERNAL_TCP", 0)
        var now = 0L
        // 30 fixes at 5 Hz = 6 s, longer than the RTK hold time.
        fun feed(rtk: RtkStatus, age: Double?, n: Int = 30) = repeat(n) { now += 200; t.onFix(fix(rtk, age), now) }
        feed(RtkStatus.FLOAT, 1.0); feed(RtkStatus.FIX, 1.0); feed(RtkStatus.FIX, 6.0); feed(RtkStatus.FIX, 12.0)
        feed(RtkStatus.FLOAT, 31.0); feed(RtkStatus.FLOAT, 12.0); feed(RtkStatus.FIX, 1.0)
        assertEquals(listOf(
            "I RTK_CHANGE from=unknown to=FLOAT hAccM=0.014 sats=18 hdop=0.7 corrAgeBucket=<5s (RTK status start → FLOAT)",
            "I RTK_CHANGE from=FLOAT to=FIX hAccM=0.014 sats=18 hdop=0.7 corrAgeBucket=<5s (RTK status FLOAT → FIX)",
            "W CORRECTIONS_STATE state=STALE ageS=6.0 thresholdS=5 via=nmea (Corrections age over threshold)",
            "W Corrections age over threshold thresholdS=10 ageS=12.0",
            "W Corrections age over threshold thresholdS=30 ageS=31.0",
            "W RTK_CHANGE from=FIX to=FLOAT hAccM=0.014 sats=18 hdop=0.7 corrAgeBucket=>30s (RTK status FIX → FLOAT)",
            "I CORRECTIONS_STATE state=OK ageS=1.0 via=nmea (Corrections age recovered)",
            "I RTK_CHANGE from=FLOAT to=FIX hAccM=0.014 sats=18 hdop=0.7 corrAgeBucket=<5s (RTK status FLOAT → FIX)",
        ), lines)
        // Every coded line parses; the RTK lines carry from/to.
        val parsed = lines.mapNotNull { DiagEvents.parse(it.substringAfter(' ')) }
        assertEquals(6, parsed.size)
        assertEquals(mapOf("from" to "FIX", "to" to "FLOAT"), parsed[3].second.filterKeys { it == "from" || it == "to" })
        val s = t.stats(now)
        assertEquals(1, s.rtkDrops); assertEquals(31.0, s.longestCorrectionAgeS!!, 1e-9); assertEquals(210L, s.fixes)
        assertEquals("from the first fix to now", now - 200, s.timeInStatusMs.values.sum())
    }

    @Test fun `source switches are logged and the heartbeat has no coordinates`() {
        t.onSource("INTERNAL", 0); t.onSource("EXTERNAL_TCP", 10)
        t.onSky(18, 27); t.onNmea(59_800)
        repeat(300) { t.onFix(fix(RtkStatus.FIX, 1.2), 1_000L + it * 200) }
        val hb = t.heartbeat(60_000)
        assertTrue(lines.contains("I GNSS source INTERNAL → EXTERNAL_TCP"))
        assertEquals("GNSS heartbeat source=EXTERNAL_TCP rtk=FIX sats=18/27 hdop=0.7 hAcc=<2cm corrAgeS=1.2 fixesPerS=5.0 lastNmeaAgeS=0.2", hb)
        listOf("41.23", "76.12", "312.3", "345.6").forEach { assertFalse(it, (lines + hb).any { l -> l.contains(it) }) }
    }

    @Test fun `hAcc buckets`() {
        assertEquals(listOf("<2cm", "<10cm", "<1m", ">=10m", "unknown"),
            listOf(0.01, 0.07, 0.5, 12.0, null).map(GnssFeedTracker::hAccBucket))
    }

    @Test fun `RTK flapping shorter than the hold time is not logged`() {
        t.onSource("EXTERNAL_TCP", 0)
        var now = 0L
        repeat(30) { now += 200; t.onFix(fix(RtkStatus.FIX, 1.0), now) }
        // FIX/FLOAT alternating every 1 s for a minute: never holds 5 s.
        repeat(60) { i -> repeat(5) { now += 200; t.onFix(fix(if (i % 2 == 0) RtkStatus.FLOAT else RtkStatus.FIX, 1.0), now) } }
        assertEquals(listOf("I RTK_CHANGE from=unknown to=FIX hAccM=0.014 sats=18 hdop=0.7 corrAgeBucket=<5s (RTK status start → FIX)"), lines)
        assertTrue("drops are still counted for stats", t.stats(now).rtkDrops >= 30)
    }

    @Test fun `RTK and corrections lines carry the connection id of an external source`() {
        val conn = GnssFeedTracker(connId = { "c7" }) { level, m -> lines += "$level $m" }
        conn.onSource("EXTERNAL_TCP", 0)
        var now = 0L
        repeat(30) { now += 200; conn.onFix(fix(RtkStatus.FIX, 6.0), now) }
        assertTrue(lines.all { it.contains("conn=c7") })
        assertEquals(2, lines.size)
    }

    @Test fun `feed state LIVE, NO_FIX, STALE logged once each after the hold, flicker ignored`() {
        t.onSource("EXTERNAL_TCP", 0)
        var now = 0L
        fun tick(ms: Long, fixes: Boolean, valid: Boolean = true, nmea: Boolean = fixes) {
            val end = now + ms
            while (now < end) {
                now += 200
                if (nmea) t.onNmea(now)
                if (fixes) t.onFix(if (valid) fix(RtkStatus.FIX, 1.0) else fix(RtkStatus.NONE, null).copy(latDeg = 0.0, lonDeg = 0.0), now)
                if (now % 1_000 == 0L) t.checkFeed(now)
            }
        }
        t.checkFeed(100)                  // nothing received yet: no line
        tick(10_000, fixes = true)        // LIVE
        tick(10_000, fixes = true, valid = false)   // NMEA + position-less fixes: NO_FIX
        tick(1_000, fixes = true)         // a 1 s blip of valid fixes: shorter than the hold, not logged
        tick(10_000, fixes = true, valid = false)
        tick(20_000, fixes = false)       // nothing: STALE
        tick(10_000, fixes = true)        // LIVE again
        val feed = lines.filter { it.contains("GNSS_FEED") }
        assertEquals(listOf("LIVE", "NO_FIX", "STALE", "LIVE"), feed.map { DiagEvents.parse(it.substringAfter(' '))!!.second["state"] })
        assertTrue(feed[0].startsWith("I GNSS_FEED state=LIVE source=EXTERNAL_TCP"))
        assertTrue(feed[1].startsWith("W GNSS_FEED state=NO_FIX ageS="))
        assertTrue(feed[2].startsWith("W GNSS_FEED state=STALE ageS="))
        listOf("41.23", "76.12", "312.3").forEach { s -> assertFalse(s, lines.any { it.contains(s) }) }
    }

    @Test fun `heartbeat only on a bucket change or every 10 minutes, never without fixes`() {
        assertEquals(null, t.heartbeatIfDue(60_000))
        t.onSource("EXTERNAL_TCP", 0)
        assertEquals("no fix yet", null, t.heartbeatIfDue(60_000))
        t.onFix(fix(RtkStatus.FIX, 1.0), 61_000)
        assertTrue(t.heartbeatIfDue(62_000)!!.startsWith("GNSS heartbeat source=EXTERNAL_TCP rtk=FIX"))
        t.onFix(fix(RtkStatus.FIX, 1.0), 121_000)
        assertEquals("unchanged", null, t.heartbeatIfDue(122_000))
        t.onFix(fix(RtkStatus.FIX, 1.0, hAcc = 0.5), 181_000)
        assertTrue("hAcc bucket changed", t.heartbeatIfDue(182_000)!!.contains("hAcc=<1m"))
        t.onFix(fix(RtkStatus.FIX, 1.0, hAcc = 0.5), 781_000)
        assertTrue("10 min heartbeat", t.heartbeatIfDue(782_000) != null)
        assertEquals("fix older than 60 s", null, t.heartbeatIfDue(900_000))
    }
}
