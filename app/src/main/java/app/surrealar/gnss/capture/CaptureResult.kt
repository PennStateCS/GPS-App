package app.surrealar.gnss.capture

import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.TimestampSource
import java.time.Instant

/**
 * Final averaged position and quality summary from a completed capture session.
 *
 * ObservationSession collects multiple accepted GNSS fixes, averages their position,
 * and packages the result into this object for storage and UI display.
 *
 * The position fields represent the averaged WGS84 result. The quality fields are
 * a snapshot from the last accepted fix, not an average across the whole session.
 * Fields are nullable when the receiver did not report that value during capture.
 */
data class CaptureResult(
    /** Time when the capture session began accepting fixes. */
    val startedAt: Instant,

    /** Time when the capture session finished. */
    val endedAt: Instant,

    /**
     * Number of accepted GNSS epochs included in the average.
     *
     * Rejected fixes, stale fixes, or fixes below the required RTK quality are not
     * included in this count.
     */
    val samples: Int,

    /**
     * Averaged WGS84 latitude in decimal degrees.
     */
    val latDeg: Double,

    /**
     * Averaged WGS84 longitude in decimal degrees.
     */
    val lonDeg: Double,

    /**
     * Averaged WGS84 ellipsoidal height in meters.
     *
     * This is height above the WGS84 ellipsoid, not height above mean sea level.
     * ARCore geospatial anchors expect this type of altitude.
     */
    val altEllipsoidalM: Double,

    /**
     * Empirical spread of the accepted samples in ECEF space.
     *
     * The values are standard deviations for Earth-Centered, Earth-Fixed X, Y,
     * and Z coordinates, in meters. This is useful for audit/debug output because
     * it shows how tightly the accepted samples clustered during averaging.
     */
    val ecefStd: Triple<Double, Double, Double>,

    /**
     * RTK quality from the last accepted fix.
     */
    val rtkStatus: RtkStatus?,

    /** Number of satellites used in the last accepted position solution. */
    val satsUsed: Int?,

    /** Number of satellites visible to the receiver in the last accepted sky snapshot. */
    val satsVisible: Int?,

    /** Horizontal dilution of precision from the last accepted fix. */
    val hdop: Double?,

    /** Vertical dilution of precision from the last accepted fix. */
    val vDop: Double?,

    /** Position dilution of precision from the last accepted fix. */
    val pDop: Double?,

    /**
     * Receiver-reported or estimated horizontal accuracy, in meters.
     */
    val hAccM: Double?,

    /**
     * Receiver-reported or estimated vertical accuracy, in meters.
     */
    val vAccM: Double?,

    /**
     * Age of differential or RTK correction data, in seconds.
     */
    val diffAgeS: Double?,

    /**
     * Identifier of the correction station used by the receiver, when reported.
     */
    val correctionStationId: String?,

    // ── Metadata carried over from the final accepted fix ───────────────────────
    // These come from the last accepted epoch (like the quality fields above) so the saved
    // coordinate keeps the height-reference and precision metadata that was available at capture.

    /** Mean-sea-level height (metres) of the final accepted fix, when reported. */
    val altMslM: Double? = null,

    /** Geoid separation (metres, ellipsoidal − MSL) of the final accepted fix, when reported. */
    val geoidSeparationM: Double? = null,

    /** Where the final accepted fix's timestamp came from (device clock, NMEA ZDA, …). */
    val timestampSource: TimestampSource? = null,

    /** Multipath indicator of the final accepted fix, when reported. */
    val multipathIndex: Double? = null,

    /** East 1-sigma standard deviation (metres) of the final accepted fix, when reported. */
    val stdDevEastM: Double? = null,

    /** North 1-sigma standard deviation (metres) of the final accepted fix, when reported. */
    val stdDevNorthM: Double? = null,

    /** Up 1-sigma standard deviation (metres) of the final accepted fix, when reported. */
    val stdDevUpM: Double? = null,
)