package app.surrealar.settings.model

import app.surrealar.gnss.settings.GnssReceiverSettings

/**
 * The external receiver's antenna geometry, resolved for the selected receiver profile.
 *
 * Users measure both heights to the bottom of the receiver (the antenna reference point), as with a
 * tape. The receiver reports its position at the antenna phase centre, [phaseCenterOffsetM] higher,
 * so that offset is added here: to the pole height for captures, and to the camera distance for AR.
 * Pure (no Android) so the arithmetic is unit-tested in one place.
 */
data class AntennaSetup(
    /** Survey mark → bottom of the receiver (m). */
    val poleHeightM: Double,
    /** Bottom of the receiver → tablet camera lens, vertical (m); negative when the camera is higher. */
    val cameraBelowReceiverM: Double,
    /** Bottom of the receiver → antenna phase centre (m). */
    val phaseCenterOffsetM: Double,
    /** True when [phaseCenterOffsetM] comes from the receiver profile, false when the user entered it. */
    val offsetFromProfile: Boolean,
) {
    /** Subtracted from an external-receiver capture's altitude so the stored height is the mark. */
    val captureOffsetM: Double get() = poleHeightM + phaseCenterOffsetM

    /** How far the antenna phase centre is above the tablet camera (AR, mock location). */
    val antennaAboveCameraM: Double get() = cameraBelowReceiverM + phaseCenterOffsetM

    companion object {
        /** Largest accepted phase-centre offset; anything above is almost certainly a typo. */
        const val MAX_PHASE_CENTER_OFFSET_M = 0.5

        fun resolve(settings: GnssReceiverSettings, profile: ExternalReceiverProfile?): AntennaSetup {
            val override = settings.phaseCenterOffsetOverrideM
            val fromProfile = profile?.phaseCenterOffsetM
            return AntennaSetup(
                poleHeightM = settings.poleHeightM,
                cameraBelowReceiverM = settings.cameraBelowReceiverM,
                phaseCenterOffsetM = override ?: fromProfile ?: 0.0,
                offsetFromProfile = override == null && fromProfile != null,
            )
        }
    }
}
