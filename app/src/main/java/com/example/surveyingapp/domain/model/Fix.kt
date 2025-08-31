package com.example.surveyingapp.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.time.Duration

@Serializable
data class Fix(
    val lat: Double,
    val lon: Double,
    val altEllipsoidalM: Double?,
    val hAccM: Double? = null,
    val vAccM: Double? = null,
    @Deprecated("Use hAccM / vAccM instead")
    val accuracyM: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val satsUsed: Int? = null,
    val satsVisible: Int? = null,
    val hdop: Double? = null,
    val pdop: Double? = null,
    val rtkStatus: RtkStatus? = null,
    val timestamp: Instant,
    val timestampSource: TimestampSource? = null,
    val diffAge: Duration? = null,
    val baseStationId: String? = null,
    val baselineLengthM: Double? = null,
    val correctionSource: CorrectionSource? = null,
    val geoidSeparationM: Double? = null,
    val provider: Provider,
    val crsEpsg: Int? = 4326,
) {
    val isRtkFixed: Boolean get() = rtkStatus == RtkStatus.FIX
    val isHighPrecision: Boolean get() = isRtkFixed || (hAccM != null && hAccM <= 0.05)
    val altOrthometricM: Double? get() = if (altEllipsoidalM != null && geoidSeparationM != null) altEllipsoidalM - geoidSeparationM else null
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

sealed class LocationStatus {
    object Idle : LocationStatus()
    data class Connecting(val attempt: Int) : LocationStatus()
    object Streaming : LocationStatus()
    data class Error(val message: String, val recoverable: Boolean = true) : LocationStatus()
}
