package io.github.pennstatecs.geokit.receiver.emlid.tilt

import io.github.pennstatecs.geokit.receiver.emlid.nmea.ETC
import io.github.pennstatecs.geokit.receiver.emlid.nmea.EtcNotification
import io.github.pennstatecs.geokit.receiver.emlid.nmea.EtcStateCode

/**
 * Tilt-compensation state of an Emlid Reach RS4 / RS4 Pro, from its `$..ETC` sentence.
 *
 * Mapping from the documented ETC state field (ETC_rev.3_RS4.pdf):
 *
 * | ETC state | [EmlidTiltState] |
 * |---|---|
 * | empty (off) | [OFF] |
 * | 10 setup | [INITIALIZING] |
 * | 20 alignment (also re-entered during work, e.g. after bad GNSS) | [ALIGNING] |
 * | 30 compensating | [ACTIVE] (check [EmlidTiltReading.notification] for warnings) |
 * | 0 fatal error | [UNAVAILABLE] |
 * | undocumented / malformed / stale / not time-matched | [UNKNOWN] |
 *
 * "Temporarily lost" (Emlid Flow's orange "Move the receiver" after a brief FIX loss) is not a separate
 * ETC state; the PDF says the engine returns to alignment, so it reads as [ALIGNING] — an assumption to
 * confirm with a receiver.
 */
public enum class EmlidTiltState {
    /** Tilt compensation turned off in the receiver. */
    OFF,
    /** Tilt engine setup after boot (ETC 10). */
    INITIALIZING,
    /** IMU alignment: the user must move the receiver (ETC 20). */
    ALIGNING,
    /** Compensating: heading, tilt and accuracy coefficients are valid (ETC 30). */
    ACTIVE,
    /** Fatal error: no compensation until the receiver is rebooted (ETC 0). */
    UNAVAILABLE,
    /** No trustworthy ETC for this position. */
    UNKNOWN,
}

/**
 * One decoded ETC epoch.
 *
 * @property state tilt state ([EmlidTiltState.UNKNOWN] for a malformed or undocumented sentence).
 * @property notification documented notification (null when malformed).
 * @property timeOfFixSecondsOfDay the ETC's UTC time-of-fix, seconds of day; null when the field is empty.
 * @property headingDeg heading, degrees (ACTIVE only).
 * @property tiltDirectionDeg tilt direction, degrees (ACTIVE only).
 * @property tiltDeg tilt from vertical, degrees (ACTIVE only).
 * @property accuracyCoefficientN n-axis accuracy coefficient (ACTIVE only; × antenna height = mm).
 * @property accuracyCoefficientE e-axis accuracy coefficient (ACTIVE only).
 * @property accuracyCoefficientU u-axis accuracy coefficient (ACTIVE only).
 * @property malformedReason why the ETC was not usable, or null.
 */
public data class EmlidTiltReading(
    val state: EmlidTiltState,
    val notification: EtcNotification?,
    val timeOfFixSecondsOfDay: Double?,
    val headingDeg: Double?,
    val tiltDirectionDeg: Double?,
    val tiltDeg: Double?,
    val accuracyCoefficientN: Double?,
    val accuracyCoefficientE: Double?,
    val accuracyCoefficientU: Double?,
    val malformedReason: String?,
) {
    /** ACTIVE with a notification other than none (fast motion, bad GNSS, filter fault, undocumented). */
    public val hasWarning: Boolean
        get() = state == EmlidTiltState.ACTIVE && notification != EtcNotification.NONE

    public companion object {
        /** Reads one ETC sentence. Never throws. */
        public fun from(etc: ETC): EmlidTiltReading {
            val d = etc.decoded
            val state = if (!d.isWellFormed) EmlidTiltState.UNKNOWN else when (d.state) {
                EtcStateCode.OFF -> EmlidTiltState.OFF
                EtcStateCode.SETUP -> EmlidTiltState.INITIALIZING
                EtcStateCode.ALIGNMENT -> EmlidTiltState.ALIGNING
                EtcStateCode.COMPENSATING -> EmlidTiltState.ACTIVE
                EtcStateCode.FATAL_ERROR -> EmlidTiltState.UNAVAILABLE
                EtcStateCode.UNRECOGNIZED -> EmlidTiltState.UNKNOWN
            }
            return EmlidTiltReading(
                state = state,
                notification = d.notification.takeIf { d.isWellFormed },
                timeOfFixSecondsOfDay = d.timeOfFixSecondsOfDay,
                headingDeg = d.headingDeg,
                tiltDirectionDeg = d.tiltDirectionDeg,
                tiltDeg = d.tiltDeg,
                accuracyCoefficientN = d.accuracyCoefficientN,
                accuracyCoefficientE = d.accuracyCoefficientE,
                accuracyCoefficientU = d.accuracyCoefficientU,
                malformedReason = d.malformedReason,
            )
        }
    }
}
