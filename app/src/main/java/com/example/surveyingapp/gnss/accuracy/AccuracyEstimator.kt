package com.example.surveyingapp.gnss.accuracy

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import kotlin.math.sqrt

/**
 * Computes per-axis 1-sigma estimates.
 * Priority: use GST std devs if present; fallback to DOP×UERE.
 */
object AccuracyEstimator {
    fun estimate1SigmaMeters(fix: Fix, uereTable: UereTable): Pair<Double?, Double?> {
        // If GST provided, prefer it
        if (fix.hAccM != null || fix.vAccM != null) {
            return fix.hAccM to fix.vAccM
        }
        val uere = uereTable.uere(fix.provider, fix.rtkStatus)
        val h = fix.hDop?.let { it * uere }
        val v = fix.vDop?.let { it * uere }
        return h to v
    }
}
