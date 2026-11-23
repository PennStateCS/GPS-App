package io.github.pennstatecs.geokit.gnss.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FixSnapshot] is a plain value holder: every field round-trips and value semantics hold. */
class FixSnapshotTest {

    private fun snapshot() = FixSnapshot(
        timestampMillis = 1_700_000_000_000L, timestampSource = TimestampSource.DEVICE,
        lat = 41.25, lon = -75.88, altMsl = 180.0, geoidSeparation = -33.0, altEllipsoidal = 147.0,
        speedMps = 0.1, courseDeg = 90.0, satsUsed = 24, hdop = 0.6, vDop = 0.9, pDop = 1.1,
        satellitesInView = 31, horizontalAccuracyM = 0.014, verticalAccuracyM = 0.021,
        correctionAgeS = 1.0, correctionStationId = "0042", multipathIndex = 0.3, rtkStatus = "fix",
        stdLatM = 0.01, stdLonM = 0.011, stdAltM = 0.02,
    )

    @Test fun fields_roundTrip() {
        val s = snapshot()
        assertEquals(1_700_000_000_000L, s.timestampMillis)
        assertEquals(TimestampSource.DEVICE, s.timestampSource)
        assertEquals(41.25, s.lat!!, 0.0); assertEquals(-75.88, s.lon!!, 0.0)
        assertEquals(180.0, s.altMsl!!, 0.0); assertEquals(-33.0, s.geoidSeparation!!, 0.0)
        assertEquals(147.0, s.altEllipsoidal!!, 0.0)
        assertEquals(0.1, s.speedMps!!, 0.0); assertEquals(90.0, s.courseDeg!!, 0.0)
        assertEquals(24, s.satsUsed); assertEquals(31, s.satellitesInView)
        assertEquals(0.6, s.hdop!!, 0.0); assertEquals(0.9, s.vDop!!, 0.0); assertEquals(1.1, s.pDop!!, 0.0)
        assertEquals(0.014, s.horizontalAccuracyM!!, 0.0); assertEquals(0.021, s.verticalAccuracyM!!, 0.0)
        assertEquals(1.0, s.correctionAgeS!!, 0.0); assertEquals("0042", s.correctionStationId)
        assertEquals(0.3, s.multipathIndex!!, 0.0); assertEquals("fix", s.rtkStatus)
        assertEquals(0.01, s.stdLatM!!, 0.0); assertEquals(0.011, s.stdLonM!!, 0.0); assertEquals(0.02, s.stdAltM!!, 0.0)
    }

    @Test fun valueSemantics() {
        val a = snapshot()
        val b = snapshot()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val moved = a.copy(lat = null, lon = null)
        assertNotEquals(a, moved)
        assertNull(moved.lat)
        assertTrue(a.toString().startsWith("FixSnapshot("))
        val (ts, src) = a
        assertEquals(a.timestampMillis, ts)
        assertEquals(a.timestampSource, src)
    }
}
