package app.surrealar.receiver.tilt

import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.receiver.emlid.EmlidReachModel
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltAssociation
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltState
import io.github.pennstatecs.geokit.receiver.emlid.tilt.PositionReference
import io.github.pennstatecs.geokit.receiver.emlid.tilt.TiltMatch

/**
 * What one external epoch means for the app (Phase 4 WP C), on top of geokit's
 * [EmlidPositionReference.classify].
 */
enum class EpochReference {
    /** The receiver's antenna phase centre: today's maths (pole height + phase-centre offset subtracted). */
    ANTENNA,
    /**
     * Not established, but nothing suggests a compensated position (no ETC, or the output setting is unknown
     * and the tilt engine is off / not reported): today's maths, shown as "uncertain".
     */
    ANTENNA_UNCERTAIN,
    /** The receiver already moved the position to the pole tip: the app must NOT subtract pole height / PCO. */
    POLE_TIP,
    /** May already be the pole tip: never reduced, never averaged (excluded from captures). */
    MAY_BE_COMPENSATED,
    /** Not an RS4 / RS4 Pro profile (RS2+, generic NMEA, internal GNSS): unchanged behaviour, no tilt logic. */
    NOT_APPLICABLE,
}

/** [reference] for one epoch, with geokit's [result] (null when not applicable) and the ETC association. */
data class EpochClassification(
    val reference: EpochReference,
    val result: EmlidPositionReference.Result?,
    val tilt: EmlidTiltAssociation?,
) {
    /** The matched ETC carries a receiver notification (compensation degraded). */
    val hasWarning: Boolean get() = result?.hasWarning == true
}

/**
 * Pure rules turning geokit's per-epoch classification into the app's [EpochReference]. No maths: positions are
 * never changed here; it only decides which interpretation an epoch has.
 *
 * App policy on top of the library (conservative): geokit marks an UNKNOWN epoch `mayBeCompensated` only when the
 * output is declared ENABLED. With the setting still UNKNOWN, Emlid Flow could nevertheless be sending
 * compensated NMEA whenever the tilt engine is on, so such an epoch is also treated as [EpochReference.MAY_BE_COMPENSATED]
 * when the matched ETC shows the engine on (any state but OFF), or when the epoch could not be matched while the
 * recent ETC stream showed it on. Without any sign of the tilt engine (no ETC, or ETC says OFF) it stays
 * [EpochReference.ANTENNA_UNCERTAIN] / [EpochReference.ANTENNA] (today's maths).
 */
object PositionReferencePolicy {

    /** Profiles with an IMU that can stream ETC / compensated NMEA (Emlid documents tilt for RS4 and RS4 Pro). */
    fun appliesTo(profile: ExternalReceiverProfile?): Boolean =
        profile == ExternalReceiverProfile.REACH_RS4 || profile == ExternalReceiverProfile.REACH_RS4_PRO

    /** The geokit model of a profile (null for generic NMEA). */
    fun modelFor(profile: ExternalReceiverProfile?): EmlidReachModel? = when (profile) {
        ExternalReceiverProfile.REACH_RS2_PLUS -> EmlidReachModel.RS2_PLUS
        ExternalReceiverProfile.REACH_RS4 -> EmlidReachModel.RS4
        ExternalReceiverProfile.REACH_RS4_PRO -> EmlidReachModel.RS4_PRO
        ExternalReceiverProfile.GENERIC_NMEA_TCP, null -> null
    }

    /**
     * @param recentTiltOn the latest ETC (stream still live) showed the tilt engine on; used only when this
     *   epoch's own ETC could not be matched.
     */
    fun evaluate(
        profile: ExternalReceiverProfile?,
        output: CompensatedOutputSetting,
        firmware: String?,
        tilt: EmlidTiltAssociation,
        recentTiltOn: Boolean,
    ): EpochClassification {
        if (!appliesTo(profile)) return EpochClassification(EpochReference.NOT_APPLICABLE, null, tilt)
        val r = EmlidPositionReference.classify(modelFor(profile), output.toGeokit(), firmware, tilt)
        val ref = when (r.reference) {
            PositionReference.ANTENNA -> EpochReference.ANTENNA
            PositionReference.RECEIVER_COMPENSATED_POLE_TIP -> EpochReference.POLE_TIP
            PositionReference.UNKNOWN -> when {
                r.mayBeCompensated -> EpochReference.MAY_BE_COMPENSATED
                r.reason == EmlidPositionReference.Reason.OUTPUT_SETTING_UNKNOWN && tiltEngineMayBeOn(tilt, recentTiltOn) ->
                    EpochReference.MAY_BE_COMPENSATED
                else -> EpochReference.ANTENNA_UNCERTAIN
            }
        }
        return EpochClassification(ref, r, tilt)
    }

    /**
     * [evaluate] when the receiver profile could not be read (the capture's start snapshot failed): treated as an
     * IMU-capable receiver (geokit model unknown) with "Compensated NMEA output" UNKNOWN — the same conservative
     * rules as an RS4 / RS4 Pro with the setting Unknown. Never [EpochReference.POLE_TIP] (that needs the output
     * declared ENABLED); possibly compensated when the ETC shows the tilt engine on; otherwise antenna (uncertain),
     * i.e. today's maths.
     */
    fun evaluateUnknownProfile(firmware: String?, tilt: EmlidTiltAssociation, recentTiltOn: Boolean): EpochClassification {
        val r = EmlidPositionReference.classify(null, CompensatedOutputSetting.UNKNOWN.toGeokit(), firmware, tilt)
        return EpochClassification(mapUnknownOutput(r, tilt, recentTiltOn), r, tilt)
    }

    private fun mapUnknownOutput(r: EmlidPositionReference.Result, tilt: EmlidTiltAssociation, recentTiltOn: Boolean) =
        when (r.reference) {
            PositionReference.ANTENNA -> EpochReference.ANTENNA
            // Unreachable with output UNKNOWN; mapped defensively to "may be compensated", never to a pole tip.
            PositionReference.RECEIVER_COMPENSATED_POLE_TIP -> EpochReference.MAY_BE_COMPENSATED
            PositionReference.UNKNOWN ->
                if (r.mayBeCompensated || tiltEngineMayBeOn(tilt, recentTiltOn)) EpochReference.MAY_BE_COMPENSATED
                else EpochReference.ANTENNA_UNCERTAIN
        }

    private fun tiltEngineMayBeOn(tilt: EmlidTiltAssociation, recentTiltOn: Boolean): Boolean = when (tilt.match) {
        TiltMatch.MATCHED -> tilt.state != EmlidTiltState.OFF
        TiltMatch.NO_ETC_SEEN, TiltMatch.NO_FIX_TIME -> false
        TiltMatch.ETC_STREAM_STALE, TiltMatch.NOT_MATCHED, TiltMatch.AMBIGUOUS, TiltMatch.MALFORMED -> recentTiltOn
    }

    /**
     * True when [state] may mean the tilt engine is on (for [evaluate]'s `recentTiltOn`): any reading other than
     * OFF, including a malformed / undocumented one (conservative). Null = no ETC.
     */
    fun isTiltOn(state: EmlidTiltState?): Boolean = state != null && state != EmlidTiltState.OFF
}
