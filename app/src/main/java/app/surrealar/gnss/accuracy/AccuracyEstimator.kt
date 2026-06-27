package app.surrealar.gnss.accuracy

import app.surrealar.gnss.model.Fix

/**
 * Estimates 1-sigma position accuracy for a fix.
 *
 * Receiver-reported GST values are preferred because they come directly from the
 * GNSS solution. When GST is not available, the estimator falls back to a common
 * approximation: DOP multiplied by an expected UERE value for the fix type.
 */
object AccuracyEstimator {

    /** Horizontal/vertical 1-sigma accuracy in metres; either is null when it could not be estimated. */
    data class Accuracy1Sigma(
        val horizontalMeters: Double?,
        val verticalMeters: Double?
    )

    /**
     * Returns horizontal and vertical 1-sigma accuracy estimates in meters.
     *
     * The result may contain null values when the receiver did not report GST
     * accuracy and the fix does not include enough DOP data for a fallback estimate.
     */
    fun estimate1SigmaMeters(fix: Fix, uereTable: UereTable): Accuracy1Sigma {
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
        val uere = uereTable.uere(fix.provider, fix.rtkStatus)
        val horizontalMeters = fix.hDop?.times(uere)
        val verticalMeters = fix.vDop?.times(uere)

        return Accuracy1Sigma(horizontalMeters, verticalMeters)
    }
}