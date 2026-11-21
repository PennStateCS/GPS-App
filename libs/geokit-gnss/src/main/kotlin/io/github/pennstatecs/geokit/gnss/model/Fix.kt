package io.github.pennstatecs.geokit.gnss.model

import io.github.pennstatecs.geokit.gnss.receiver.AccuracyClass
import java.time.Instant

/**
 * Single GNSS position/time observation normalized for the app.
 * Fields are intentionally explicit so UI doesn't guess at semantics.
 *
 * Units are in the names (Deg, M, S, Mps). `null` always means "unknown / not reported", never 0.
 * Horizontal accuracy [hAccM] is a **68 % radius**; vertical accuracy [vAccM] and the per-axis
 * standard deviations are **1σ**.
 *
 * @property provider GNSS data source: [Provider.INTERNAL] (the device's own GNSS) or an external receiver.
 * @property timeUtc UTC time of the fix (GNSS time where available, else the device clock).
 * @property timestampSource where [timeUtc] came from.
 * @property latDeg latitude, decimal degrees, in the feed's datum (WGS84/ITRF for the app).
 * @property lonDeg longitude, decimal degrees, in the feed's datum.
 * @property altEllipsoidalM height above the ellipsoid, metres (= [altMslM] + [geoidSeparationM]).
 * @property altMslM height above mean sea level as reported (GGA), metres.
 * @property geoidSeparationM geoid separation N, metres; positive = geoid above the ellipsoid.
 * @property hDop horizontal dilution of precision (GGA).
 * @property vDop vertical dilution of precision (GSA).
 * @property pDop position dilution of precision (GSA).
 * @property hAccM horizontal accuracy, metres, as a 68 % radius (GST converted, or $..ACCURACY as reported).
 * @property vAccM vertical accuracy, metres, 1σ (GST altitude σ, or $..ACCURACY).
 * @property stdDevEastM east standard deviation, metres, 1σ (GST longitude σ).
 * @property stdDevNorthM north standard deviation, metres, 1σ (GST latitude σ).
 * @property stdDevUpM up standard deviation, metres, 1σ (GST altitude σ).
 * @property rtkStatus fix quality (NONE, DGPS, FLOAT, FIX, …).
 * @property satsUsed satellites used in the solution; null = not reported (never 0 for unknown).
 * @property satsVisible satellites in view, if known.
 * @property diffAgeS age of the differential corrections, seconds.
 * @property speedMps speed over ground, metres per second (RMC).
 * @property courseDeg course over ground, degrees true (RMC); null when the receiver leaves it blank.
 * @property correctionStationId differential/RTK base station ID, if reported.
 * @property multipathIndex multipath index, if reported.
 * @property rawNmeaSnapshot raw NMEA sentences behind this fix, if kept.
 * @property accuracyClass accuracy class of the receiver that produced the fix, stamped by the source
 *   from the active receiver profile; null = assume [AccuracyClass.defaultFor] the [provider].
 * @property receiverLabel human-readable receiver name from the active profile (e.g. "Emlid Reach RS2+");
 *   null = unknown (fall back to [Provider.label]).
 */
public data class Fix(
    val provider: Provider,
    val timeUtc: Instant,
    val timestampSource: TimestampSource,
    val latDeg: Double,
    val lonDeg: Double,
    val altEllipsoidalM: Double?,
    val altMslM: Double?,
    val geoidSeparationM: Double?,
    val hDop: Double?,
    val vDop: Double?,
    val pDop: Double?,
    val hAccM: Double?,
    val vAccM: Double?,
    val stdDevEastM: Double?,
    val stdDevNorthM: Double?,
    val stdDevUpM: Double?,
    val rtkStatus: RtkStatus,
    val satsUsed: Int?,
    val satsVisible: Int?,
    val diffAgeS: Double?,
    val speedMps: Double?,
    val courseDeg: Double?,
    val correctionStationId: String? = null,
    val multipathIndex: Double? = null,
    val rawNmeaSnapshot: List<String> = emptyList(),
    val accuracyClass: AccuracyClass? = null,
    val receiverLabel: String? = null,
    )
