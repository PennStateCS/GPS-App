package io.github.pennstatecs.geokit.gnss.height

import io.github.pennstatecs.geokit.gnss.accuracy.VerticalAccuracyStatus
import io.github.pennstatecs.geokit.gnss.accuracy.VerticalAccuracyValidity
import io.github.pennstatecs.geokit.gnss.model.Fix

/** How far the ellipsoidal height h = H + N of a fix (both from the same GGA) can be trusted. */
public enum class EllipsoidalHeightStatus {
    /** GGA carried H (field 9) and a non-zero N (field 11): h = H + N, one geoid term, from the same sentence. */
    RECONSTRUCTED,

    /** GGA carried no altitude: no height at all. */
    NO_ALTITUDE,

    /** GGA carried H but no geoid separation: h is unavailable (the fuser leaves it null). */
    NO_SEPARATION,

    /**
     * GGA carried H and a separation of exactly 0. Undecidable from NMEA: either field 9 is already the
     * ellipsoidal height (h = H + 0 is right), or it is an orthometric height whose separation was left
     * out (h = H + 0 is wrong by the geoid undulation, tens of metres in the US). Treat h as uncertain.
     */
    SEPARATION_ZERO_AMBIGUOUS,

    /** The vertical accuracy is an invalid marker (e.g. Emlid's 999 m): no height of this epoch is valid. */
    VERTICAL_ACCURACY_INVALID,
}

/**
 * The heights of one fix as the receiver reported them, kept apart from app-computed heights.
 *
 * @property receiverOrthometricM receiver-reported H (GGA field 9), in the receiver's geoid model.
 * @property receiverGeoidSeparationM receiver-reported N (GGA field 11).
 * @property receiverGeoidModel always null: NMEA 0183 GGA does not identify the geoid model, so the
 *   receiver's H is "model unknown". Never infer NAVD88, NAPGD2022 or any model from the stream.
 * @property ellipsoidalM h as the fuser computed it (H + N from the same GGA), null when unavailable.
 * @property ellipsoidalStatus whether [ellipsoidalM] can be trusted.
 * @property verticalAccuracy classification of the reported vertical accuracy.
 */
public data class ReceiverHeightAssessment(
    val receiverOrthometricM: Double?,
    val receiverGeoidSeparationM: Double?,
    val receiverGeoidModel: String?,
    val ellipsoidalM: Double?,
    val ellipsoidalStatus: EllipsoidalHeightStatus,
    val verticalAccuracy: VerticalAccuracyStatus,
) {
    /** True when h may be used as a valid elevation (reconstructed, and the vertical accuracy is not invalid). */
    val ellipsoidalTrusted: Boolean
        get() = ellipsoidalStatus == EllipsoidalHeightStatus.RECONSTRUCTED && ellipsoidalM != null
}

/**
 * Classifies the receiver heights of a fix. Pure; reads only what the fuser put on the [Fix] and changes
 * nothing (h = H + N from the same GGA stays the fuser's rule — one geoid term, never two).
 */
public object ReceiverHeights {

    public fun assess(fix: Fix): ReceiverHeightAssessment {
        val h = fix.altMslM
        val n = fix.geoidSeparationM
        val vacc = VerticalAccuracyValidity.classify(fix)
        val status = when {
            vacc == VerticalAccuracyStatus.INVALID -> EllipsoidalHeightStatus.VERTICAL_ACCURACY_INVALID
            h == null -> EllipsoidalHeightStatus.NO_ALTITUDE
            n == null -> EllipsoidalHeightStatus.NO_SEPARATION
            n == 0.0 -> EllipsoidalHeightStatus.SEPARATION_ZERO_AMBIGUOUS
            fix.altEllipsoidalM == null -> EllipsoidalHeightStatus.NO_SEPARATION
            else -> EllipsoidalHeightStatus.RECONSTRUCTED
        }
        return ReceiverHeightAssessment(
            receiverOrthometricM = h,
            receiverGeoidSeparationM = n,
            receiverGeoidModel = null,
            ellipsoidalM = fix.altEllipsoidalM,
            ellipsoidalStatus = status,
            verticalAccuracy = vacc,
        )
    }
}
