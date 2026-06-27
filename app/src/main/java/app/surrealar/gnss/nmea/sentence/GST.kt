package app.surrealar.gnss.nmea.sentence

/**
 * $..GST: GPS Pseudorange Error Statistics
 * - timeRaw: UTC time of fix (hhmmss[.sss])
 * - rmsStdDev: RMS standard deviation of pseudorange residuals
 * - stdDevMajor: Standard deviation of semi-major axis (meters)
 * - stdDevMinor: Standard deviation of semi-minor axis (meters)
 * - orientationDeg: Orientation of semi-major axis (degrees from true north)
 * - stdDevLat: Standard deviation of latitude error (meters)
 * - stdDevLon: Standard deviation of longitude error (meters)
 * - stdDevAlt: Standard deviation of altitude error (meters)
 */
data class GST(
    override val talker: String,
    val timeRaw: String?,
    val rmsStdDev: Double?,
    val stdDevMajor: Double?,
    val stdDevMinor: Double?,
    val orientationDeg: Double?,
    val stdDevLat: Double?,
    val stdDevLon: Double?,
    val stdDevAlt: Double?
) : NmeaSentence {
    override val tag: String = "GST"
}
