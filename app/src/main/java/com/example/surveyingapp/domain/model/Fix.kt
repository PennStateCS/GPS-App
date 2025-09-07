@file:Suppress("unused")
@file:UseSerializers(
    InstantAsLongSerializer::class,
    DurationAsDoubleSecondsSerializer::class
)

package com.example.surveyingapp.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Serialize java.time.Instant as epoch milliseconds (Long).
 */
@Serializer(forClass = Instant::class)
object InstantAsLongSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("InstantEpochMillis", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.toEpochMilli())
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.ofEpochMilli(decoder.decodeLong())
    }
}

/**
 * Serialize kotlin.time.Duration as seconds with fractional part (Double).
 * Example: 1.25s -> 1.25
 */
@Serializer(forClass = Duration::class)
object DurationAsDoubleSecondsSerializer : KSerializer<Duration> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DurationSeconds", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Duration) {
        encoder.encodeDouble(value.toDouble(DurationUnit.SECONDS))
    }

    override fun deserialize(decoder: Decoder): Duration {
        val seconds = decoder.decodeDouble()
        return seconds.toDuration(DurationUnit.SECONDS)
    }
}

@Serializable
data class Fix(
    val lat: Double,
    val lon: Double,

    /** Height above the WGS-84 ellipsoid, meters. */
    val altEllipsoidalM: Double?,

    /** Horizontal accuracy (1-sigma) in meters, if available. */
    val hAccM: Double? = null,

    /** Vertical accuracy (1-sigma) in meters, if available. */
    val vAccM: Double? = null,

    /**
     * Deprecated: prefer hAccM / vAccM.
     * Marked transient so it no longer serializes.
     */
    @Deprecated("Use hAccM / vAccM instead")
    @kotlinx.serialization.Transient
    val accuracyM: Double? = null,

    /** Speed over ground (m/s), if available. */
    val speedMps: Double? = null,

    /**
     * 1-sigma uncertainty of speed (m/s), if available.
     * Populated on Android API 26+ fused location; null for NMEA/RS2+.
     */
    val speedAccMps: Double? = null,

    /** Course/heading over ground (degrees), if available. */
    val bearingDeg: Double? = null,

    /**
     * 1-sigma uncertainty of bearing/heading (degrees), if available.
     * Populated on Android API 26+ fused location; null for NMEA/RS2+.
     */
    val bearingAccDeg: Double? = null,

    /** Satellites used in solution (from GGA), if available. */
    val satsUsed: Int? = null,

    /** Satellites in view (from GSV), if available. */
    val satsVisible: Int? = null,

    /** HDOP/PDOP, if available. */
    val hdop: Double? = null,
    val pdop: Double? = null,

    /** RTK status classification mapped from NMEA. */
    val rtkStatus: RtkStatus? = null,

    /** Timestamp for this fix (UTC). */
    val timestamp: Instant,

    /** Origin of the timestamp (NMEA sentence vs system clock). */
    val timestampSource: TimestampSource? = null,

    /** Age of differential corrections (RTK/NTRIP), when provided by receiver. */
    val diffAge: Duration? = null,

    /** Base station identifier (from GGA), if present. */
    val baseStationId: String? = null,

    /** Baseline length (meters) if provided, else null. */
    val baselineLengthM: Double? = null,

    /** Source of corrections, if known. */
    val correctionSource: CorrectionSource? = null,

    /**
     * Geoid separation (meters). For NMEA GGA, MSL = ellipsoidal − geoidSeparation.
     * Positive in regions where the ellipsoid lies above mean sea level.
     */
    val geoidSeparationM: Double? = null,

    /** EPSG code of the geographic CRS for lat/lon (default 4326/WGS-84). */
    val crsEpsg: Int = 4326,

    /** Per-axis standard deviations (from NMEA GST) in meters, if present. */
    val stdLatM: Double? = null,
    val stdLonM: Double? = null,
    val stdAltM: Double? = null,

    /** Origin of this fix in the app (internal fused vs external RS2+, etc.). */
    val provider: Provider,
) {
    /** True when RTK fixed. */
    val isRtkFixed: Boolean get() = rtkStatus == RtkStatus.FIX

    /**
     * True when we have RTK fixed OR reported horizontal sigma ≤ 5 cm.
     * NOTE: phone providers can report optimistic values; you may also require isExternal.
     */
    val isHighPrecision: Boolean get() = isRtkFixed || (hAccM != null && hAccM <= 0.05)

    /** Orthometric (MSL) height in meters, when geoid separation is known. */
    val altOrthometricM: Double?
        get() = if (altEllipsoidalM != null && geoidSeparationM != null)
            altEllipsoidalM - geoidSeparationM
        else null

    /** Convenience alias for MSL height. */
    val altMslM: Double? get() = altOrthometricM

    /** Whether differential/RTK corrections were likely in play. */
    val hasCorrections: Boolean
        get() = when (rtkStatus) {
            RtkStatus.FIX, RtkStatus.FLOAT, RtkStatus.DGPS -> true
            else -> false
        }

    /** True if produced by an external receiver (RS2 over TCP/BT). */
    val isExternal: Boolean
        get() = provider == Provider.RS2_TCP || provider == Provider.RS2_BT

    /** Handy access to epoch milliseconds. */
    val timestampEpochMillis: Long get() = timestamp.toEpochMilli()
}

@Serializable
enum class RtkStatus { FIX, FLOAT, DGPS, SINGLE, INVALID }

@Serializable
enum class Provider {
    @SerialName("INTERNAL") INTERNAL,
    @SerialName("RS2_TCP") RS2_TCP,
    @SerialName("RS2_BT") RS2_BT,
    @SerialName("OTHER") OTHER
}

@Serializable
enum class CorrectionSource { NTRIP, LORA, BASE_TCP, UNKNOWN }

/**
 * Be sure all `when (timestampSource)` statements handle NMEA_ZDA too.
 */
@Serializable
enum class TimestampSource {
    /** Time came from the device (platform fused provider). */
    DEVICE,
    /** Time parsed from NMEA GGA. */
    NMEA_GGA,
    /** Time parsed from NMEA RMC (includes date). */
    NMEA_RMC,
    /** Time parsed from NMEA ZDA (year/month/day + time). */
    NMEA_ZDA,
    /** System clock (fallback). */
    SYSTEM,
}

/** Stream/engine status surfaced by the manager. */
sealed class LocationStatus {
    data object Idle : LocationStatus()
    data class Connecting(val attempt: Int) : LocationStatus()
    data object Streaming : LocationStatus()
    data class Error(val message: String, val recoverable: Boolean = true) : LocationStatus()
}
