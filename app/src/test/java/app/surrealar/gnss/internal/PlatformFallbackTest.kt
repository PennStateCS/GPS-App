package app.surrealar.gnss.internal

import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformFallbackTest {

    private fun loc(msl: Double? = null) = PlatformLocation(
        timeMs = 1_700_000_000_000L, latDeg = 41.25, lonDeg = -76.02,
        altEllipsoidalM = 150.0, altMslM = msl, hAccM = 3.5, vAccM = 0.0,
        speedMps = 0.4, bearingDeg = 90.0, satellitesExtra = 9,
    )

    @Test fun `falls back only after NMEA has been silent for the grace period`() {
        assertFalse(PlatformFallback.nmeaSilent(nowMs = 2_000, registeredAtMs = 0, lastNmeaAtMs = 0))
        assertTrue(PlatformFallback.nmeaSilent(nowMs = 3_500, registeredAtMs = 0, lastNmeaAtMs = 0))
        assertFalse(PlatformFallback.nmeaSilent(nowMs = 10_000, registeredAtMs = 0, lastNmeaAtMs = 9_000))
    }

    @Test fun `location maps to an internal single fix`() {
        val fix = PlatformFallback.toFix(loc(msl = 184.0), satsUsed = 12, satsVisible = 20)
        assertEquals(Provider.INTERNAL, fix.provider)
        assertEquals(RtkStatus.SINGLE, fix.rtkStatus)
        assertEquals(-76.02, fix.lonDeg, 0.0)
        assertEquals(12, fix.satsUsed)
        assertEquals(20, fix.satsVisible)
        assertEquals(-34.0, fix.geoidSeparationM!!, 1e-9)
        assertEquals(3.5, fix.hAccM!!, 0.0)
        assertNull("zero vertical accuracy means unknown", fix.vAccM)
    }

    @Test fun `without GnssStatus the legacy satellites extra is used`() {
        val fix = PlatformFallback.toFix(loc(), satsUsed = null, satsVisible = null)
        assertEquals(9, fix.satsUsed)
        assertNull(fix.geoidSeparationM)
    }

    @Test fun `NMEA fix without GST or geoid separation is filled from the same-epoch platform location`() {
        val nmea = PlatformFallback.toFix(loc(), null, null).copy(
            timeUtc = java.time.Instant.ofEpochMilli(1_700_000_000_400L),
            hAccM = null, vAccM = null, altEllipsoidalM = null, altMslM = 184.0, geoidSeparationM = null,
            latDeg = 41.2501, lonDeg = -76.0201,
        )
        val p = loc().copy(vAccM = 6.0)
        val out = PlatformFallback.enrich(nmea, p)
        assertEquals(3.5, out.hAccM!!, 0.0)
        assertEquals(6.0, out.vAccM!!, 0.0)
        assertEquals(150.0, out.altEllipsoidalM!!, 0.0)
        assertEquals(-34.0, out.geoidSeparationM!!, 1e-9)
        assertEquals("position is never touched", 41.2501, out.latDeg, 0.0)
    }

    @Test fun `enrichment never overwrites NMEA values and ignores other epochs`() {
        val nmea = PlatformFallback.toFix(loc(), null, null).copy(hAccM = 0.9)
        assertEquals(0.9, PlatformFallback.enrich(nmea, loc()).hAccM!!, 0.0)
        val otherEpoch = loc().copy(timeMs = loc().timeMs + 5_000)
        val bare = nmea.copy(hAccM = null)
        assertNull(PlatformFallback.enrich(bare, otherEpoch).hAccM)
    }

    @Test fun `multi-band status lists each satellite once per constellation`() {
        val sats = listOf(
            PlatformSatellite("GP", 5, 40f, 100f, 38f, usedInFix = true),   // L1
            PlatformSatellite("GP", 5, 40f, 100f, 31f, usedInFix = false),  // L5
            PlatformSatellite("GA", 5, 20f, 200f, 30f, usedInFix = false),
        )
        val msgs = PlatformFallback.toGsvMessages(sats).associateBy { it.constellation }
        assertEquals(1, msgs.getValue("GP").entries.size)
        assertEquals(38.0, msgs.getValue("GP").entries[0].snrDbHz!!, 0.0)
        assertTrue(msgs.getValue("GP").entries[0].usedInFix)
        assertEquals(1 to 2, PlatformFallback.counts(sats))
    }

    @Test fun `elevation and azimuth are rounded like GsvParser, not truncated`() {
        val e = PlatformFallback.toGsvMessages(
            listOf(PlatformSatellite("GP", 7, 12.7f, 359.6f, 40f, usedInFix = true))
        ).single().entries.single()
        assertEquals(13, e.elevationDeg)     // toInt() gave 12
        assertEquals(360, e.azimuthDeg)
        val low = PlatformFallback.toGsvMessages(
            listOf(PlatformSatellite("GP", 8, -0.6f, 10.2f, 30f, usedInFix = false))
        ).single().entries.single()
        assertEquals(-1, low.elevationDeg)   // toInt() gave 0 (above the horizon)
    }

    @Test fun `no satellite count anywhere stays unknown instead of becoming 0`() {
        val fix = PlatformFallback.toFix(loc().copy(satellitesExtra = null), satsUsed = null, satsVisible = null)
        assertNull(fix.satsUsed)
    }
}
