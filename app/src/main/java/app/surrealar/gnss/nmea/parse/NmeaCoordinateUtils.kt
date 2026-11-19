package app.surrealar.gnss.nmea.parse

/**
 * Utility functions for parsing NMEA coordinate formats (ddmm.mmmm and dddmm.mmmm)
 * and converting them to decimal degrees with proper hemisphere handling.
 */
object NmeaCoordinateUtils {

    /**
     * Converts NMEA latitude format (ddmm.mmmm) to decimal degrees.
     *
     * @param latitudeRaw Raw latitude string in ddmm.mmmm format (e.g., "4124.8963")
     * @param hemisphere Hemisphere indicator: "N" for North, "S" for South
     * @return Decimal degrees (positive for North, negative for South), or null if parsing fails
     *
     * Example: parseLatitude("4124.8963", "N") returns 41.41493833...
     *          parseLatitude("4124.8963", "S") returns -41.41493833...
     */
    fun parseLatitude(latitudeRaw: String?, hemisphere: String?): Double? {
        return parseCoordinate(latitudeRaw, hemisphere, isLatitude = true)
    }

    /**
     * Converts NMEA longitude format (dddmm.mmmm) to decimal degrees.
     *
     * @param longitudeRaw Raw longitude string in dddmm.mmmm format (e.g., "08151.6838")
     * @param hemisphere Hemisphere indicator: "E" for East, "W" for West
     * @return Decimal degrees (positive for East, negative for West), or null if parsing fails
     *
     * Example: parseLongitude("08151.6838", "E") returns 81.8614...
     *          parseLongitude("08151.6838", "W") returns -81.8614...
     */
    fun parseLongitude(longitudeRaw: String?, hemisphere: String?): Double? {
        return parseCoordinate(longitudeRaw, hemisphere, isLatitude = false)
    }

    /**
     * Lenient (d)ddmm.mmmm → decimal degrees used by the sentence parsers (GGA/RMC/EBP).
     *
     * The degrees/minutes split is found from the DECIMAL POINT (minutes are always the two digits
     * before it), not from a fixed degree width. Some receivers — e.g. the internal GNSS on Samsung
     * SM-X940 — omit the leading zero on longitude ("7601.3509" instead of "07601.3509"); a fixed
     * 3-digit split read that as 760° 1.35' → −760.02°. Results outside the valid range
     * (minutes ≥ 60, |lat| > 90, |lon| > 180) return null rather than an impossible coordinate.
     *
     * @param hemisphere N/S (latitude) or E/W (longitude); null is treated as positive (N/E), any
     *   other value returns null — matching the parsers' historical behaviour.
     */
    fun toDecimalDegrees(raw: String?, hemisphere: String?, isLatitude: Boolean): Double? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        val dot = v.indexOf('.').let { if (it < 0) v.length else it }
        if (dot < 3) return null // need at least d + mm
        val deg = v.substring(0, dot - 2).toIntOrNull() ?: return null
        val min = v.substring(dot - 2).toDoubleOrNull() ?: return null
        if (deg < 0 || min < 0.0 || min >= 60.0) return null
        var value = deg + min / 60.0
        if (value > (if (isLatitude) 90.0 else 180.0)) return null
        val (pos, neg) = if (isLatitude) "N" to "S" else "E" to "W"
        when (hemisphere) {
            neg -> value = -value
            pos, null -> { /* keep + */ }
            else -> return null
        }
        return value
    }

    /**
     * Generic function to parse NMEA coordinate format to decimal degrees.
     *
     * @param coordinateRaw Raw coordinate string in (d)ddmm.mmmm format
     * @param hemisphere Hemisphere indicator (N/S for latitude, E/W for longitude)
     * @param isLatitude true for latitude (ddmm.mmmm), false for longitude (dddmm.mmmm)
     * @return Decimal degrees with proper sign based on hemisphere, or null if parsing fails
     */
    private fun parseCoordinate(coordinateRaw: String?, hemisphere: String?, isLatitude: Boolean): Double? {
        // Validate inputs
        if (coordinateRaw.isNullOrEmpty() || hemisphere.isNullOrEmpty()) {
            return null
        }

        // Validate hemisphere indicators
        val validHemispheres = if (isLatitude) setOf("N", "S") else setOf("E", "W")
        if (hemisphere !in validHemispheres) {
            return null
        }

        try {
            val coordinate = coordinateRaw.toDouble()

            // Validate coordinate range
            val maxDegrees = if (isLatitude) 90.0 else 180.0
            if (coordinate >= maxDegrees * 100) {
                return null // Invalid coordinate range
            }

            // Extract degrees and minutes
            // For latitude: ddmm.mmmm format (degrees can be 0-89)
            // For longitude: dddmm.mmmm format (degrees can be 0-179)
            val degrees = (coordinate / 100).toInt()
            val minutes = coordinate - (degrees * 100)

            // Validate minutes range (0-59.9999...)
            if (minutes >= 60.0) {
                return null
            }

            // Convert to decimal degrees
            var decimalDegrees = degrees + (minutes / 60.0)

            // Apply hemisphere sign (negative for South/West)
            if (hemisphere == "S" || hemisphere == "W") {
                decimalDegrees = -decimalDegrees
            }

            return decimalDegrees
        } catch (e: NumberFormatException) {
            return null
        }
    }
}
