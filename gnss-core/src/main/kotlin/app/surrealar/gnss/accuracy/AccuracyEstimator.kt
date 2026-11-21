package app.surrealar.gnss.accuracy

import app.surrealar.gnss.model.Fix

/**
 * Estimates position accuracy for a fix: horizontal as a 68 % radius (app-wide meaning of hAccM,
 * see [app.surrealar.geo.accuracy.HorizontalAccuracy]), vertical as 1σ.
 *
 * Receiver-reported values are preferred (GST is already converted to a 68 % radius in NmeaFuser;
 * $..ACCURACY and Android Location.accuracy are 68 % radii as reported). When none is available,
 * the estimator falls back to DOP × UERE for the fix type; HDOP × UERE is a horizontal RMS, scaled
 * by 1.515/√2 ≈ 1.071 to a 68 % radius.
 */
public object AccuracyEstimator {

    /**
     * Horizontal accuracy (68 % radius) and vertical accuracy (1σ) in metres; either is null when it
     * could not be estimated. (Name kept for compatibility — the horizontal is not a plain 1σ.)
     */
    public data class Accuracy1Sigma(
        val horizontalMeters: Double?,
        val verticalMeters: Double?
    )

    /**
     * Returns the horizontal accuracy (68 % radius) and vertical accuracy (1σ) in metres for [fix],
     * falling back to DOP × UERE from [uereTable] when the receiver reported none.
     *
     * The result may contain null values when the receiver did not report GST
     * accuracy and the fix does not include enough DOP data for a fallback estimate.
     */
    public fun estimate1SigmaMeters(fix: Fix, uereTable: UereTable): Accuracy1Sigma {
        /*
         * GST is the best available source because it reflects the receiver's own
         * solution statistics. Preserve partial GST data if only one axis is present.
         */
        if (fix.hAccM != null || fix.vAccM != null) {
            return Accuracy1Sigma(fix.hAccM, fix.vAccM)
        }

        /*
         * Fall back to DOP × UERE when receiver-reported accuracy is missing.
         * This is an estimate, not a replacement for receiver-provided error stats.
         */
        // No position solution (NONE/INVALID) → no estimate, rather than a number.
        val uere = uereTable.uere(fix.provider, fix.rtkStatus) ?: return Accuracy1Sigma(null, null)
        // HDOP × UERE is already a horizontal RMS (≈ 1.41σ per axis), so only the RMS → 68 %-radius
        // step applies (×1.515/√2 ≈ 1.071) to agree with GST, $..ACCURACY and Location.accuracy.
        // VDOP × UERE stays a 1σ vertical, like every other vertical source.
        val horizontalMeters = fix.hDop?.let { app.surrealar.geo.accuracy.HorizontalAccuracy.r68FromHorizontalRms(it * uere) }
        val verticalMeters = fix.vDop?.times(uere)

        return Accuracy1Sigma(horizontalMeters, verticalMeters)
    }
}