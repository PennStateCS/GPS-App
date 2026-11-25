package app.surrealar.ui.capture

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** SYNTHETIC fixes: capture height gate (invalid vertical accuracy, recovery, zero separation). */
class CaptureHeightGateTest {

    private fun fix(vAcc: Double?, msl: Double? = 395.539, sep: Double? = -33.145) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.GNSS_PROVIDER,
        latDeg = 41.3, lonDeg = -76.0,
        altEllipsoidalM = if (msl != null && sep != null) msl + sep else null, altMslM = msl, geoidSeparationM = sep,
        hDop = 1.0, vDop = null, pDop = null, hAccM = 0.01, vAccM = vAcc,
        stdDevEastM = 0.01, stdDevNorthM = 0.01, stdDevUpM = vAcc,
        rtkStatus = RtkStatus.FIX, satsUsed = 20, satsVisible = 30, diffAgeS = 1.0, speedMps = null, courseDeg = null,
    )

    @Test
    fun `999 m epochs are excluded and averaging resumes when corrections return`() {
        val log = mutableListOf<String>()
        val g = CaptureHeightGate { log += it }
        val admitted = listOf(0.016, 0.016, 999.0, 999.0, 999.0, 0.018, 0.018).map { g.admit(fix(it)) }
        assertEquals(listOf(true, true, false, false, false, true, true), admitted)
        assertEquals(3, g.excludedEpochs)
        assertFalse(g.invalidNow)
        assertEquals("one line when interrupted, one when resumed", 2, log.size)
        assertTrue(log[0].contains("invalid"))
        assertTrue(log[1].contains("resumes"))
    }

    @Test
    fun `no reported vertical accuracy is not treated as invalid`() {
        val g = CaptureHeightGate()
        assertTrue(g.admit(fix(null)))
        assertEquals(0, g.excludedEpochs)
    }

    @Test
    fun `zero separation is admitted but logged once as uncertain`() {
        val log = mutableListOf<String>()
        val g = CaptureHeightGate { log += it }
        assertTrue(g.admit(fix(0.02, sep = 0.0)))
        assertTrue(g.admit(fix(0.02, sep = 0.0)))
        assertEquals(1, log.size)
        assertTrue(log[0].contains("uncertain"))
    }
}
