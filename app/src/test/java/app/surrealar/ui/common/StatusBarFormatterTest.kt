package app.surrealar.ui.common

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [updateStatusBar] text: source label, internal fix classification, RTK labels, sats/DOP/accuracy
 * formatting, coordinate precision, NaN sanitising and stale annotation. Fresh fixes are stamped
 * far in the future (age clamps to 0) so no assertion depends on the wall clock; stale fixes use the
 * epoch and only match the "stale Ns" shape.
 */
class StatusBarFormatterTest {

    private val future: Instant = Instant.parse("2999-01-01T00:00:00Z")

    private fun fix(
        rtk: RtkStatus = RtkStatus.FIX,
        time: Instant = future,
        lat: Double = 41.2459149,
        lon: Double = -75.8813075,
        alt: Double? = 210.456,
        hAcc: Double? = 0.014,
        vAcc: Double? = 0.025,
        hDop: Double? = 0.7,
        pDop: Double? = 1.24,
        used: Int? = 18,
        vis: Int? = 30,
        diffAge: Double? = 1.8,
    ) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = time, timestampSource = TimestampSource.DEVICE,
        latDeg = lat, lonDeg = lon, altEllipsoidalM = alt, altMslM = null, geoidSeparationM = null,
        hDop = hDop, vDop = null, pDop = pDop, hAccM = hAcc, vAccM = vAcc,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = rtk,
        satsUsed = used, satsVisible = vis, diffAgeS = diffAge, speedMps = null, courseDeg = null,
    )

    @Test fun `external RTK fix shows all parts with 7-decimal coordinates`() {
        val s = updateStatusBar("rs2", fix(), null)
        assertEquals(
            "RS2+ • FIX • 18/30 sats • PDOP 1.2 / HDOP 0.7 • ±0.014/0.025 m • 41.2459149,-75.8813075 • Alt 210.46 m • Age 1.8s",
            s,
        )
    }

    @Test fun `external non-FIX uses 5-decimal coordinates`() {
        val s = updateStatusBar("tcp", fix(rtk = RtkStatus.FLOAT), null)
        assertTrue(s, s.contains(" • FLOAT • "))
        assertTrue(s, s.contains("41.24591,-75.88131"))
    }

    @Test fun `every RTK status has its label`() {
        val expected = mapOf(
            RtkStatus.FIX to "FIX", RtkStatus.FLOAT to "FLOAT", RtkStatus.DGPS to "DGPS",
            RtkStatus.SINGLE to "SINGLE", RtkStatus.NONE to "NONE", RtkStatus.INVALID to "--",
            RtkStatus.DEAD_RECKONING to "DR",
        )
        for ((status, label) in expected) {
            val parts = updateStatusBar("external", fix(rtk = status), null).split(" • ")
            assertEquals(label, parts[1])
        }
    }

    @Test fun `DOP part handles only-PDOP only-HDOP and neither`() {
        assertTrue(updateStatusBar("x", fix(hDop = null), null).contains(" • PDOP 1.2 • "))
        assertTrue(updateStatusBar("x", fix(pDop = null), null).contains(" • HDOP 0.7 • "))
        val none = updateStatusBar("x", fix(pDop = null, hDop = null), null)
        assertFalse(none, none.contains("DOP"))
    }

    @Test fun `accuracy formatting picks decimals by magnitude and drops vertical when absent`() {
        assertTrue(updateStatusBar("x", fix(hAcc = 0.5, vAcc = 5.0), null).contains("±0.50/5.00 m"))
        assertTrue(updateStatusBar("x", fix(hAcc = 12.4, vAcc = null), null).contains(" • ±12 m • "))
        val noH = updateStatusBar("x", fix(hAcc = null, vAcc = 0.02), null)
        assertFalse(noH, noH.contains("±"))
    }

    @Test fun `NaN and infinite values are sanitised away`() {
        val s = updateStatusBar("x", fix(hAcc = Double.NaN, vAcc = Double.POSITIVE_INFINITY, alt = Double.NaN), null)
        assertFalse(s, s.contains("±"))
        assertFalse(s, s.contains("Alt"))
        assertFalse(s, s.contains("NaN"))
    }

    @Test fun `sats part needs positive used and visible counts`() {
        assertFalse(updateStatusBar("x", fix(used = 0), null).contains("sats"))
        assertFalse(updateStatusBar("x", fix(used = null), null).contains("sats"))
        assertFalse(updateStatusBar("x", fix(vis = null), null).contains("sats"))
        assertFalse(updateStatusBar("x", fix(vis = 0), null).contains("sats"))
    }

    @Test fun `negative or missing correction age is omitted`() {
        assertFalse(updateStatusBar("x", fix(diffAge = -1.0), null).contains("Age"))
        assertFalse(updateStatusBar("x", fix(diffAge = null), null).contains("Age"))
        assertTrue(updateStatusBar("x", fix(diffAge = 13.4), null).endsWith(" • Age 13s"))
    }

    @Test fun `stale external fix annotates the RTK status`() {
        val s = updateStatusBar("external", fix(time = Instant.EPOCH), null)
        assertTrue(s, Regex("^RS2\\+ • FIX \\(stale \\d+s\\) • ").containsMatchIn(s))
    }

    @Test fun `internal 3D fix shows type sats accuracy coords and altitude with vertical accuracy`() {
        val s = updateStatusBar("Internal GPS", fix(rtk = RtkStatus.FIX, hAcc = 3.2, vAcc = 4.5, alt = 180.0), null)
        assertEquals("Internal • 3D • 18/30 sats • ±3.20/4.50 m • 41.24591,-75.88131 • Alt 180.00 ±4.50 m", s)
    }

    @Test fun `internal fix without vertical accuracy shows plain altitude`() {
        val s = updateStatusBar("internal", fix(vAcc = null, alt = 99.999), null)
        assertTrue(s, s.endsWith(" • Alt 100.00 m"))
    }

    @Test fun `internal 2D and No Fix classification`() {
        val twoD = updateStatusBar("internal", fix(alt = null, hAcc = 5.0, vAcc = null), null)
        assertEquals("Internal • 2D • 18/30 sats • ±5.00 m • 41.24591,-75.88131", twoD)
        val noFix = updateStatusBar("INTERNAL", fix(alt = null, hAcc = null, used = null), null)
        assertEquals("Internal • No Fix • 41.24591,-75.88131", noFix)
    }

    @Test fun `internal stale fix is annotated not hidden and no DOP or age is shown`() {
        val s = updateStatusBar("internal", fix(time = Instant.EPOCH), null)
        assertTrue(s, Regex("^Internal • 3D \\(stale \\d+s\\) • ").containsMatchIn(s))
        assertFalse(s, s.contains("DOP"))
        assertFalse(s, s.contains("Age"))
    }
}
