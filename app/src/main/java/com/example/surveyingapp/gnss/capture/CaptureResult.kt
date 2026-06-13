package com.example.surveyingapp.gnss.capture

import com.example.surveyingapp.gnss.model.RtkStatus
import java.time.Instant

/**
 * The averaged position and quality snapshot produced by a completed capture session.
 *
 * This is the single handoff object from [ObservationSession] to storage and UI.
 * All fields that could not be determined during the session are nullable.
 */
data class CaptureResult(
    val startedAt: Instant,
    val endedAt: Instant,

    /** Number of GNSS epochs that were accepted and averaged. */
    val samples: Int,

    /** Averaged WGS84 position from the session. */
    val latDeg: Double,
    val lonDeg: Double,
    val altEllipsoidalM: Double,

    /** Empirical ECEF spread (X, Y, Z standard deviations in metres). Useful for audit logs. */
    val ecefStd: Triple<Double, Double, Double>,

    /** Quality snapshot from the last accepted epoch in the session. */
    val rtkStatus: RtkStatus?,
    val satsUsed: Int?,
    val satsVisible: Int?,
    val hdop: Double?,
    val vDop: Double?,
    val pDop: Double?,
    val hAccM: Double?,
    val vAccM: Double?,
    val diffAgeS: Double?,
    val correctionStationId: String?
)
