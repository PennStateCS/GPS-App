package com.example.surveyingapp.gnss.settings

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.RtkStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.StateFlow

/** Acceptance gates for capture. */
data class CaptureSettings(
    val minSats: Int = 10,
    val maxPdop: Double = 3.0,
    val allowed: Set<RtkStatus> = setOf(RtkStatus.FIX, RtkStatus.FLOAT),
    val minDwell: Duration = 2.seconds
) {
    /** True if this fix passes current acceptance thresholds (ignores dwell/windowing). */
    fun accepts(fix: Fix): Boolean {
        val satsOk = (fix.satsUsed ?: 0) >= minSats
        val pdopOk = (fix.pDop ?: Double.POSITIVE_INFINITY) <= maxPdop
        val modeOk = fix.rtkStatus?.let { it in allowed } ?: false
        return satsOk && pdopOk && modeOk
    }
}

/** Per-mode UERE overrides in meters (1-sigma). */
data class UereOverrides(
    val forSingle: Double = 3.0,
    val forDgps: Double = 1.5,
    val forFloat: Double = 0.3,
    val forFix: Double = 0.02
)

/**
 * Accuracy knobs. Flows live in the repo; consumers can collect these.
 * If overrideUere = true, use values from uereOverrides; else fall back to your default table.
 */
data class AccuracySettings(
    val overrideUere: StateFlow<Boolean>,
    val uereOverrides: StateFlow<UereOverrides>
)
