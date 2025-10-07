package com.example.surveyingapp.gnss.accuracy

import com.example.surveyingapp.gnss.model.Fix

/**
 * Computes per-axis 1-sigma accuracy estimates in meters.
 * Priority:
 *   1. Use receiver-reported GST standard deviations (if available).
 *   2. Otherwise, fall back to DOP × UERE.
 */
object AccuracyEstimator {

    data class Accuracy1Sigma(
        val horizontalMeters: Double?,
        val verticalMeters: Double?
    )

    fun estimate1SigmaMeters(fix: Fix, uereTable: UereTable): Accuracy1Sigma {
        // Prefer GST if present
        if (fix.hAccM != null || fix.vAccM != null) {
            return Accuracy1Sigma(fix.hAccM, fix.vAccM)
        }

        // Fall back to DOP × UERE
        val uere = uereTable.uere(fix.provider, fix.rtkStatus)
        val h = fix.hDop?.times(uere)
        val v = fix.vDop?.times(uere)

        return Accuracy1Sigma(h, v)
    }
}
