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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
