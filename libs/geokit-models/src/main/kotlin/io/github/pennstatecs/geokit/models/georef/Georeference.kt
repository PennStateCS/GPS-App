package io.github.pennstatecs.geokit.models.georef

/** How sure we are that a model's baked-in coordinates are a real WGS84 location. */
public enum class GeoreferenceConfidence { HIGH, MEDIUM, LOW }

/** A geographic point found in (or used as the origin for) a georeferenced model. WGS84 degrees, metres. */
public data class GeoreferencedOrigin(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    /** The model's vertical value: metres, as exported (no datum conversion). */
    val heightM: Double,
)

/** Horizontal extent of the POSITION accessor min/max, in degrees (X = longitude, Z = -latitude). */
public data class PositionSpan(val spanX: Double, val spanZ: Double)

/**
 * The plausibility rules shared by the detector and the reprojector. The heuristic reads the first
 * POSITION accessor's min/max in the Metashape Y-up layout: X = longitude, Y = height (m), Z = -latitude.
 */
public object GeoreferenceRules {
    /** Method label stored with the location. */
    public const val METHOD: String = "GLB_POSITION_WGS_LIKE"

    public enum class Rejection { LONGITUDE_OUT_OF_RANGE, LATITUDE_OUT_OF_RANGE, HEIGHT_OUT_OF_RANGE, NULL_ISLAND, SPAN_TOO_LARGE }

    /**
     * Why ([lon], [lat], [height]) with these spans is not a plausible local georeferenced scan, or null
     * when it is: lon in ±180, lat in ±90, height in -1000..9000 m, not (0, 0), spans at most 0.05°.
     */
    public fun reject(lon: Double, lat: Double, height: Double, span: PositionSpan): Rejection? = when {
        lon !in -180.0..180.0 -> Rejection.LONGITUDE_OUT_OF_RANGE
        lat !in -90.0..90.0 -> Rejection.LATITUDE_OUT_OF_RANGE
        height !in -1000.0..9000.0 -> Rejection.HEIGHT_OUT_OF_RANGE
        // Degenerate (0,0) is null island, not a real export.
        kotlin.math.abs(lon) < 1e-6 && kotlin.math.abs(lat) < 1e-6 -> Rejection.NULL_ISLAND
        // Impossibly large for a local scan (> ~5 km).
        span.spanX > 0.05 || span.spanZ > 0.05 -> Rejection.SPAN_TOO_LARGE
        else -> null
    }

    /**
     * HIGH for spans of 1e-7..0.005° on both axes, else MEDIUM. (The detector keeps a LOW branch for
     * spans of 0.05° or more, which [reject] has already turned away, so LOW never occurs.)
     */
    internal fun confidence(span: PositionSpan, withLowBranch: Boolean): GeoreferenceConfidence = when {
        span.spanX in 1e-7..0.005 && span.spanZ in 1e-7..0.005 -> GeoreferenceConfidence.HIGH
        !withLowBranch -> GeoreferenceConfidence.MEDIUM
        span.spanX < 0.05 && span.spanZ < 0.05 -> GeoreferenceConfidence.MEDIUM
        else -> GeoreferenceConfidence.LOW
    }
}
