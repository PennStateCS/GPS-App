package com.example.surveyingapp.gnss.model

import java.time.Instant

/**
 * Single GNSS position/time observation normalized for the app.
 * Fields are intentionally explicit so UI doesn't guess at semantics.
 */
data class Fix(
    val provider: Provider,                         // GNSS data source: INTERNAL (phone) or RS2_EXTERNAL (external receiver)
    val timeUtc: Instant,                           // UTC timestamp of fix (from GNSS or system)
    val timestampSource: TimestampSource,           // Source of timestamp: DEVICE, NMEA_ZDA, GNSS_PROVIDER, or UNKNOWN
    val latDeg: Double,                             // Latitude in decimal degrees (WGS84)
    val lonDeg: Double,                             // Longitude in decimal degrees (WGS84)
    val altEllipsoidalM: Double?,                   // Altitude above ellipsoid (meters); may be null for poor fixes
    val altMslM: Double?,                           // Altitude above mean sea level (meters); may be null if not provided/derived
    val geoidSeparationM: Double?,                  // Geoid separation (meters); positive means geoid below ellipsoid
    val hDop: Double?,                              // Horizontal dilution of precision (from GSA sentence)
    val vDop: Double?,                              // Vertical dilution of precision (from GSA sentence)
    val pDop: Double?,                              // Position dilution of precision (from GSA sentence)
    val hAccM: Double?,                             // Horizontal accuracy (meters, 1-sigma); from GST or null
    val vAccM: Double?,                             // Vertical accuracy (meters, 1-sigma); from GST or null
    val rtkStatus: RtkStatus,                       // RTK status: NONE, DGPS, FLOAT, FIX
    val satsUsed: Int,                              // Number of satellites used in solution
    val satsVisible: Int?,                          // Total satellites in view (if known)
    val diffAgeS: Double?,                          // Age of differential correction (seconds, if known)
    val speedMps: Double?,                          // Speed over ground (meters/second); from RMC or fused
    val courseDeg: Double?,                         // Course over ground (degrees); from RMC or fused
    val correctionStationId: String? = null,        // Correction station ID (if available)
    val multipathIndex: Double? = null,             // Multipath index (if available)
    val rawNmeaSnapshot: List<String> = emptyList() // Raw NMEA sentences for this fix (if available)
    )
