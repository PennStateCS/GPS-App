package app.surrealar.gnss.settings

/**
 * Receiver tuning preferences.
 * - [highAccuracy] requests the high-accuracy location mode where available.
 * - [antennaHeightM] is the vertical offset (metres) of the EXTERNAL receiver's antenna phase centre
 *   above the survey mark — the pole/tripod height (plus any antenna-reference-point→phase-centre
 *   delta). Applied to external-receiver captures.
 * - [internalAntennaHeightM] is the same idea for the device's INTERNAL GNSS when the tablet is
 *   pole/bracket-mounted at a known height. Applied to internal-GPS captures.
 *
 * Both default to **2.0 m** (a common pole height) until the user enters their own value; set to 0
 * for handheld use. The relevant offset is SUBTRACTED from the captured altitude so a stored
 * coordinate's altitude is the ground mark, not the antenna. External and internal are separate
 * because the RS2 antenna and the tablet's antenna sit at different heights on the same pole.
 */
data class GnssReceiverSettings(
    val highAccuracy: Boolean = true,
    val antennaHeightM: Double = 2.0,
    val internalAntennaHeightM: Double = 2.0
)
