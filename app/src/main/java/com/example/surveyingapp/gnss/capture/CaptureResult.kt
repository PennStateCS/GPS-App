package com.example.surveyingapp.gnss.capture

import com.example.surveyingapp.gnss.model.RtkStatus
import java.time.Instant

/**
 * Averaged point and the key survey/timing/quality fields needed for persistence.
 * This is the single handoff object from the capture engine to storage/UI.
 */
data class CaptureResult(
    // When the averaging started/ended (UTC)
    val startedAt: Instant,
    val endedAt: Instant,

    // How many epochs were used
    val samples: Int,

    // Final averaged position (WGS84)
    val latDeg: Double,
    val lonDeg: Double,
    val altEllipsoidalM: Double,

    // Empirical spread in ECEF (for audits; optional to display)
    val ecefStd: Triple<Double, Double, Double>,

    // --- New: survey quality snapshot pulled from the last good epoch ---
    val rtkStatus: RtkStatus?,        // FIX/FLOAT/DGPS/NONE (null if not known)
    val satsUsed: Int?,               // satellites in solution at capture completion
    val hdop: Double?,                // from GSA if available at completion
    val hAccM: Double?,               // 1-sigma horizontal (GST preferred, else DOP×UERE)
    val vAccM: Double?,               // 1-sigma vertical
    val diffAgeS: Double?             // corrections age in seconds, if provided by receiver
)
