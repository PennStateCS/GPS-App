package app.surrealar.gnss.settings

/**
 * Receiver tuning preferences.
 * - [highAccuracy] is RETIRED (2026-11-19): it only set the Google fused-location priority, which
 *   the app no longer uses (all location comes from the GNSS feed). Kept so stored settings still
 *   round-trip; nothing reads it and the Settings toggle was removed.
 * - [antennaHeightM] is the vertical offset (metres) of the EXTERNAL receiver's antenna phase centre
 *   above the survey mark — the pole/tripod height (plus any antenna-reference-point→phase-centre
 *   delta). Applied to external-receiver captures.
 * - [internalAntennaHeightM] is the same idea for the device's INTERNAL GNSS when the tablet is
 *   pole/bracket-mounted at a known height. Applied to internal-GPS captures.
 *
 * The external height defaults to **2.0 m** (a common pole height); the internal one defaults to
 * **0.0 m** because the tablet is usually handheld (a 2 m default lowered handheld captures by 2 m).
 * Defaults only apply when nothing is stored — a value the user saved is kept. The relevant offset
 * is SUBTRACTED from the captured altitude so a stored
 * coordinate's altitude is the ground mark, not the antenna. External and internal are separate
 * because the RS2 antenna and the tablet's antenna sit at different heights on the same pole.
 */
data class GnssReceiverSettings(
    val highAccuracy: Boolean = true,
    val antennaHeightM: Double = 2.0,
    val internalAntennaHeightM: Double = 0.0
)
