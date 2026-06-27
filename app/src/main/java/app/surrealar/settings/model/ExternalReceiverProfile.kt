package app.surrealar.settings.model

/**
 * Selectable external-receiver *profile*. This is metadata only — every profile uses the same live
 * External TCP NMEA pipeline (TcpNmeaSource → ExternalAdapter → NmeaFuser → FixSwitchboard). A
 * profile just carries sensible defaults and display labels; it does NOT create a new GNSS source.
 *
 * Persisted by [prefKey] (a stable string) in DataStore; never rename the keys. Default is
 * [REACH_RS2_PLUS] so existing installs keep their current "RS2+" labelling and 9001 port.
 */
enum class ExternalReceiverProfile(
    val prefKey: String,
    /** Full label, e.g. shown on the Settings receiver card and diagnostics. */
    val label: String,
    /** Short label for the space-constrained toolbar source token. */
    val shortLabel: String,
    /** Default TCP port applied when this profile is selected. */
    val defaultPort: Int,
    /** Optional setup hint shown under the profile selector; null = no hint. */
    val hint: String?,
) {
    GENERIC_NMEA_TCP(
        prefKey = "generic_nmea_tcp",
        label = "Generic NMEA TCP",
        shortLabel = "EXT",
        defaultPort = 9000,
        hint = null,
    ),
    REACH_RS2_PLUS(
        prefKey = "reach_rs2_plus",
        label = "Emlid Reach RS2+",
        shortLabel = "RS2+",
        defaultPort = 9001,
        hint = null,
    ),
    REACH_RS4(
        prefKey = "reach_rs4",
        label = "Emlid Reach RS4",
        shortLabel = "RS4",
        defaultPort = 9001,
        hint = "Configure Emlid Flow → Position Streaming 1 as a TCP server with NMEA format.",
    ),
    REACH_RS4_PRO(
        prefKey = "reach_rs4_pro",
        label = "Emlid Reach RS4 Pro",
        shortLabel = "RS4 Pro",
        defaultPort = 9001,
        hint = "Configure Emlid Flow → Position Streaming 1 as a TCP server with NMEA format.",
    );

    companion object {
        val DEFAULT = REACH_RS2_PLUS

        /** Resolves a persisted [prefKey] back to a profile; unknown/blank → [DEFAULT]. */
        fun fromPrefKey(key: String?): ExternalReceiverProfile =
            entries.firstOrNull { it.prefKey == key } ?: DEFAULT

        /**
         * The port to use after the user switches to [newProfile]. Applies the new profile's
         * [defaultPort] only when [currentPort] is missing/invalid or was still at *some* profile's
         * default (i.e. not user-customized). A genuinely custom port is preserved — switching
         * profiles never silently overwrites it.
         */
        fun portForProfileChange(currentPort: Int?, newProfile: ExternalReceiverProfile): Int {
            val isCustom = currentPort != null && currentPort in 1..65535 &&
                entries.none { it.defaultPort == currentPort }
            return if (isCustom) currentPort!! else newProfile.defaultPort
        }
    }
}
