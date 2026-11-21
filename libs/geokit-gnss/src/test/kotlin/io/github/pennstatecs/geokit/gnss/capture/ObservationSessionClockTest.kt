package io.github.pennstatecs.geokit.gnss.capture

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Capture durations are judged on a monotonic clock: a tablet wall-clock correction mid-capture
 * (network time sync, manual change) must neither complete a capture early nor stop it timing out.
 * The monotonic clock is the test scheduler's virtual time; the wall clock is stepped by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservationSessionClockTest {

    private val wallBase = Instant.parse("2026-11-21T15:00:00Z")
    private var wallStepMs = 0L

    private fun fix() = Fix(
        provider = Provider.INTERNAL, timeUtc = Instant.now(), timestampSource = TimestampSource.DEVICE,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = 300.0, altMslM = null, geoidSeparationM = null,
        hDop = 1.0, vDop = 1.0, pDop = 1.0, hAccM = 3.0, vAccM = 5.0,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = RtkStatus.SINGLE, satsUsed = 12, satsVisible = 20, diffAgeS = null, speedMps = 0.0, courseDeg = 0.0,
    )

    private fun TestScope.session(fixes: MutableSharedFlow<Fix>, policy: AveragingPolicy) = ObservationSession(
        backgroundScope, fixes, policy,
        wallNow = { wallBase.plusMillis(testScheduler.currentTime + wallStepMs) },
        monotonicNowMs = { testScheduler.currentTime },
    )

    @Test fun `a forward wall-clock step does not complete a 30 s capture early`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        val s = session(fixes, AveragingPolicy.INTERNAL.copy(minSamples = 1))
        s.start(); runCurrent()
        fixes.emit(fix()); runCurrent()

        advanceTimeBy(20_000); runCurrent()
        wallStepMs = 15_000                       // clock corrected +15 s at 20 s
        fixes.emit(fix()); runCurrent()
        assertTrue("20 s into a 30 s capture: still capturing, was ${s.state.value}",
            s.state.value is ObservationSession.State.Capturing)
        assertEquals(20, (s.state.value as ObservationSession.State.Capturing).elapsedSec)

        advanceTimeBy(10_500); runCurrent()       // 30.5 s of real time
        val done = s.state.value
        assertTrue("completes once 30 s have really passed, was $done", done is ObservationSession.State.Complete)
        assertTrue((done as ObservationSession.State.Complete).requirementsMet)
    }

    @Test fun `a backward wall-clock step does not stop the capture timing out`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        // minSamples can't be reached, so only maxDurationSec (90 s) can end the capture.
        val s = session(fixes, AveragingPolicy.INTERNAL.copy(minSamples = 10_000))
        s.start(); runCurrent()
        fixes.emit(fix()); runCurrent()

        advanceTimeBy(10_000); runCurrent()
        wallStepMs = -120_000                     // clock corrected −2 min at 10 s
        advanceTimeBy(81_000); runCurrent()       // 91 s of real time
        val done = s.state.value
        assertTrue("times out at 90 s of real time, was $done", done is ObservationSession.State.Complete)
        assertFalse((done as ObservationSession.State.Complete).requirementsMet)
    }

    @Test fun `startedAt and endedAt still come from the wall clock`() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        val s = session(fixes, AveragingPolicy.INTERNAL.copy(minDurationSec = 0, minSamples = 1))
        s.start(); runCurrent()
        fixes.emit(fix()); runCurrent()
        val r = (s.state.value as ObservationSession.State.Complete).result
        assertEquals(wallBase, r.startedAt)
        assertEquals(wallBase, r.endedAt)
    }
}
