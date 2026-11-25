package io.github.pennstatecs.geokit.gnss.accuracy

import io.github.pennstatecs.geokit.gnss.model.Fix

/** Whether a fix's reported vertical accuracy can be used. */
public enum class VerticalAccuracyStatus {
    /** A finite, positive value below [VerticalAccuracyValidity.INVALID_AT_OR_ABOVE_M]. */
    VALID,

    /** The receiver reported no vertical accuracy (no GST altitude σ, no $..ACCURACY). */
    NOT_REPORTED,

    /**
     * A sentinel or impossible value: non-finite, ≤ 0, or ≥ [VerticalAccuracyValidity.INVALID_AT_OR_ABOVE_M].
     * Emlid documents "altitude accuracy is marked as invalid (999m)" when receiver-side orthometric
     * (geoid) corrections are interrupted for more than 10 s. The height of such an epoch must not be
     * treated as a valid elevation, and the value must never be shown or stored as an accuracy.
     */
    INVALID,
}

/**
 * Classifies the receiver-reported vertical accuracy of a fix ([Fix.vAccM] and [Fix.stdDevUpM], both 1σ).
 *
 * Pure. It changes nothing in parsing or fusion: the fuser still reports what the receiver sent; this only
 * tells consumers (capture, status) when that value is a "height invalid" marker rather than an accuracy.
 */
public object VerticalAccuracyValidity {

    /**
     * Values at or above this (metres, 1σ) are invalid markers. Emlid's documented marker is 999 m; no
     * GNSS solution that still has a position reports a 500 m 1σ vertical, so the margin catches similar
     * sentinels (e.g. 999.9, 9999) without touching any real accuracy.
     */
    public const val INVALID_AT_OR_ABOVE_M: Double = 500.0

    /** Classifies one vertical accuracy value; null → [VerticalAccuracyStatus.NOT_REPORTED]. */
    public fun classify(vAccM: Double?): VerticalAccuracyStatus = when {
        vAccM == null -> VerticalAccuracyStatus.NOT_REPORTED
        !vAccM.isFinite() || vAccM <= 0.0 || vAccM >= INVALID_AT_OR_ABOVE_M -> VerticalAccuracyStatus.INVALID
        else -> VerticalAccuracyStatus.VALID
    }

    /**
     * Classifies [fix]: INVALID when either [Fix.vAccM] or [Fix.stdDevUpM] is invalid, VALID when at least
     * one is valid and neither is invalid, NOT_REPORTED otherwise.
     */
    public fun classify(fix: Fix): VerticalAccuracyStatus {
        val a = classify(fix.vAccM)
        val b = classify(fix.stdDevUpM)
        return when {
            a == VerticalAccuracyStatus.INVALID || b == VerticalAccuracyStatus.INVALID -> VerticalAccuracyStatus.INVALID
            a == VerticalAccuracyStatus.VALID || b == VerticalAccuracyStatus.VALID -> VerticalAccuracyStatus.VALID
            else -> VerticalAccuracyStatus.NOT_REPORTED
        }
    }

    /** True when the fix's vertical accuracy is an invalid marker (see [VerticalAccuracyStatus.INVALID]). */
    public fun isInvalid(fix: Fix): Boolean = classify(fix) == VerticalAccuracyStatus.INVALID
}
