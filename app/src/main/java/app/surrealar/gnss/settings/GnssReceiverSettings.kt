package app.surrealar.gnss.settings

/**
 * Receiver tuning preferences.
 * - [highAccuracy] is RETIRED (2026-11-19): it only set the Google fused-location priority, which
 *   the app no longer uses (all location comes from the GNSS feed). Kept so stored settings still
 *   round-trip; nothing reads it and the Settings toggle was removed.
 * - [poleHeightM] is the EXTERNAL receiver's pole/tripod height: survey mark → bottom of the
 *   receiver (the antenna reference point), as measured with a tape. The receiver model's
 *   phase-centre offset is added on top (see [app.surrealar.settings.model.AntennaSetup]); before
 *   2026-11 this field (key `antenna_height_m`) was the height to the phase centre itself.
 * - [cameraBelowReceiverM] is the vertical distance from the bottom of the EXTERNAL receiver down to
 *   the tablet camera lens (negative when the camera is higher). AR uses it with the phase-centre
 *   offset to put the camera at the right height.
 * - [phaseCenterOffsetOverrideM] replaces the receiver profile's phase-centre offset; null = use the
 *   profile's value.
 * - [poleHeightNeedsCheck] is true for installs that stored a pole height under the old meaning,
 *   until the user saves or confirms it.
 * - [internalAntennaHeightM] is the same idea for the device's INTERNAL GNSS when the tablet is
 *   pole/bracket-mounted at a known height. Applied to internal-GPS captures.
 *
 * The external pole height defaults to **2.0 m** (a common pole height); the internal one defaults to
 * **0.0 m** because the tablet is usually handheld (a 2 m default lowered handheld captures by 2 m).
 * Defaults only apply when nothing is stored — a value the user saved is kept. The relevant offset
 * is SUBTRACTED from the captured altitude so a stored
 * coordinate's altitude is the ground mark, not the antenna. External and internal are separate
 * because the RS2 antenna and the tablet's antenna sit at different heights on the same pole.
 */
data class GnssReceiverSettings(
    val highAccuracy: Boolean = true,
    val poleHeightM: Double = 2.0,
    val internalAntennaHeightM: Double = 0.0,
    val cameraBelowReceiverM: Double = 0.0,
    val phaseCenterOffsetOverrideM: Double? = null,
    val poleHeightNeedsCheck: Boolean = false,
)
