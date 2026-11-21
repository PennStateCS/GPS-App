package app.surrealar.gnss.model

/** All supported GNSS constellations. UNKNOWN is used when the talker prefix is unrecognised. */
enum class Constellation { GPS, GLONASS, GALILEO, BEIDOU, QZSS, SBAS, IRNSS, UNKNOWN }

/**
 * Normalized satellite geometry used by the skyplot chart and signal-strength bars.
 *
 * Fields are derived from GSV and GSA sentences. The skyplot draws each satellite
 * at (azDeg, elDeg) and colours it by SNR; GSA provides the usedInFix flag.
 */
data class SkyGeometry(
    val svid: Int,                 // PRN/SVID assigned by the receiver
    val constellation: Constellation,
    val azDeg: Double?,            // Azimuth in degrees [0..360), null if not reported
    val elDeg: Double?,            // Elevation in degrees [-10..90], null if not reported
    val snrDbHz: Double?,          // Signal-to-noise ratio in dB-Hz (higher is better)
    val usedInFix: Boolean         // True if this satellite contributed to the current position solution
)

