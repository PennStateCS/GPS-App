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
