package app.surrealar.gnss.capture

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.TimestampSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

/**
 * Unit tests for ObservationSession.
 *
 * The first group covers the [AveragingPolicy] quality ranking. The second group drives the
 * actual capture lifecycle deterministically using `kotlinx-coroutines-test`: wall-clock
 * dependence is sidestepped by setting minDurationSec/maxDurationSec to 0 (always reached) or
 * large (never reached) so the finish decision is driven purely by the sample count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservationSessionTest {

    @Test
    fun `RtkStatus quality ranking is used correctly in policy`() {
        val policy = AveragingPolicy(requiredMinStatus = app.surrealar.gnss.model.RtkStatus.FLOAT)

        // Verify the policy uses quality ranking
        assertTrue("FIX should meet FLOAT requirement",
            app.surrealar.gnss.model.RtkStatus.FIX.meetsOrExceeds(policy.requiredMinStatus))
        assertTrue("FLOAT should meet FLOAT requirement",
            app.surrealar.gnss.model.RtkStatus.FLOAT.meetsOrExceeds(policy.requiredMinStatus))
        assertFalse("DGPS should not meet FLOAT requirement",
            app.surrealar.gnss.model.RtkStatus.DGPS.meetsOrExceeds(policy.requiredMinStatus))
        assertFalse("SINGLE should not meet FLOAT requirement",
            app.surrealar.gnss.model.RtkStatus.SINGLE.meetsOrExceeds(policy.requiredMinStatus))
    }

    @Test
    fun `policy validates with correct quality thresholds`() {
        val strictPolicy = AveragingPolicy(requiredMinStatus = app.surrealar.gnss.model.RtkStatus.FIX)
        val lenientPolicy = AveragingPolicy(requiredMinStatus = app.surrealar.gnss.model.RtkStatus.SINGLE)

        // Strict policy only accepts FIX
        assertTrue(app.surrealar.gnss.model.RtkStatus.FIX.meetsOrExceeds(strictPolicy.requiredMinStatus))
        assertFalse(app.surrealar.gnss.model.RtkStatus.FLOAT.meetsOrExceeds(strictPolicy.requiredMinStatus))

        // Lenient policy accepts SINGLE and above
        assertTrue(app.surrealar.gnss.model.RtkStatus.FIX.meetsOrExceeds(lenientPolicy.requiredMinStatus))
        assertTrue(app.surrealar.gnss.model.RtkStatus.FLOAT.meetsOrExceeds(lenientPolicy.requiredMinStatus))
        assertTrue(app.surrealar.gnss.model.RtkStatus.DGPS.meetsOrExceeds(lenientPolicy.requiredMinStatus))
        assertTrue(app.surrealar.gnss.model.RtkStatus.SINGLE.meetsOrExceeds(lenientPolicy.requiredMinStatus))
        assertFalse(app.surrealar.gnss.model.RtkStatus.NONE.meetsOrExceeds(lenientPolicy.requiredMinStatus))
    }

    // ── Capture lifecycle (deterministic, via coroutines-test) ──────────────────

    private fun validFix(rtk: RtkStatus = RtkStatus.FIX, alt: Double? = 10.0) = Fix(
        provider = Provider.RS2_EXTERNAL, timeUtc = Instant.now(), timestampSource = TimestampSource.DEVICE,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = alt, altMslM = null, geoidSeparationM = null,
        hDop = 1.0, vDop = 1.0, pDop = 1.0, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = rtk, satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = 0.0, courseDeg = 0.0
    )

    private fun policy(minDur: Int, maxDur: Int, minSamples: Int) = AveragingPolicy(
        minDurationSec = minDur, maxDurationSec = maxDur, minSamples = minSamples,
        requiredMinStatus = RtkStatus.FIX, maxFixAgeSec = 30, maxDiffAgeSec = 30
    )

    @Test
    fun `both requirements met completes with requirementsMet`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        val session = ObservationSession(backgroundScope, fixes, policy(minDur = 0, maxDur = 100, minSamples = 2))
        session.start(); runCurrent()
        fixes.emit(validFix()); fixes.emit(validFix())
        runCurrent()
        val s = session.state.value
        assertTrue("expected Complete, was $s", s is ObservationSession.State.Complete)
        s as ObservationSession.State.Complete
        assertTrue(s.requirementsMet)
        assertEquals(2, s.result.samples)
    }

    @Test
    fun `min samples not met stays capturing`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        val session = ObservationSession(backgroundScope, fixes, policy(minDur = 0, maxDur = 100, minSamples = 5))
        session.start(); runCurrent()
        fixes.emit(validFix()); fixes.emit(validFix())
        runCurrent()
        val s = session.state.value
        assertTrue("expected Capturing, was $s", s is ObservationSession.State.Capturing)
        assertEquals(2, (s as ObservationSession.State.Capturing).samples)
    }

    @Test
    fun `min duration not met stays capturing`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        // Duration requirement is large and won't be met in a ms-long test, even though samples are.
        val session = ObservationSession(backgroundScope, fixes, policy(minDur = 100, maxDur = 200, minSamples = 1))
        session.start(); runCurrent()
        fixes.emit(validFix())
        runCurrent()
        assertTrue(session.state.value is ObservationSession.State.Capturing)
    }

    @Test
    fun `timeout completes with requirements not met`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        // maxDuration 0 forces a finish on the first accepted fix before minSamples (5) is reached.
        val session = ObservationSession(backgroundScope, fixes, policy(minDur = 0, maxDur = 0, minSamples = 5))
        session.start(); runCurrent()
        fixes.emit(validFix())
        runCurrent()
        val s = session.state.value
        assertTrue("expected Complete, was $s", s is ObservationSession.State.Complete)
        assertFalse((s as ObservationSession.State.Complete).requirementsMet)
    }

    @Test
    fun `fix below required status is ignored`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        val session = ObservationSession(backgroundScope, fixes, policy(minDur = 0, maxDur = 100, minSamples = 1))
        session.start(); runCurrent()
        fixes.emit(validFix(rtk = RtkStatus.NONE))   // below required FIX → rejected, no capture starts
        runCurrent()
        assertTrue("rejected fix must not start capture", session.state.value is ObservationSession.State.Idle)

        fixes.emit(validFix(rtk = RtkStatus.FIX))    // accepted → finishes (minSamples 1, minDur 0)
        runCurrent()
        assertTrue(session.state.value is ObservationSession.State.Complete)
    }

    @Test
    fun `dead-reckoned fixes never satisfy a capture requirement`() {
        for (req in listOf(RtkStatus.SINGLE, RtkStatus.DGPS, RtkStatus.FIX, RtkStatus.NONE)) {
            assertFalse("DR must not meet $req", RtkStatus.DEAD_RECKONING.meetsOrExceeds(req))
        }
        assertTrue(RtkStatus.SINGLE.meetsOrExceeds(RtkStatus.SINGLE))
        assertEquals("rank unchanged for status displays", 1, RtkStatus.DEAD_RECKONING.qualityRank())
    }

    @Test
    fun `single-or-better capture rejects quality 6 fixes`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        val p = policy(minDur = 0, maxDur = 100, minSamples = 1).copy(requiredMinStatus = RtkStatus.SINGLE)
        val session = ObservationSession(backgroundScope, fixes, p)
        session.start(); runCurrent()
        fixes.emit(validFix(rtk = RtkStatus.DEAD_RECKONING)); fixes.emit(validFix(rtk = RtkStatus.DEAD_RECKONING))
        runCurrent()
        assertTrue("DR fixes must not be averaged", session.state.value !is ObservationSession.State.Complete)
        fixes.emit(validFix(rtk = RtkStatus.SINGLE)); runCurrent()
        assertEquals(1, (session.state.value as ObservationSession.State.Complete).result.samples)
    }

    @Test
    fun `MSL height is the averaged ellipsoidal height minus the mean geoid separation`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        val session = ObservationSession(backgroundScope, fixes, policy(minDur = 0, maxDur = 100, minSamples = 2))
        session.start(); runCurrent()
        // Ellipsoidal 10.0 / 12.0 (mean 11.0); N −33.0 / −33.2 (mean −33.1) → MSL 44.1, not the last epoch's 45.2.
        fixes.emit(validFix(alt = 10.0).copy(geoidSeparationM = -33.0, altMslM = 43.0))
        fixes.emit(validFix(alt = 12.0).copy(geoidSeparationM = -33.2, altMslM = 45.2))
        runCurrent()
        val r = (session.state.value as ObservationSession.State.Complete).result
        assertEquals(11.0, r.altEllipsoidalM, 1e-3)
        assertEquals(-33.1, r.geoidSeparationM!!, 1e-9)
        assertEquals(44.1, r.altMslM!!, 1e-3)
        assertEquals("altitude − MSL must equal N", r.geoidSeparationM!!, r.altEllipsoidalM - r.altMslM!!, 1e-9)
    }

    @Test
    fun `geoid separation is derived from ellipsoidal minus MSL when N is not reported`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        val session = ObservationSession(backgroundScope, fixes, policy(minDur = 0, maxDur = 100, minSamples = 2))
        session.start(); runCurrent()
        fixes.emit(validFix(alt = 10.0).copy(altMslM = 43.0))   // N = −33.0
        fixes.emit(validFix(alt = 12.0).copy(altMslM = 45.0))   // N = −33.0
        runCurrent()
        val r = (session.state.value as ObservationSession.State.Complete).result
        assertEquals(-33.0, r.geoidSeparationM!!, 1e-9)
        assertEquals(44.0, r.altMslM!!, 1e-3)
    }

    @Test
    fun `no MSL or N reported leaves both unknown`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        val session = ObservationSession(backgroundScope, fixes, policy(minDur = 0, maxDur = 100, minSamples = 1))
        session.start(); runCurrent()
        fixes.emit(validFix())
        runCurrent()
        val r = (session.state.value as ObservationSession.State.Complete).result
        assertNull(r.altMslM)
        assertNull(r.geoidSeparationM)
    }

    @Test
    fun `result carries the last accepted fix time`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        val session = ObservationSession(backgroundScope, fixes, policy(minDur = 0, maxDur = 100, minSamples = 2))
        session.start(); runCurrent()
        val t2 = Instant.now()
        fixes.emit(validFix().copy(timeUtc = t2.minusMillis(1000)))
        fixes.emit(validFix().copy(timeUtc = t2, timestampSource = TimestampSource.NMEA_ZDA))
        runCurrent()
        val r = (session.state.value as ObservationSession.State.Complete).result
        assertEquals(t2, r.lastFixTimeUtc)
    }
}

