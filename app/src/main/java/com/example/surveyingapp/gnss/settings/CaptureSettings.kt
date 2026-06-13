package com.example.surveyingapp.gnss.settings

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.RtkStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.StateFlow

/** Thresholds that a fix must satisfy before it is accepted into a capture session. */
data class CaptureSettings(
    val minSats: Int = 10,
    val maxPdop: Double = 3.0,
    val allowed: Set<RtkStatus> = setOf(RtkStatus.FIX, RtkStatus.FLOAT),
    val minDwell: Duration = 2.seconds
) {
    /**
     * Returns true if [fix] passes all quality thresholds.
     * Does not consider dwell time or windowing — the session handles those separately.
     */
    fun accepts(fix: Fix): Boolean {
        val satsOk = (fix.satsUsed ?: 0) >= minSats
        val pdopOk = (fix.pDop ?: Double.POSITIVE_INFINITY) <= maxPdop
        val modeOk = fix.rtkStatus?.let { it in allowed } ?: false
        return satsOk && pdopOk && modeOk
    }
}

/** Per-mode UERE overrides in metres (1-sigma). Affects DOP-based accuracy fallback estimates. */
data class UereOverrides(
    val forSingle: Double = 3.0,
    val forDgps: Double = 1.5,
    val forFloat: Double = 0.3,
    val forFix: Double = 0.02
)

/**
 * Accuracy configuration knobs exposed as StateFlows so UI can toggle them live.
 * When [overrideUere] is true the [uereOverrides] values are used instead of the
 * built-in defaults in [UereTable].
 */
data class AccuracySettings(
    val overrideUere: StateFlow<Boolean>,
    val uereOverrides: StateFlow<UereOverrides>
)
