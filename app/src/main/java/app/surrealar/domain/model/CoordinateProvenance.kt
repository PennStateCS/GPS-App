package app.surrealar.domain.model

import io.github.pennstatecs.geokit.convert.Epsg
import io.github.pennstatecs.geokit.datum.CoordinateEpoch

/**
 * Persisted tokens for the v14 coordinate provenance columns (`sourceFrame`, `receiverFrame`,
 * `coordinateEpoch`, `orthometricHeightSource`, `orthometricModel`). The strings are stored in Room and
 * in backups: never rename one, only add.
 *
 * NULL in any of these columns means **unknown** (legacy rows, manual/imported/model points). Readers
 * resolve unknown to today's behaviour (WGS84 treated as ITRF2014 at the observation `timestamp`, or
 * NAD83(2011) for `crsEpsg` 6318/6319); nothing is ever back-filled.
 */
object FrameTokens {
    /**
     * The app's legacy convention: receiver/ARCore "WGS84" coordinates taken to be ITRF2014. Equals
     * geokit `ReferenceFrame.WGS84_AS_ITRF2014.id`.
     */
    const val WGS84_AS_ITRF2014 = "wgs84-as-itrf2014"

    /** ITRF2014. Equals geokit `ReferenceFrame.ITRF2014.id`. */
    const val ITRF2014 = "itrf2014"

    /** NAD83(2011). Equals geokit `ReferenceFrame.NAD83_2011.id`. */
    const val NAD83_2011 = "nad83-2011"

    /**
     * Frame of a coordinate stored with [crsEpsg] (the datum the stored lat/lon/height are in):
     * WGS84 4326/4979 → [WGS84_AS_ITRF2014], NAD83(2011) 6318/6319 → [NAD83_2011], anything else
     * (including null) → null (unknown).
     */
    fun forStoredCrsEpsg(crsEpsg: Int?): String? = when {
        crsEpsg == Epsg.WGS84_3D || crsEpsg == Epsg.WGS84_2D -> WGS84_AS_ITRF2014
        Epsg.isNad83_2011(crsEpsg) -> NAD83_2011
        else -> null
    }
}

/** Provenance of a coordinate's `altitudeMsl` / `geoidSeparationM` (column `orthometricHeightSource`). */
object OrthometricHeightSource {
    /** An external receiver's NMEA GGA: its own geoid model, which the receiver does not report. */
    const val RECEIVER_GGA = "RECEIVER_GGA"

    /** The device's own GNSS (chip GGA or the platform MSL altitude): model not reported. */
    const val DEVICE_GNSS = "DEVICE_GNSS"

    /** Reserved: computed by the app from the ellipsoidal height with a known geoid model. */
    const val COMPUTED = "COMPUTED"
}

/** Geoid model of `altitudeMsl` when known (column `orthometricModel`); null = unknown. */
object OrthometricModel {
    const val GEOID18 = "GEOID18"
    const val EGM2008 = "EGM2008"
    const val EGM96 = "EGM96"
}

/**
 * Plausible stored coordinate epochs (decimal years). GNSS RTK data before 1980 or after 2100 is not a
 * real capture: such a value (or 0.0, NaN, ±Infinity from a damaged backup) is treated as unknown.
 */
val COORDINATE_EPOCH_RANGE: ClosedFloatingPointRange<Double> = 1980.0..2100.0

/** [epoch] if it is a finite decimal year within [COORDINATE_EPOCH_RANGE], else null (unknown). */
fun validCoordinateEpochOrNull(epoch: Double?): Double? =
    epoch?.takeIf { it.isFinite() && it in COORDINATE_EPOCH_RANGE }

/** Decimal-year coordinate epoch of an instant (epoch millis), as the feed's datum transform uses it. */
fun coordinateEpochOf(epochMillis: Long): Double = CoordinateEpoch.fromEpochMillis(epochMillis).decimalYear

/**
 * This coordinate with its horizontal/ellipsoidal provenance (`sourceFrame`, `receiverFrame`,
 * `coordinateEpoch`) cleared to unknown — for edits that replace the position with one of unknown
 * frame (e.g. "Use model location"). The orthometric fields describe `altitudeMsl`, which such edits
 * do not change, so they are kept.
 */
fun Coordinate.withPositionProvenanceUnknown(): Coordinate =
    copy(sourceFrame = null, receiverFrame = null, coordinateEpoch = null)
