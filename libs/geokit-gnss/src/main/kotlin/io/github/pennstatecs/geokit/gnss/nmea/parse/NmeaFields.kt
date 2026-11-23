package io.github.pennstatecs.geokit.gnss.nmea.parse

/**
 * Field helpers for writing a [SentenceParser] outside this library (e.g. a receiver module's
 * proprietary sentences). They are the same conversions the built-in GGA/RMC/GST parsers use, so a
 * vendor sentence reads coordinates and numbers exactly like the standard ones.
 */
public object NmeaFields {

    /**
     * NMEA latitude `ddmm.mmmm` plus `N`/`S` to decimal degrees (south negative), or null when the
     * field is blank or malformed, the hemisphere is missing or not N/S, minutes are 60 or more, or the
     * result is beyond 90°. The degree/minute split comes from the decimal point, so a missing leading
     * zero is tolerated.
     */
    public fun latitude(raw: String?, hemisphere: String?): Double? =
        NmeaCoordinateUtils.toDecimalDegrees(raw, hemisphere, isLatitude = true)

    /** As [latitude] for longitude `dddmm.mmmm` plus `E`/`W` (west negative, limit 180°). */
    public fun longitude(raw: String?, hemisphere: String?): Double? =
        NmeaCoordinateUtils.toDecimalDegrees(raw, hemisphere, isLatitude = false)

    /** A numeric field as a finite Double; null when blank, unparseable, NaN or infinite. */
    public fun finiteDouble(raw: String?): Double? = raw.toFiniteDoubleOrNull()
}
