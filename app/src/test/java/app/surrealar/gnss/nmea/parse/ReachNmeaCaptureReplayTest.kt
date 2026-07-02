package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.bus.adapters.NmeaFuser
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * Regression tests that replay real Emlid Reach RS4 NMEA captures end-to-end through the production
 * parser ([DefaultNmeaRegistry] + [NmeaFuser]). Fixtures live in `src/test/resources/nmea/`:
 *  - reach_nmea_capture.txt : standard capture (ETC is timestamp-only)
 *  - reach_nmea_withimu.txt : includes ETC orientation/IMU payloads
 *
 * These guard against regressions in: fix emission, RTK-Fixed detection, altitude/geoid, correction
 * age + base station id, GST accuracy parsing (never fabricated as 0.0), multi-constellation GSA/GSV,
 * ZDA date, and tolerance of unsupported/vendor sentences (VTG, EBP, ETC) — including that EBP never
 * overrides the rover fix.
 */
class ReachNmeaCaptureReplayTest {

    private data class Replay(val fixes: List<Fix>, val fuser: NmeaFuser)

    private fun replay(resource: String): Replay {
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("Missing test fixture: $resource")
        val fixes = mutableListOf<Fix>()
        val fuser = NmeaFuser(
            provider = Provider.RS2_EXTERNAL,
            registry = DefaultNmeaRegistry.create(verifyChecksum = true), // production checksum behavior
            onFix = { fixes.add(it) },
            onGsv = { }
        )
        stream.bufferedReader().useLines { seq -> seq.forEach { fuser.accept(it) } }
        return Replay(fixes, fuser)
    }

    @Test
    fun `capture file replays and emits many RTK-fixed fixes`() {
        val (fixes, fuser) = replay("/nmea/reach_nmea_capture.txt")

        assertTrue("should emit fixes from GGA/RMC", fixes.size > 100)
        // GGA fix quality 4 → RTK Fixed.
        assertEquals("first fix should be RTK Fixed (GGA quality 4)", RtkStatus.FIX, fixes.first().rtkStatus)
        assertTrue("most fixes should be RTK Fixed", fixes.count { it.rtkStatus == RtkStatus.FIX } > 100)

        val f = fixes.first()
        // Rover position (GGA), not the EBP base (~85 m south). 4118.4448800,N → 41.30741.
        assertEquals("rover latitude from GGA", 41.30741, f.latDeg, 1e-4)
        assertEquals("rover longitude from GGA (W → negative)", -76.01717, f.lonDeg, 1e-4)
        assertTrue("satellites used should be parsed", (f.satsUsed) > 0)
        assertNotNull("MSL altitude parsed", f.altMslM)
        assertEquals(395.539, f.altMslM!!, 0.01)
        assertNotNull("geoid separation parsed", f.geoidSeparationM)
        assertEquals(-33.145, f.geoidSeparationM!!, 0.01)
        // Ellipsoidal = MSL + geoid separation.
        assertEquals(395.539 - 33.145, f.altEllipsoidalM!!, 0.01)
        assertEquals("correction age from GGA", 1.8, f.diffAgeS!!, 0.01)
        assertEquals("base station id from GGA", "1116", f.correctionStationId)
        // ZDA/RMC date → 2026-06-30.
        assertEquals(2026, f.timeUtc.atZone(ZoneOffset.UTC).year)
        assertEquals(6, f.timeUtc.atZone(ZoneOffset.UTC).monthValue)

        // GST accuracy parsed from lat/lon std-devs (0.010) — present and NOT fabricated as 0.0.
        val hacc = fuser.timingStats.lastHAccM
        assertNotNull("GST horizontal accuracy must be parsed, not null", hacc)
        assertEquals("GST accuracy ~0.010 m", 0.010, hacc!!, 1e-3)
        assertTrue("accuracy must never be a fake 0.0", hacc > 0.0)

        // satsVisible (GSV) never below satsUsed (no cross-constellation double-count blowup).
        fixes.forEach { fx -> fx.satsVisible?.let { assertTrue("visible >= used", it >= fx.satsUsed) } }

        // Vendor/custom sentences seen but non-fatal.
        assertTrue("EBP seen", fuser.nmeaCustomStats.ebpSeen)
        assertTrue("ETC seen", fuser.nmeaCustomStats.etcSeen)
        assertTrue("GST counted", fuser.timingStats.gstCount > 0)
    }

    @Test
    fun `capture file ETC carries no orientation data`() {
        val (_, fuser) = replay("/nmea/reach_nmea_capture.txt")
        // This capture's ETC is timestamp-only.
        assertTrue("ETC seen", fuser.nmeaCustomStats.etcSeen)
        assertTrue("no IMU/orientation in the plain capture", !fuser.nmeaCustomStats.imuOrientationSeen)
    }

    @Test
    fun `withimu file replays, emits fixes, and flags IMU orientation data`() {
        val (fixes, fuser) = replay("/nmea/reach_nmea_withimu.txt")
        assertTrue("should emit fixes", fixes.size > 100)
        assertEquals(RtkStatus.FIX, fixes.first().rtkStatus)
        // ETC in this file carries orientation/IMU-style payloads.
        assertTrue("IMU/orientation should be detected", fuser.nmeaCustomStats.imuOrientationSeen)
        val etc = fuser.nmeaCustomStats.latestEtc
        assertNotNull(etc)
        assertTrue("latest ETC has raw data fields", etc!!.hasOrientationData)
        // Accuracy still parsed correctly alongside IMU data.
        assertNotNull("GST accuracy parsed", fuser.timingStats.lastHAccM)
        assertTrue(fuser.timingStats.lastHAccM!! > 0.0)
    }

    @Test
    fun `both files replay without throwing`() {
        // The acceptance criterion: replay end-to-end without errors.
        replay("/nmea/reach_nmea_capture.txt")
        replay("/nmea/reach_nmea_withimu.txt")
    }

    @Test
    fun `missing GST yields unknown (null) accuracy, never fabricated 0_0`() {
        val fixes = mutableListOf<Fix>()
        val fuser = NmeaFuser(
            provider = Provider.RS2_EXTERNAL,
            registry = DefaultNmeaRegistry.create(verifyChecksum = false),
            onFix = { fixes.add(it) },
            onGsv = { }
        )
        // GGA only, no GST at all.
        fuser.accept("\$GNGGA,172424.80,4118.4448800,N,07601.0300517,W,4,22,1.0,395.539,M,-33.145,M,1.8,1116")
        assertEquals(1, fixes.size)
        assertNull("no GST → hAccM must be null (unknown), not 0.0", fixes[0].hAccM)
    }

    @Test
    fun `RS2 capture replays as RTK Float with receiver GST accuracy and no IMU`() {
        val (fixes, fuser) = replay("/nmea/reach_nmea_rs2_capture.txt")
        assertTrue("should emit fixes", fixes.size > 100)

        // RS2 capture is RTK Float (GGA quality 5) — exercises the FLOAT mapping.
        assertEquals("GGA quality 5 → RTK Float", RtkStatus.FLOAT, fixes.first().rtkStatus)
        assertTrue("most fixes RTK Float", fixes.count { it.rtkStatus == RtkStatus.FLOAT } > 100)

        val f = fixes.first()
        // Rover position (4120.869,N / 07601.357,W) — NOT the EBP base (~41.3067).
        assertEquals(41.347823, f.latDeg, 1e-4)
        assertEquals(-76.022613, f.lonDeg, 1e-4)
        assertNotNull(f.altMslM); assertEquals(432.727, f.altMslM!!, 0.01)
        assertNotNull(f.geoidSeparationM); assertEquals(-33.134, f.geoidSeparationM!!, 0.01)
        assertEquals("1116", f.correctionStationId)
        assertEquals(2026, f.timeUtc.atZone(ZoneOffset.UTC).year)
        assertEquals(7, f.timeUtc.atZone(ZoneOffset.UTC).monthValue)

        // GST accuracy parsed from lat/lon std-devs (RS2 also leaves semi-major/minor empty).
        val hacc = fuser.timingStats.lastHAccM
        assertNotNull("GST accuracy must be parsed, not DOP fallback", hacc)
        assertEquals(0.010, hacc!!, 1e-3)
        assertEquals(NmeaFuser.AccuracySource.RECEIVER_GST, fuser.timingStats.lastAccuracySource)

        // RS2 has no IMU / GNETC.
        assertFalse("RS2 has no ETC", fuser.nmeaCustomStats.etcSeen)
        assertFalse("RS2 has no IMU orientation", fuser.nmeaCustomStats.imuOrientationSeen)
        // EBP is present (base station) but never becomes the rover fix.
        assertTrue("EBP seen", fuser.nmeaCustomStats.ebpSeen)
        // Per-constellation GSA/GSV counts are populated.
        assertTrue("GSA by constellation counted", fuser.timingStats.gsaCountByTalker.isNotEmpty())
        assertTrue("GSV by constellation counted", fuser.timingStats.gsvCountByTalker.isNotEmpty())
    }
}
