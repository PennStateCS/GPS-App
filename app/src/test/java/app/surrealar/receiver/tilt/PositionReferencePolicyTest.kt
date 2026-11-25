package app.surrealar.receiver.tilt

import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.receiver.emlid.nmea.EtcNotification
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltAssociation
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltReading
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltState
import io.github.pennstatecs.geokit.receiver.emlid.tilt.TiltMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionReferencePolicyTest {

    private fun reading(state: EmlidTiltState, notification: EtcNotification? = EtcNotification.NONE) = EmlidTiltReading(
        state, notification, 61_200.0, 10.0, 20.0, 5.0, 1.0, 1.0, 1.0, null)

    private fun matched(state: EmlidTiltState, notification: EtcNotification? = EtcNotification.NONE) =
        EmlidTiltAssociation(state, reading(state, notification), TiltMatch.MATCHED)

    private fun unmatched(match: TiltMatch) = EmlidTiltAssociation(EmlidTiltState.UNKNOWN, null, match)

    private fun eval(
        profile: ExternalReceiverProfile = ExternalReceiverProfile.REACH_RS4_PRO,
        output: CompensatedOutputSetting = CompensatedOutputSetting.ENABLED,
        firmware: String? = "34.10",
        tilt: EmlidTiltAssociation,
        recentTiltOn: Boolean = false,
    ) = PositionReferencePolicy.evaluate(profile, output, firmware, tilt, recentTiltOn)

    @Test fun rs2PlusAndGeneric_areNotApplicable_withoutCallingTheClassifier() {
        for (p in listOf(ExternalReceiverProfile.REACH_RS2_PLUS, ExternalReceiverProfile.GENERIC_NMEA_TCP, null)) {
            val c = eval(profile = p ?: ExternalReceiverProfile.REACH_RS2_PLUS, tilt = matched(EmlidTiltState.ACTIVE))
                .takeIf { p != null } ?: PositionReferencePolicy.evaluate(null, CompensatedOutputSetting.ENABLED, "34.10", matched(EmlidTiltState.ACTIVE), true)
            assertEquals(EpochReference.NOT_APPLICABLE, c.reference)
            assertNull(c.result)
        }
    }

    @Test fun unknownProfile_isTreatedAsOutputUnknown_neverPoleTip() {
        val u = PositionReferencePolicy::evaluateUnknownProfile
        assertEquals(EpochReference.MAY_BE_COMPENSATED, u("34.10", matched(EmlidTiltState.ACTIVE), true).reference)
        assertEquals(EpochReference.MAY_BE_COMPENSATED, u("34.10", unmatched(TiltMatch.NOT_MATCHED), true).reference)
        assertEquals(EpochReference.ANTENNA, u("34.10", matched(EmlidTiltState.OFF), false).reference)
        assertEquals(EpochReference.ANTENNA_UNCERTAIN, u(null, unmatched(TiltMatch.NO_ETC_SEEN), false).reference)
        for (state in EmlidTiltState.entries) for (fw in listOf("34.10", null)) {
            assertTrue(u(fw, matched(state), true).reference != EpochReference.POLE_TIP)
        }
        assertEquals(EmlidPositionReference.Reason.OUTPUT_SETTING_UNKNOWN, u("34.10", unmatched(TiltMatch.NO_ETC_SEEN), false).result?.reason)
    }

    @Test fun enabled_firmwareOk_matchedActive_isPoleTip() {
        val c = eval(tilt = matched(EmlidTiltState.ACTIVE))
        assertEquals(EpochReference.POLE_TIP, c.reference)
        assertEquals(false, c.hasWarning)
    }

    @Test fun poleTip_withNotification_carriesTheWarning() {
        val c = eval(tilt = matched(EmlidTiltState.ACTIVE, EtcNotification.FAST_MOTION))
        assertEquals(EpochReference.POLE_TIP, c.reference)
        assertTrue(c.hasWarning)
    }

    @Test fun enabled_aligning_or_unmatched_or_oldFirmware_mayBeCompensated() {
        assertEquals(EpochReference.MAY_BE_COMPENSATED, eval(tilt = matched(EmlidTiltState.ALIGNING)).reference)
        assertEquals(EpochReference.MAY_BE_COMPENSATED, eval(tilt = unmatched(TiltMatch.NOT_MATCHED)).reference)
        assertEquals(EpochReference.MAY_BE_COMPENSATED, eval(tilt = unmatched(TiltMatch.ETC_STREAM_STALE)).reference)
        assertEquals(EpochReference.MAY_BE_COMPENSATED, eval(firmware = "34.4", tilt = matched(EmlidTiltState.ACTIVE)).reference)
        assertEquals(EpochReference.MAY_BE_COMPENSATED, eval(firmware = null, tilt = matched(EmlidTiltState.ACTIVE)).reference)
    }

    @Test fun tiltOff_isAntenna_whateverTheSetting() {
        for (o in CompensatedOutputSetting.entries) {
            assertEquals(o.name, EpochReference.ANTENNA, eval(output = o, tilt = matched(EmlidTiltState.OFF)).reference)
        }
    }

    @Test fun disabled_isAntenna_withTiltedPoleWarning() {
        val c = eval(output = CompensatedOutputSetting.DISABLED, tilt = matched(EmlidTiltState.ACTIVE))
        assertEquals(EpochReference.ANTENNA, c.reference)
        assertEquals(true, c.result?.antennaMayBeTilted)
    }

    @Test fun unknownSetting_tiltEngineOn_isMayBeCompensated_appPolicy() {
        val c = eval(output = CompensatedOutputSetting.UNKNOWN, tilt = matched(EmlidTiltState.ACTIVE))
        assertEquals(EmlidPositionReference.Reason.OUTPUT_SETTING_UNKNOWN, c.result?.reason)
        assertEquals(false, c.result?.mayBeCompensated)   // the library's own flag is unchanged
        assertEquals(EpochReference.MAY_BE_COMPENSATED, c.reference)
        assertEquals(EpochReference.MAY_BE_COMPENSATED,
            eval(output = CompensatedOutputSetting.UNKNOWN, tilt = matched(EmlidTiltState.ALIGNING)).reference)
    }

    @Test fun unknownSetting_unmatched_followsTheRecentStream() {
        assertEquals(EpochReference.MAY_BE_COMPENSATED,
            eval(output = CompensatedOutputSetting.UNKNOWN, tilt = unmatched(TiltMatch.NOT_MATCHED), recentTiltOn = true).reference)
        assertEquals(EpochReference.ANTENNA_UNCERTAIN,
            eval(output = CompensatedOutputSetting.UNKNOWN, tilt = unmatched(TiltMatch.NOT_MATCHED), recentTiltOn = false).reference)
    }

    @Test fun unknownSetting_noEtcAtAll_isAntennaUncertain() {
        assertEquals(EpochReference.ANTENNA_UNCERTAIN,
            eval(output = CompensatedOutputSetting.UNKNOWN, tilt = unmatched(TiltMatch.NO_ETC_SEEN), recentTiltOn = true).reference)
    }

    @Test fun isTiltOn_treatsOnlyOffAndNoneAsOff() {
        assertEquals(false, PositionReferencePolicy.isTiltOn(null))
        assertEquals(false, PositionReferencePolicy.isTiltOn(EmlidTiltState.OFF))
        for (s in EmlidTiltState.entries - EmlidTiltState.OFF) assertTrue(s.name, PositionReferencePolicy.isTiltOn(s))
    }
}
