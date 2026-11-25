package app.surrealar.settings.model

import io.github.pennstatecs.geokit.receiver.emlid.tilt.CompensatedNmeaOutput

/**
 * Settings → Receiver → "Compensated NMEA output (Emlid Flow)": whether the Reach RS4 / RS4 Pro streams
 * tilt-compensated (pole-tip) NMEA to this app. The setting lives in Emlid Flow and does not show in the
 * NMEA stream, so the user declares it here. Only used for the RS4 / RS4 Pro profiles.
 *
 * Persisted by [prefKey] in its own DataStore key (`external_compensated_nmea_output`); never rename the keys.
 * Default [UNKNOWN]: the app then never assumes a pole tip, and refuses epochs that may be compensated.
 */
enum class CompensatedOutputSetting(val prefKey: String, val label: String) {
    UNKNOWN("unknown", "Unknown"),
    ENABLED("enabled", "Enabled"),
    DISABLED("disabled", "Disabled");

    /** The geokit-receiver-emlid value [io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference] takes. */
    fun toGeokit(): CompensatedNmeaOutput = when (this) {
        UNKNOWN -> CompensatedNmeaOutput.UNKNOWN
        ENABLED -> CompensatedNmeaOutput.ENABLED
        DISABLED -> CompensatedNmeaOutput.DISABLED
    }

    companion object {
        val DEFAULT = UNKNOWN

        /** Persisted key → value; unknown / blank → [DEFAULT]. */
        fun fromPrefKey(key: String?): CompensatedOutputSetting = entries.firstOrNull { it.prefKey == key } ?: DEFAULT
    }
}
