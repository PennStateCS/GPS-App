package app.surrealar.gnss.accuracy

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Tests for [AccuracyEstimator] + [UereTable]. These feed the survey-grade accuracy shown to users
 * and the gates for capture acceptance and AR anchor creation, so silent regressions here are
 * survey-integrity bugs.
 */
class AccuracyEstimatorTest {

    private val tol = 1e-9
    /** RMS → 68 % radius: 1.515 / √2 ≈ 1.071 (HDOP × UERE is already a horizontal RMS). */
    private val R68 = 1.515 / Math.sqrt(2.0)

    private fun fix(
        provider: Provider = Provider.INTERNAL,
        rtkStatus: RtkStatus = RtkStatus.SINGLE,
        hAccM: Double? = null,
        vAccM: Double? = null,
        hDop: Double? = null,
        vDop: Double? = null,
    ) = Fix(
        provider = provider, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.UNKNOWN,
        latDeg = 0.0, lonDeg = 0.0, altEllipsoidalM = null, altMslM = null, geoidSeparationM = null,
        hDop = hDop, vDop = vDop, pDop = null, hAccM = hAccM, vAccM = vAccM,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = rtkStatus, satsUsed = 10, satsVisible = null, diffAgeS = null,
        speedMps = null, courseDeg = null,
    )

    // ── GST-reported accuracy is preferred over DOP×UERE ──────────────────────────────────────

    @Test fun `receiver-reported GST is used directly and ignores DOP`() {
        val r = AccuracyEstimator.estimate1SigmaMeters(
            fix(hAccM = 0.5, vAccM = 0.8, hDop = 99.0, vDop = 99.0), UereTable()
        )
        assertEquals(0.5, r.horizontalMeters!!, tol)
        assertEquals(0.8, r.verticalMeters!!, tol)
    }

    @Test fun `partial GST (only horizontal) keeps the present axis and nulls the other`() {
        val r = AccuracyEstimator.estimate1SigmaMeters(fix(hAccM = 0.42, hDop = 1.0), UereTable())
        assertEquals(0.42, r.horizontalMeters!!, tol)
        assertNull(r.verticalMeters)   // not back-filled from DOP when any GST axis is present
    }

    // ── Fallback: DOP × UERE ──────────────────────────────────────────────────────────────────

    @Test fun `internal single falls back to DOP times internal UERE`() {
        val r = AccuracyEstimator.estimate1SigmaMeters(
            fix(provider = Provider.INTERNAL, rtkStatus = RtkStatus.SINGLE, hDop = 1.5, vDop = 2.0),
            UereTable()
        )
        assertEquals(R68 * 1.5 * 4.0, r.horizontalMeters!!, tol)   // HDOP × UERE is a horizontal RMS → ×1.071 for the 68 % radius
        assertEquals(2.0 * 4.0, r.verticalMeters!!, tol)
    }

    @Test fun `no fix gives no accuracy estimate rather than a number`() {
        val r = AccuracyEstimator.estimate1SigmaMeters(
            fix(provider = Provider.RS2_EXTERNAL, rtkStatus = RtkStatus.NONE, hDop = 1.5, vDop = 2.0),
            UereTable()
        )
        assertNull(r.horizontalMeters)
        assertNull(r.verticalMeters)
    }

    @Test fun `first Emlid fix before any GST uses the standalone RS2 UERE`() {
        val r = AccuracyEstimator.estimate1SigmaMeters(
            fix(provider = Provider.RS2_EXTERNAL, rtkStatus = RtkStatus.SINGLE, hDop = 0.8), UereTable()
        )
        assertEquals(R68 * 0.8 * 2.5, r.horizontalMeters!!, tol)
    }

    @Test fun `DOP fallback examples - Emlid first fix and SM-X940`() {
        val emlid = AccuracyEstimator.estimate1SigmaMeters(
            fix(provider = Provider.RS2_EXTERNAL, rtkStatus = RtkStatus.SINGLE, hDop = 1.0), UereTable())
        assertEquals(2.68, emlid.horizontalMeters!!, 0.005)        // HDOP 1.0 × 2.5 m × 1.071
        val tablet = AccuracyEstimator.estimate1SigmaMeters(
            fix(provider = Provider.INTERNAL, rtkStatus = RtkStatus.SINGLE, hDop = 1.2), UereTable())
        assertEquals(5.14, tablet.horizontalMeters!!, 0.005)       // HDOP 1.2 × 4 m × 1.071
    }

    @Test fun `RS2 RTK fixed uses the tight rs2 UERE`() {
        val r = AccuracyEstimator.estimate1SigmaMeters(
            fix(provider = Provider.RS2_EXTERNAL, rtkStatus = RtkStatus.FIX, hDop = 0.8), UereTable()
        )
        assertEquals(R68 * 0.8 * 0.02, r.horizontalMeters!!, tol)   // rs2[FIX] = 0.02, ×1.071 → 68 % radius
        assertNull(r.verticalMeters)                          // no vDop → no vertical estimate
    }

    @Test fun `no GST and no DOP yields null estimates (not a crash)`() {
        val r = AccuracyEstimator.estimate1SigmaMeters(fix(), UereTable())
        assertNull(r.horizontalMeters)
        assertNull(r.verticalMeters)
    }

    @Test fun `zero DOP yields zero estimate (degenerate but non-crashing)`() {
        val r = AccuracyEstimator.estimate1SigmaMeters(fix(hDop = 0.0), UereTable())
        assertEquals(0.0, r.horizontalMeters!!, tol)
    }

    @Test fun `high DOP scales the estimate up`() {
        val r = AccuracyEstimator.estimate1SigmaMeters(
            fix(provider = Provider.INTERNAL, rtkStatus = RtkStatus.SINGLE, hDop = 50.0), UereTable()
        )
        assertEquals(R68 * 200.0, r.horizontalMeters!!, tol)
    }

    // ── UereTable mapping + fallbacks ─────────────────────────────────────────────────────────

    @Test fun `UereTable maps providers and modes`() {
        val t = UereTable()
        assertEquals(4.0, t.uere(Provider.INTERNAL, RtkStatus.SINGLE)!!, tol)
        assertEquals(0.02, t.uere(Provider.RS2_EXTERNAL, RtkStatus.FIX)!!, tol)
        assertEquals(0.25, t.uere(Provider.RS2_BT, RtkStatus.FLOAT)!!, tol)
        assertEquals(0.5, t.uere(Provider.RS2_TCP, RtkStatus.DGPS)!!, tol)
        // OTHER/MODEL share the internal table.
        assertEquals(2.0, t.uere(Provider.OTHER, RtkStatus.DGPS)!!, tol)
        assertEquals(2.0, t.uere(Provider.MODEL, RtkStatus.FIX)!!, tol)
    }

    @Test fun `UereTable falls back when a custom table lacks a mode`() {
        val t = UereTable(internal = emptyMap(), rs2 = emptyMap())
        assertEquals(4.0, t.uere(Provider.INTERNAL, RtkStatus.SINGLE)!!, tol)
        assertEquals(2.5, t.uere(Provider.RS2_EXTERNAL, RtkStatus.SINGLE)!!, tol)
        assertNull(t.uere(Provider.RS2_EXTERNAL, RtkStatus.NONE))
    }
}
