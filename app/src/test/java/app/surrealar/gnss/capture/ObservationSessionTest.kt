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
}

