package io.github.pennstatecs.geokit.gnss.receiver

import io.github.pennstatecs.geokit.gnss.model.Provider

/**
 * Accuracy class of the receiver that produced a fix. Selects the UERE row used when the receiver
 * reports no accuracy of its own (DOP × UERE fallback, see io.github.pennstatecs.geokit.gnss.accuracy.UereTable).
 */
public enum class AccuracyClass {
    /** Multi-band survey receiver (e.g. Emlid Reach RS2+/RS4): ionosphere largely removed, RTK capable. */
    SURVEY_MULTIBAND,

    /** Single-frequency receiver of unknown quality (the conservative choice for generic NMEA). */
    SINGLE_FREQUENCY,

    /** The device's own GNSS (tablet/phone class: small antenna, strong multipath). */
    DEVICE;

    public companion object {
        /**
         * Class assumed for a fix that carries none ([io.github.pennstatecs.geokit.gnss.model.Fix.accuracyClass] is
         * null): external connections → [SURVEY_MULTIBAND], everything else → [DEVICE]. These are the
         * rows each provider used before accuracy classes existed, so unstamped fixes (older code
         * paths, tests) estimate exactly as before.
         */
        public fun defaultFor(provider: Provider): AccuracyClass = when (provider) {
            Provider.EXTERNAL_TCP, Provider.EXTERNAL_BT -> SURVEY_MULTIBAND
            Provider.INTERNAL, Provider.REPLAY, Provider.MODEL, Provider.OTHER -> DEVICE
        }
    }
}

/** Receiver manufacturer, as far as the app distinguishes them. */
public enum class ReceiverVendor { EMLID, GENERIC }

/**
 * What a receiver can report beyond its NMEA stream. All false for a plain NMEA source.
 *
 * @property hasBattery reports battery level / charging.
 * @property hasDeviceStatus reports model, firmware, storage, uptime …
 * @property hasCorrectionsStatus reports the correction (NTRIP/LoRa) link and base station.
 * @property supportsDiscovery can be found on the local network.
 */
public data class ReceiverCapabilities(
    val hasBattery: Boolean = false,
    val hasDeviceStatus: Boolean = false,
    val hasCorrectionsStatus: Boolean = false,
    val supportsDiscovery: Boolean = false,
) {
    public companion object {
        /** No capabilities beyond NMEA. */
        public val NONE: ReceiverCapabilities = ReceiverCapabilities()

        /** Everything. */
        public val ALL: ReceiverCapabilities = ReceiverCapabilities(true, true, true, true)
    }
}
