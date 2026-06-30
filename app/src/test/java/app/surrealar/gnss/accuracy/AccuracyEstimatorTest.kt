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

    private fun fix(
        provider: Provider = Provider.INTERNAL,
        rtkStatus: RtkStatus = RtkStatus.NONE,
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

    @Test fun `internal no-fix falls back to DOP times internal UERE`() {
        val r = AccuracyEstimator.estimate1SigmaMeters(
            fix(provider = Provider.INTERNAL, rtkStatus = RtkStatus.NONE, hDop = 1.5, vDop = 2.0),
            UereTable()
        )
        assertEquals(1.5 * 4.0, r.horizontalMeters!!, tol)   // internal[NONE] = 4.0
        assertEquals(2.0 * 4.0, r.verticalMeters!!, tol)
    }

    @Test fun `RS2 RTK fixed uses the tight rs2 UERE`() {
        val r = AccuracyEstimator.estimate1SigmaMeters(
            fix(provider = Provider.RS2_EXTERNAL, rtkStatus = RtkStatus.FIX, hDop = 0.8), UereTable()
        )
        assertEquals(0.8 * 0.02, r.horizontalMeters!!, tol)   // rs2[FIX] = 0.02
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
            fix(provider = Provider.INTERNAL, rtkStatus = RtkStatus.NONE, hDop = 50.0), UereTable()
        )
        assertEquals(200.0, r.horizontalMeters!!, tol)
    }

    // ── UereTable mapping + fallbacks ─────────────────────────────────────────────────────────

    @Test fun `UereTable maps providers and modes`() {
        val t = UereTable()
        assertEquals(4.0, t.uere(Provider.INTERNAL, RtkStatus.NONE), tol)
        assertEquals(0.02, t.uere(Provider.RS2_EXTERNAL, RtkStatus.FIX), tol)
        assertEquals(0.25, t.uere(Provider.RS2_BT, RtkStatus.FLOAT), tol)
        assertEquals(0.5, t.uere(Provider.RS2_TCP, RtkStatus.DGPS), tol)
        // OTHER/MODEL share the internal table.
        assertEquals(2.0, t.uere(Provider.OTHER, RtkStatus.DGPS), tol)
        assertEquals(2.0, t.uere(Provider.MODEL, RtkStatus.FIX), tol)
    }

    @Test fun `UereTable falls back when the mode is absent from the table`() {
        val t = UereTable()
        // SINGLE is not in either map → provider-specific fallback constants.
        assertEquals(4.0, t.uere(Provider.INTERNAL, RtkStatus.SINGLE), tol)   // INTERNAL_FALLBACK
        assertEquals(1.0, t.uere(Provider.RS2_EXTERNAL, RtkStatus.SINGLE), tol) // RS2_FALLBACK
    }
}
