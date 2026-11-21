package app.surrealar.ui.openinar

import io.github.pennstatecs.geokit.gnss.accuracy.AccuracyEstimator
import io.github.pennstatecs.geokit.gnss.accuracy.UereTable
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import io.github.pennstatecs.geokit.gnss.receiver.AccuracyClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.sqrt

/**
 * The AR anchor gate uses the app-wide accuracy (AccuracyEstimator + UereTable by accuracy class)
 * and never anchors on a fix without a satellite solution.
 */
class ArGnssGateTest {

    private fun fix(rtk: RtkStatus, hDop: Double? = 1.0, hAccM: Double? = null,
                    provider: Provider = Provider.EXTERNAL_TCP, cls: AccuracyClass? = null) = Fix(
        provider = provider, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = 300.0, altMslM = null, geoidSeparationM = null,
        hDop = hDop, vDop = 1.5, pDop = null, hAccM = hAccM, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = rtk,
        satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = null, courseDeg = null, accuracyClass = cls,
    )

    /** The gate's former private table (HDOP × 0.02/0.1/0.5/3.0), kept here as the "before" reference. */
    private fun oldAccuracy(f: Fix): Double? = f.hAccM ?: f.hDop?.let { h ->
        when (f.rtkStatus) { RtkStatus.FIX -> h * 0.02; RtkStatus.FLOAT -> h * 0.1; RtkStatus.DGPS -> h * 0.5; else -> h * 3.0 }
    }
    private val r68 = 1.515 / sqrt(2.0)

    @Test fun `gate accuracy equals the toolbar's estimate for every status`() {
        val table = UereTable()
        for (rtk in listOf(RtkStatus.FIX, RtkStatus.FLOAT, RtkStatus.DGPS, RtkStatus.SINGLE)) {
            for (cls in AccuracyClass.entries) {
                val f = fix(rtk, hDop = 1.3, cls = cls)
                assertEquals("$rtk/$cls", AccuracyEstimator.estimate1SigmaMeters(f, table).horizontalMeters, ArGnssGate.accuracyM(f))
            }
        }
    }

    @Test fun `FLOAT without GST - before 0_10 m, now the survey UERE (0_27 m) the toolbar shows`() {
        val f = fix(RtkStatus.FLOAT, hDop = 1.0)
        assertEquals(0.10, oldAccuracy(f)!!, 1e-9)
        assertEquals(0.25 * r68, ArGnssGate.accuracyM(f)!!, 1e-9)
        assertTrue(ArGnssGate.allows(f))
    }

    @Test fun `no-solution fixes never pass, however small the HDOP`() {
        for (rtk in listOf(RtkStatus.NONE, RtkStatus.DEAD_RECKONING, RtkStatus.INVALID)) {
            val f = fix(rtk, hDop = 0.6)
            assertTrue("before: $rtk passed at ${oldAccuracy(f)} m", oldAccuracy(f)!! <= ArGnssGate.MAX_ACCURACY_M)
            assertFalse("now: $rtk never anchors", ArGnssGate.allows(f))
            assertFalse(ArGnssGate.allows(f.copy(hAccM = 0.01)))   // even with a (bogus) small accuracy
        }
    }

    @Test fun `receiver accuracy is used as-is and the 20 m limit still applies`() {
        assertTrue(ArGnssGate.allows(fix(RtkStatus.SINGLE, hAccM = 19.9)))
        assertFalse(ArGnssGate.allows(fix(RtkStatus.SINGLE, hAccM = 20.1)))
    }

    @Test fun `device-class SINGLE is judged with the device UERE`() {
        // HDOP 5: before 15 m (pass); now 5 × 4.0 m × 1.071 = 21.4 m (wait for a better fix).
        val f = fix(RtkStatus.SINGLE, hDop = 5.0, provider = Provider.INTERNAL)
        assertEquals(15.0, oldAccuracy(f)!!, 1e-9)
        assertEquals(5.0 * 4.0 * r68, ArGnssGate.accuracyM(f)!!, 1e-9)
        assertFalse(ArGnssGate.allows(f))
    }

    @Test fun `unknown accuracy (no GST, no HDOP) still defers to the ARCore gate`() {
        assertTrue(ArGnssGate.allows(fix(RtkStatus.FIX, hDop = null)))
    }
}
