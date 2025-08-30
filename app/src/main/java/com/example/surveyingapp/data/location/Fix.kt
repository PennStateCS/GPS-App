package com.example.surveyingapp.data.location

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.time.Duration

@Serializable
data class Fix(
    val lat: Double,                 // WGS84 latitude (deg)
    val lon: Double,                 // WGS84 longitude (deg)
    val altEllipsoidalM: Double?,    // Altitude relative to WGS84 ellipsoid (m)

    // Accuracy: prefer explicit horizontal/vertical; keep legacy 'accuracyM' for fused providers
    val hAccM: Double? = null,       // 1-sigma horizontal accuracy (m)
    val vAccM: Double? = null,       // 1-sigma vertical accuracy (m)
    val accuracyM: Double? = null,   // Legacy: fused providers often give a single accuracy (m)

    val speedMps: Double? = null,
    val bearingDeg: Double? = null,

    val satsUsed: Int? = null,
    val satsVisible: Int? = null,

    val hdop: Double? = null,
    val pdop: Double? = null,

    val rtkStatus: RtkStatus? = null,

    // Timing
    val timestamp: Instant,          // Replaces Long millis; use Instant.now() when building
    val timestampSource: TimestampSource? = null,

    // Corrections/RTK metadata
    val diffAge: Duration? = null,   // Age of corrections
    val baseStationId: String? = null,
    val baselineLengthM: Double? = null,
    val correctionSource: CorrectionSource? = null,

    // Heights
    val geoidSeparationM: Double? = null,   // N(h) = h(ellipsoid) - H(orthometric)

    // Provenance
    val provider: Provider,          // INTERNAL, RS2_TCP, RS2_BT, etc.

    // Optional: CRS code for future non-WGS84 support
    val crsEpsg: Int? = 4326,        // 4326 (WGS84) by default
) {
    /** True for fixed RTK solution. */
    val isRtkFixed: Boolean get() = rtkStatus == RtkStatus.FIX

    /** Heuristic: “survey-grade” if fixed or H-acc ≤ 0.05 m. Tune as needed. */
    val isHighPrecision: Boolean get() = isRtkFixed || (hAccM != null && hAccM <= 0.05)

    /** Orthometric height if geoid separation is available. */
    val altOrthometricM: Double? get() =
        if (altEllipsoidalM != null && geoidSeparationM != null) altEllipsoidalM - geoidSeparationM else null
}

@Serializable
enum class RtkStatus { FIX, FLOAT, DGPS, SINGLE, INVALID }

@Serializable
enum class Provider {
    INTERNAL,
    @SerialName("RS2_TCP") RS2_TCP,
    @SerialName("RS2_BT") RS2_BT,
    OTHER
}

@Serializable
enum class CorrectionSource { NTRIP, LORA, BASE_TCP, UNKNOWN }

@Serializable
enum class TimestampSource { DEVICE, NMEA_GGA, NMEA_RMC, SYSTEM }

/** Coarse-grained location streaming state for UI. */
sealed class LocationStatus {
    data object Idle : LocationStatus()
    data class Connecting(val attempt: Int) : LocationStatus()
    data object Streaming : LocationStatus()
    data class Error(val message: String, val recoverable: Boolean = true) : LocationStatus()
}
