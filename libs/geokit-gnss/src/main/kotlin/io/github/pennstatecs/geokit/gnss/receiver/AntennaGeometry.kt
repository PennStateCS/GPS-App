package io.github.pennstatecs.geokit.gnss.receiver

/**
 * Antenna geometry of a pole-mounted GNSS receiver, for any make.
 *
 * Both heights are measured to the antenna reference point (ARP, usually the bottom of the receiver),
 * the way a tape measures them. The receiver reports its position at the antenna phase centre (APC),
 * [phaseCenterOffsetM] above the ARP, so that offset is added to each height.
 *
 * @property poleHeightM survey mark to ARP, metres.
 * @property cameraBelowArpM ARP to the device camera lens, vertical, metres; negative when the camera
 *   is above the ARP.
 * @property phaseCenterOffsetM ARP to APC, metres (0 when unknown and not entered).
 */
public data class AntennaGeometry(
    val poleHeightM: Double,
    val cameraBelowArpM: Double,
    val phaseCenterOffsetM: Double,
) {
    /** Mark to APC, metres: subtract from a captured height so the stored height is the mark. */
    val captureOffsetM: Double get() = poleHeightM + phaseCenterOffsetM

    /** How far the APC is above the camera, metres (AR placement, mock location). */
    val antennaAboveCameraM: Double get() = cameraBelowArpM + phaseCenterOffsetM

    public companion object {
        /**
         * Largest plausible phase-centre offset (ARP → APC), metres. This is a plausibility guard, not
         * a physical limit: real ARP-to-APC offsets for survey antennas are a few centimetres up to
         * about 0.15 m (Reach RS2/RS2+ 0.134 m, RS4 0.095 m), so a value above 0.5 m almost always
         * means a pole height was typed into the phase-centre field. Antennas with a genuinely larger
         * offset would need this raised deliberately.
         */
        public const val MAX_PHASE_CENTER_OFFSET_M: Double = 0.5
    }
}
