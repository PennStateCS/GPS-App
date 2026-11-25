package io.github.pennstatecs.geokit.receiver.emlid.tilt

import io.github.pennstatecs.geokit.receiver.emlid.EmlidReachModel
import io.github.pennstatecs.geokit.receiver.emlid.tilt.CompensatedNmeaOutput.DISABLED
import io.github.pennstatecs.geokit.receiver.emlid.tilt.CompensatedNmeaOutput.ENABLED
import io.github.pennstatecs.geokit.receiver.emlid.tilt.CompensatedNmeaOutput.UNKNOWN
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmlidPositionReferenceTest {

    private fun matched(state: EmlidTiltState) = EmlidTiltAssociation(
        state,
        EmlidTiltReading(state, null, 0.0, null, null, null, null, null, null, null),
        TiltMatch.MATCHED,
    )
    private fun unmatched(match: TiltMatch) = EmlidTiltAssociation(EmlidTiltState.UNKNOWN, null, match)

    private val rs4Models = listOf(EmlidReachModel.RS4, EmlidReachModel.RS4_PRO, null)
    private val allSettings = CompensatedNmeaOutput.entries

    private fun ref(model: EmlidReachModel?, out: CompensatedNmeaOutput, fw: String?, tilt: EmlidTiltAssociation) =
        EmlidPositionReference.classify(model, out, fw, tilt)

    @Test
    fun `pole tip only when declared, firmware at least 34_7 and matched ACTIVE`() {
        for (m in rs4Models) {
            val r = ref(m, ENABLED, "34.7", matched(EmlidTiltState.ACTIVE))
            assertEquals(PositionReference.RECEIVER_COMPENSATED_POLE_TIP, r.reference)
            assertEquals(Reason.RECEIVER_COMPENSATING, r.reason)
            assertEquals(PositionReference.RECEIVER_COMPENSATED_POLE_TIP, ref(m, ENABLED, "v34.10.1", matched(EmlidTiltState.ACTIVE)).reference)
            assertEquals(PositionReference.RECEIVER_COMPENSATED_POLE_TIP, ref(m, ENABLED, "35.0", matched(EmlidTiltState.ACTIVE)).reference)
        }
    }

    @Test
    fun `an ACTIVE ETC alone never means pole tip`() {
        for (m in rs4Models) {
            val r = ref(m, UNKNOWN, "34.10", matched(EmlidTiltState.ACTIVE))
            assertEquals(PositionReference.UNKNOWN, r.reference)
            assertEquals(Reason.OUTPUT_SETTING_UNKNOWN, r.reason)
        }
    }

    @Test
    fun `old or unknown firmware with compensation declared is UNKNOWN`() {
        for (fw in listOf(null, "", "unknown", "34.6", "34.4", "33.9", "2.24")) {
            val r = ref(EmlidReachModel.RS4_PRO, ENABLED, fw, matched(EmlidTiltState.ACTIVE))
            assertEquals("fw=$fw", PositionReference.UNKNOWN, r.reference)
            assertEquals(Reason.FIRMWARE_UNKNOWN_OR_TOO_OLD, r.reason)
        }
    }

    @Test
    fun `declared compensation but engine not compensating is UNKNOWN`() {
        for (s in listOf(EmlidTiltState.INITIALIZING, EmlidTiltState.ALIGNING, EmlidTiltState.UNAVAILABLE)) {
            val r = ref(EmlidReachModel.RS4, ENABLED, "34.10", matched(s))
            assertEquals("$s", PositionReference.UNKNOWN, r.reference)
            assertEquals(Reason.TILT_NOT_COMPENSATING, r.reason)
        }
    }

    @Test
    fun `declared compensation without a matched ETC is UNKNOWN`() {
        for (m in TiltMatch.entries - TiltMatch.MATCHED) {
            val r = ref(EmlidReachModel.RS4, ENABLED, "34.10", unmatched(m))
            assertEquals("$m", PositionReference.UNKNOWN, r.reference)
            assertEquals(Reason.TILT_STATE_UNKNOWN, r.reason)
        }
    }

    @Test
    fun `matched tilt OFF is ANTENNA for any setting`() {
        for (m in rs4Models) for (s in allSettings) {
            val r = ref(m, s, null, matched(EmlidTiltState.OFF))
            assertEquals(PositionReference.ANTENNA, r.reference)
            assertEquals(Reason.TILT_OFF, r.reason)
            assertFalse(r.antennaMayBeTilted)
        }
    }

    @Test
    fun `compensated output disabled is ANTENNA, flagged as possibly tilted`() {
        for (t in listOf(matched(EmlidTiltState.ACTIVE), matched(EmlidTiltState.ALIGNING), unmatched(TiltMatch.NO_ETC_SEEN), unmatched(TiltMatch.NOT_MATCHED))) {
            val r = ref(EmlidReachModel.RS4_PRO, DISABLED, null, t)
            assertEquals(PositionReference.ANTENNA, r.reference)
            assertEquals(Reason.COMPENSATED_OUTPUT_DISABLED, r.reason)
            assertTrue(r.antennaMayBeTilted)
        }
    }

    @Test
    fun `RS4 class without ETC and unknown setting is UNKNOWN`() {
        for (m in rs4Models) for (match in TiltMatch.entries - TiltMatch.MATCHED) {
            assertEquals(PositionReference.UNKNOWN, ref(m, UNKNOWN, "34.10", unmatched(match)).reference)
        }
    }

    @Test
    fun `RS2 and RS2+ are ANTENNA while no ETC, UNKNOWN if one appears`() {
        for (m in listOf(EmlidReachModel.RS2, EmlidReachModel.RS2_PLUS)) for (s in allSettings) {
            val r = ref(m, s, null, unmatched(TiltMatch.NO_ETC_SEEN))
            assertEquals(PositionReference.ANTENNA, r.reference)
            assertEquals(Reason.NO_IMU_MODEL, r.reason)
            assertFalse(r.antennaMayBeTilted)
            val wrong = ref(m, s, "34.10", matched(EmlidTiltState.ACTIVE))
            assertEquals(PositionReference.UNKNOWN, wrong.reference)
            assertEquals(Reason.ETC_ON_NO_IMU_MODEL, wrong.reason)
        }
    }

    @Test
    fun `firmware parsing`() {
        assertEquals(34 to 7, EmlidPositionReference.parseFirmware("34.7"))
        assertEquals(34 to 10, EmlidPositionReference.parseFirmware("v34.10"))
        assertEquals(34 to 10, EmlidPositionReference.parseFirmware("34.10.1-beta"))
        assertEquals(34 to 4, EmlidPositionReference.parseFirmware("Firmware 34.4"))
        assertNull(EmlidPositionReference.parseFirmware("34"))
        assertNull(EmlidPositionReference.parseFirmware(null))
        assertFalse(EmlidPositionReference.firmwareAtLeast("34.6", 34 to 7))
        assertTrue(EmlidPositionReference.firmwareAtLeast("34.7", 34 to 7))
        assertTrue(EmlidPositionReference.firmwareAtLeast("34.10", 34 to 7))
    }

    @Test
    fun `an unclassifiable epoch under declared compensated output is flagged mayBeCompensated`() {
        // Review MEDIUM: such an epoch may already be the pole tip; the app must not reduce it again.
        for (tilt in listOf(unmatched(TiltMatch.NOT_MATCHED), unmatched(TiltMatch.ETC_STREAM_STALE), matched(EmlidTiltState.ALIGNING))) {
            val r = ref(EmlidReachModel.RS4_PRO, CompensatedNmeaOutput.ENABLED, "34.10", tilt)
            assertEquals(PositionReference.UNKNOWN, r.reference)
            assertTrue("$tilt", r.mayBeCompensated)
        }
        assertTrue(ref(EmlidReachModel.RS4_PRO, CompensatedNmeaOutput.ENABLED, "34.3", matched(EmlidTiltState.ACTIVE)).mayBeCompensated)
        // Not flagged when output is declared off or unknown, or when the epoch is classified.
        assertFalse(ref(EmlidReachModel.RS4_PRO, CompensatedNmeaOutput.DISABLED, "34.10", unmatched(TiltMatch.ETC_STREAM_STALE)).mayBeCompensated)
        assertFalse(ref(EmlidReachModel.RS4_PRO, CompensatedNmeaOutput.UNKNOWN, "34.10", unmatched(TiltMatch.ETC_STREAM_STALE)).mayBeCompensated)
        assertFalse(ref(EmlidReachModel.RS4_PRO, CompensatedNmeaOutput.ENABLED, "34.10", matched(EmlidTiltState.ACTIVE)).mayBeCompensated)
    }

    @Test
    fun `pole tip carries the receiver warning through`() {
        fun active(n: io.github.pennstatecs.geokit.receiver.emlid.nmea.EtcNotification) = EmlidTiltAssociation(
            EmlidTiltState.ACTIVE,
            EmlidTiltReading(EmlidTiltState.ACTIVE, n, 0.0, null, null, null, null, null, null, null),
            TiltMatch.MATCHED,
        )
        val warn = ref(EmlidReachModel.RS4_PRO, CompensatedNmeaOutput.ENABLED, "34.10",
            active(io.github.pennstatecs.geokit.receiver.emlid.nmea.EtcNotification.FAST_MOTION))
        assertEquals(PositionReference.RECEIVER_COMPENSATED_POLE_TIP, warn.reference)
        assertTrue(warn.hasWarning)
        val ok = ref(EmlidReachModel.RS4_PRO, CompensatedNmeaOutput.ENABLED, "34.10",
            active(io.github.pennstatecs.geokit.receiver.emlid.nmea.EtcNotification.NONE))
        assertFalse(ok.hasWarning)
    }
}

