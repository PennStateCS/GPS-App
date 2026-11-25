package app.surrealar.receiver.tilt

import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference.Reason
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltAssociation
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltState
import io.github.pennstatecs.geokit.receiver.emlid.tilt.PositionReference
import io.github.pennstatecs.geokit.receiver.emlid.tilt.TiltMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.surrealar.receiver.tilt.CaptureReferenceGate.Decision
import app.surrealar.receiver.tilt.CaptureReferenceGate.StopReason

class CaptureReferenceGateTest {

    private fun c(ref: EpochReference, match: TiltMatch = TiltMatch.MATCHED, reason: Reason = Reason.RECEIVER_COMPENSATING, warning: Boolean = false) =
        EpochClassification(ref,
            EmlidPositionReference.Result(PositionReference.UNKNOWN, reason, hasWarning = warning, mayBeCompensated = ref == EpochReference.MAY_BE_COMPENSATED),
            EmlidTiltAssociation(EmlidTiltState.UNKNOWN, null, match))

    private val poleTip = c(EpochReference.POLE_TIP)
    private val antenna = c(EpochReference.ANTENNA, reason = Reason.TILT_OFF)

    @Test fun notApplicable_isAlwaysAccepted_andNeverBinds() {
        val g = CaptureReferenceGate()
        repeat(3) { assertEquals(Decision.Accept, g.decide(EpochClassification(EpochReference.NOT_APPLICABLE, null, null), it, 0)) }
        assertEquals(null, g.bound)
    }

    @Test fun poleTipOccupation_bindsAndAccepts() {
        val g = CaptureReferenceGate()
        assertEquals(Decision.Accept, g.decide(poleTip, 0, 0))
        assertEquals(Decision.Accept, g.decide(poleTip, 1, 200))
        assertEquals(EpochReference.POLE_TIP, g.bound)
    }

    @Test fun referenceChange_afterAveraging_stops_beforeAveraging_rebinds() {
        val g = CaptureReferenceGate()
        g.decide(antenna, 0, 0)
        assertEquals("nothing averaged yet: rebinding is fine", Decision.Accept, g.decide(poleTip, 0, 100))
        assertEquals(EpochReference.POLE_TIP, g.bound)
        val stop = g.decide(antenna, 2, 300)
        assertTrue(stop is Decision.Stop && stop.reason == StopReason.REFERENCE_CHANGED)
    }

    @Test fun antennaAndUncertainAntenna_areOneGroup() {
        val g = CaptureReferenceGate()
        g.decide(antenna, 0, 0)
        assertEquals(Decision.Accept, g.decide(c(EpochReference.ANTENNA_UNCERTAIN, TiltMatch.NOT_MATCHED, Reason.OUTPUT_SETTING_UNKNOWN), 3, 100))
        assertTrue(g.uncertainAdmitted)
    }

    @Test fun poleTipWarning_isExcluded_notStopped() {
        val g = CaptureReferenceGate()
        g.decide(poleTip, 0, 0)
        assertTrue(g.decide(c(EpochReference.POLE_TIP, warning = true), 1, 200) is Decision.Exclude)
        assertEquals(EpochReference.POLE_TIP, g.bound)
    }

    @Test fun poleTipOccupation_tiltStateLost_stops() {
        val g = CaptureReferenceGate()
        g.decide(poleTip, 0, 0)
        val lost = g.decide(c(EpochReference.MAY_BE_COMPENSATED, TiltMatch.MATCHED, Reason.TILT_NOT_COMPENSATING), 4, 1_000)
        assertTrue(lost is Decision.Stop && lost.reason == StopReason.TILT_LOST)
    }

    @Test fun poleTipOccupation_singleUnmatchedEpoch_isExcluded_staleStreamStops() {
        val g = CaptureReferenceGate()
        g.decide(poleTip, 0, 0)
        assertTrue(g.decide(c(EpochReference.MAY_BE_COMPENSATED, TiltMatch.NOT_MATCHED, Reason.TILT_STATE_UNKNOWN), 4, 1_000) is Decision.Exclude)
        assertEquals(Decision.Accept, g.decide(poleTip, 4, 1_200))
        val stale = g.decide(c(EpochReference.MAY_BE_COMPENSATED, TiltMatch.ETC_STREAM_STALE, Reason.TILT_STATE_UNKNOWN), 5, 4_000)
        assertTrue(stale is Decision.Stop && stale.reason == StopReason.TILT_LOST)
    }

    @Test fun antennaOccupation_tiltSwitchedOn_stops_unmatchedOnlyExcluded() {
        val g = CaptureReferenceGate()
        g.decide(antenna, 0, 0)
        assertTrue(g.decide(c(EpochReference.MAY_BE_COMPENSATED, TiltMatch.NOT_MATCHED, Reason.OUTPUT_SETTING_UNKNOWN), 2, 100) is Decision.Exclude)
        val on = g.decide(c(EpochReference.MAY_BE_COMPENSATED, TiltMatch.MATCHED, Reason.OUTPUT_SETTING_UNKNOWN), 2, 200)
        assertTrue(on is Decision.Stop && on.reason == StopReason.REFERENCE_CHANGED)
    }

    @Test fun onlyPossiblyCompensatedEpochs_stopAfterTheTimeout() {
        val g = CaptureReferenceGate(noValidEpochsMs = 1_000)
        val maybe = c(EpochReference.MAY_BE_COMPENSATED, TiltMatch.MATCHED, Reason.OUTPUT_SETTING_UNKNOWN)
        assertTrue(g.decide(maybe, 0, 0) is Decision.Exclude)
        assertTrue(g.decide(maybe, 0, 999) is Decision.Exclude)
        val stop = g.decide(maybe, 0, 1_000)
        assertTrue(stop is Decision.Stop && stop.reason == StopReason.MAY_BE_COMPENSATED)
        assertEquals(3, g.excluded)
    }

    @Test fun anAdmissibleEpoch_resetsTheNoValidEpochsTimer() {
        val g = CaptureReferenceGate(noValidEpochsMs = 1_000)
        val maybe = c(EpochReference.MAY_BE_COMPENSATED, TiltMatch.NOT_MATCHED, Reason.TILT_STATE_UNKNOWN)
        g.decide(maybe, 0, 0)
        g.decide(poleTip, 0, 500)          // admitted (the session may still reject it for quality)
        assertTrue(g.decide(maybe, 0, 1_200) is Decision.Exclude)
        assertTrue(g.decide(maybe, 0, 2_100) is Decision.Exclude)
        assertTrue(g.decide(maybe, 0, 2_200) is Decision.Stop)
    }
}
