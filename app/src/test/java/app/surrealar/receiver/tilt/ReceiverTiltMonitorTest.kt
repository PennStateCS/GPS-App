package app.surrealar.receiver.tilt

import app.surrealar.receiver.tilt.TiltTestNmea.etcActive
import app.surrealar.receiver.tilt.TiltTestNmea.etcOff
import app.surrealar.receiver.tilt.TiltTestNmea.fix
import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltState
import io.github.pennstatecs.geokit.receiver.emlid.tilt.TiltMatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverTiltMonitorTest {

    private var now = 1_000_000L
    private val profile = MutableStateFlow(ExternalReceiverProfile.REACH_RS4_PRO)
    private val output = MutableStateFlow(CompensatedOutputSetting.ENABLED)
    private val firmware = MutableStateFlow<String?>("34.10")
    private val resets = MutableStateFlow(0)

    private fun TestScope.monitor() = ReceiverTiltMonitor(
        scope = backgroundScope, profile = profile, compensatedOutput = output, firmware = firmware,
        resetSignals = resets, clockMs = { now }, tickMs = null,
    )

    private val t0 = 61_200.0   // 17:00:00 UTC

    @Test fun etcBeforeTheFix_isMatched_poleTip() = runTest {
        val m = monitor()
        m.onLine(etcActive(t0))
        val c = m.classify(fix(t0))
        assertEquals(TiltMatch.MATCHED, c.tilt?.match)
        assertEquals(EpochReference.POLE_TIP, c.reference)
    }

    @Test fun etcFollowingTheFix_isAwaited() = runTest {
        val m = monitor()
        m.onLine(etcActive(t0))                 // previous stream activity
        val pending = async { m.classify(fix(t0 + 0.2)) }
        runCurrent()
        assertFalse("waits for the epoch's own ETC", pending.isCompleted)
        now += 5
        m.onLine(etcActive(t0 + 0.2))
        runCurrent()
        assertEquals(EpochReference.POLE_TIP, pending.await().reference)
    }

    @Test fun etcThatNeverComes_timesOut_neverUsesTheOlderEtc() = runTest {
        val m = monitor()
        m.onLine(etcActive(t0))
        val pending = async { m.classify(fix(t0 + 0.2)) }
        advanceTimeBy(ReceiverTiltMonitor.DEFAULT_ETC_WAIT_MS + 1)
        val c = pending.await()
        assertEquals(TiltMatch.NOT_MATCHED, c.tilt?.match)
        assertEquals(EpochReference.MAY_BE_COMPENSATED, c.reference)
    }

    @Test fun staleEtc_withTheSameTimeOfDay_isNotPairedWithAFreshFix() = runTest {
        val m = monitor()
        m.onLine(etcActive(t0))
        now += 3_000                              // fix received 3 s later
        val pending = async { m.classify(fix(t0)) }
        advanceTimeBy(ReceiverTiltMonitor.DEFAULT_ETC_WAIT_MS + 1)
        val c = pending.await()
        assertTrue(c.tilt?.match != TiltMatch.MATCHED)
        assertTrue(c.reference != EpochReference.POLE_TIP)
    }

    @Test fun badChecksum_isIgnored() = runTest {
        val m = monitor()
        val good = etcActive(t0)
        m.onLine(good.dropLast(2) + "00")
        m.refreshStatus()
        assertEquals(0L, m.status.value.etcCount)
        m.onLine(good)
        m.refreshStatus()
        assertEquals(1L, m.status.value.etcCount)
        m.onLine(TiltTestNmea.withChecksum("GNGGA,170000.00,4100.0000,N,07600.0000,W,4,20,0.8,100.0,M,-33.0,M,1.0,0000"))
        m.refreshStatus()
        assertEquals("non-ETC lines are not counted", 1L, m.status.value.etcCount)
    }

    @Test fun resetSignal_forgetsTheEtcStream() = runTest {
        val m = monitor()
        m.onLine(etcActive(t0))
        runCurrent()
        resets.value = 1
        runCurrent()
        val c = m.classify(fix(t0))
        assertEquals(TiltMatch.NO_ETC_SEEN, c.tilt?.match)
        assertEquals(EpochReference.MAY_BE_COMPENSATED, c.reference)   // output ENABLED, tilt state unknown
        assertFalse(m.status.value.etcSeen)
    }

    @Test fun rs2Plus_internal_and_replay_areNotApplicable_withoutWaiting() = runTest {
        val m = monitor()
        assertEquals(EpochReference.NOT_APPLICABLE, m.classify(fix(t0), ExternalReceiverProfile.REACH_RS2_PLUS).reference)
        assertEquals(EpochReference.NOT_APPLICABLE, m.classify(fix(t0, Provider.INTERNAL)).reference)
        assertEquals(EpochReference.NOT_APPLICABLE, m.classify(fix(t0, Provider.REPLAY)).reference)
    }

    @Test fun deviceClockTime_offTheNmeaGrid_isNotMatchable() {
        assertNull(ReceiverTiltMonitor.timeOfDaySeconds(fix(t0 + 0.003, timestampSource = TimestampSource.DEVICE)))
        assertEquals(t0, ReceiverTiltMonitor.timeOfDaySeconds(fix(t0, timestampSource = TimestampSource.DEVICE))!!, 1e-9)
        assertEquals(t0 + 0.2, ReceiverTiltMonitor.timeOfDaySeconds(fix(t0 + 0.2))!!, 1e-9)
    }

    @Test fun status_followsObservedFixes_andFlagsPoleTipHeightsForAr() = runTest {
        val m = monitor()
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 8)
        m.observe(fixes)
        runCurrent()
        m.onLine(etcActive(t0))
        fixes.emit(fix(t0))
        runCurrent()
        val s = m.status.value
        assertTrue(s.applicable)
        assertEquals(EmlidTiltState.ACTIVE, s.tiltState)
        assertEquals(EpochReference.POLE_TIP, s.reference)
        assertTrue(m.heightsMayBePoleTip())
        assertTrue(TiltStatusText.stateLine(s).startsWith("Tilt: active"))

        now += 200
        m.onLine(etcOff(t0 + 0.2))
        fixes.emit(fix(t0 + 0.2))
        runCurrent()
        assertEquals(EpochReference.ANTENNA, m.status.value.reference)
        assertFalse(m.heightsMayBePoleTip())
    }

    @Test fun rs2PlusProfile_neverFlagsArHeights() = runTest {
        profile.value = ExternalReceiverProfile.REACH_RS2_PLUS
        val m = monitor()
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 8)
        m.observe(fixes)
        runCurrent()
        fixes.emit(fix(t0))
        runCurrent()
        assertFalse(m.status.value.applicable)
        assertFalse(m.heightsMayBePoleTip())
    }

    // ── arrival-time pairing: dropped ETCs never cascade ─────────────────────

    private data class Processed(val index: Int, val match: TiltMatch?, val lagMs: Long)

    /**
     * A 5 Hz stream through a sequential consumer (like the capture's filter chain), with a clock driven by the
     * test scheduler and the app's 1 s ETC wait. Each fix is stamped on arrival ([ReceiverTiltMonitor.recordArrival]);
     * its ETC follows 5 ms later, except for [dropped] epochs. [byArrival] = false reproduces the old behaviour
     * (pairing by processing time).
     */
    private fun TestScope.runStream(count: Int, dropped: Set<Int>, byArrival: Boolean): List<Processed> {
        val clock = { testScheduler.currentTime }
        val m = ReceiverTiltMonitor(backgroundScope, profile, output, firmware, resets, clock, etcWaitMs = 1_000, tickMs = null)
        val feed = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
        val out = mutableListOf<Processed>()
        val consumer = launch {
            var i = 0
            feed.collect { f ->
                val arrived = m.arrivalOf(f)!!
                val c = if (byArrival) m.classify(f) else m.classify(f, seenAtMs = clock())
                out += Processed(i++, c.tilt?.match, clock() - arrived)
                if (i == count) cancel()
            }
        }
        launch {   // starts after the consumer has subscribed (the test dispatcher runs launches in order)
            for (i in 0 until count) {
                val sod = t0 + i * 0.2
                feed.emit(m.recordArrival(fix(sod)))
                delay(5)
                if (i !in dropped) m.onLine(etcActive(sod))
                delay(195)
            }
        }
        advanceUntilIdle()
        assertTrue(consumer.isCancelled || consumer.isCompleted)
        return out
    }

    @Test fun droppedEtcs_doNotCascade_laterFixesMatchWithoutLag() = runTest {
        val r = runStream(count = 30, dropped = setOf(3, 4), byArrival = true)
        assertEquals(30, r.size)
        assertEquals(TiltMatch.NOT_MATCHED, r[3].match)
        assertEquals(TiltMatch.NOT_MATCHED, r[4].match)
        assertTrue("every fix after the ETCs resume is matched: ${r.drop(5)}", r.drop(5).all { it.match == TiltMatch.MATCHED })
        assertTrue("no fix waits beyond its own arrival + 1 s: ${r.maxOf { it.lagMs }}", r.all { it.lagMs <= 1_000 })
        assertTrue("the backlog drains: later fixes only wait for their own ETC (5 ms)", r.drop(10).all { it.lagMs <= 10 })
    }

    @Test fun pairingByProcessingTime_wouldCascade_reproducer() = runTest {
        // Same stream, old behaviour: after the two dropped ETCs every later fix is processed later than its ETC
        // window, NOT_MATCHED, and waits again — the lag never recovers.
        val r = runStream(count = 30, dropped = setOf(3, 4), byArrival = false)
        assertTrue(r.takeLast(5).all { it.match != TiltMatch.MATCHED })
        assertTrue(r.last().lagMs > 2_000)
    }

    @Test fun alreadyLateFix_neverWaits() = runTest {
        val m = monitor()
        m.onLine(etcActive(t0))
        val f = m.recordArrival(fix(t0 + 0.2))
        now += 1_500                                   // processed 1.5 s after it arrived; its ETC never came
        val pending = async { m.classify(f) }
        runCurrent()
        assertTrue("no wait once arrival + 1 s has passed", pending.isCompleted)
        assertEquals(TiltMatch.NOT_MATCHED, pending.await().tilt?.match)
    }

    @Test fun lateProcessedFix_isStillMatchedByArrivalTime() = runTest {
        val m = monitor()
        val f = m.recordArrival(fix(t0))
        now += 5
        m.onLine(etcActive(t0))
        now += 1_500                                   // the consumer is behind; pairing uses the arrival time
        assertEquals(EpochReference.POLE_TIP, m.classify(f).reference)
    }

    @Test fun antennaRs2AndDisabledCaptures_neverWait() = runTest {
        val m = monitor()
        m.onLine(etcActive(t0))                        // stream live; the fixes below have no ETC yet (NOT_MATCHED)
        val start = testScheduler.currentTime
        val rs2 = async { m.classify(fix(t0 + 0.2), ExternalReceiverProfile.REACH_RS2_PLUS) }
        val disabled = async { m.classify(fix(t0 + 0.2), output = CompensatedOutputSetting.DISABLED, waitOnlyWhenNeeded = true) }
        runCurrent()
        assertTrue(rs2.isCompleted && disabled.isCompleted)
        assertEquals(start, testScheduler.currentTime)
        assertEquals(EpochReference.NOT_APPLICABLE, rs2.await().reference)
        assertEquals(EpochReference.ANTENNA, disabled.await().reference)
        // Unknown output still waits: the epoch's own ETC decides "may be compensated" vs "uncertain".
        val unknown = async { m.classify(fix(t0 + 0.2), output = CompensatedOutputSetting.UNKNOWN, waitOnlyWhenNeeded = true) }
        runCurrent()
        assertFalse(unknown.isCompleted)
        advanceTimeBy(ReceiverTiltMonitor.DEFAULT_ETC_WAIT_MS + 1)
        assertTrue(unknown.isCompleted)
    }

    @Test fun unreadableProfile_isConservative_neverPoleTip() = runTest {
        val m = monitor()
        m.onLine(etcActive(t0))
        val c = m.classify(fix(t0), profile = null, profileKnown = false)
        assertEquals("tilt on, output unknown → possibly compensated", EpochReference.MAY_BE_COMPENSATED, c.reference)
        m.reset()
        val noEtc = m.classify(fix(t0 + 0.2), profile = null, profileKnown = false)
        assertEquals(EpochReference.ANTENNA_UNCERTAIN, noEtc.reference)
    }

    @Test fun etcRate_isReported_andResetClearsIt() = runTest {
        val m = monitor()
        for (i in 0 until 6) { m.onLine(etcActive(t0 + i * 0.2)); now += 200 }
        now -= 200
        m.refreshStatus()
        assertEquals(5.0, m.status.value.etcRateHz!!, 0.01)
        m.reset()
        assertNull(m.status.value.etcRateHz)
    }
}
