package com.example.surveyingapp.gnss.model

import java.time.Instant

/**
 * Single GNSS position/time observation normalized for the app.
 * Fields are intentionally explicit so UI doesn't guess at semantics.
 */
data class Fix(
    val provider: Provider,                // INTERNAL or RS2_EXTERNAL
    val timeUtc: Instant,                  // UTC from RMC/ZDA if external; else system time
    val timeSource: TimeSource,            // RMC | ZDA | GGA_DATE | SYSTEM
    val latDeg: Double,
    val lonDeg: Double,
    val altEllipsoidalM: Double?,          // May be null on poor phone fixes
    val altMslM: Double?,                  // If receiver provided or derived using geoid
    val geoidSeparationM: Double?,         // Positive means geoid below ellipsoid
    val hDop: Double?,                     // From GSA, optional
    val vDop: Double?,
    val pDop: Double?,
    val hAccM: Double?,                    // Horizontal 1-sigma if available (GST), else null
    val vAccM: Double?,                    // Vertical 1-sigma if available (GST), else null
    val rtkStatus: RtkStatus,              // NONE, DGPS, FLOAT, FIX
    val satsUsed: Int,                     // Count used in solution
    val satsVisible: Int?,                 // Total in view if known
    val diffAgeS: Double?,                 // Seconds since last correction if known
    val speedMps: Double?,                 // From RMC or fused
    val courseDeg: Double?                 // From RMC or fused
)

enum class TimeSource { RMC, ZDA, GGA_DATE, SYSTEM }
