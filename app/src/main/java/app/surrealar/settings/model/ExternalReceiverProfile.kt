package app.surrealar.settings.model

import io.github.pennstatecs.geokit.gnss.nmea.parse.EbpParser
import io.github.pennstatecs.geokit.gnss.nmea.parse.EtcParser
import io.github.pennstatecs.geokit.gnss.nmea.parse.SentenceParser
import io.github.pennstatecs.geokit.gnss.nmea.sentence.NmeaSentence
import io.github.pennstatecs.geokit.gnss.receiver.AccuracyClass
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverVendor

/**
 * Selectable external-receiver *profile*. This is metadata only — every profile uses the same live
 * External TCP NMEA pipeline (TcpNmeaSource → ExternalAdapter → NmeaFuser → FixSwitchboard). A
 * profile carries defaults, display labels and what the receiver is: its [vendor], [accuracyClass]
 * (selects the DOP × UERE fallback row), [capabilities] (battery / device / corrections status,
 * discovery) and any proprietary NMEA sentences it sends ([extraParsers]). It does NOT create a new
 * GNSS source.
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
    /** Receiver manufacturer. */
    val vendor: ReceiverVendor,
    /** Accuracy class used for the DOP × UERE fallback when the receiver reports no accuracy. */
    val accuracyClass: AccuracyClass,
    /**
     * Height (metres) of the antenna phase centre (L1) above the antenna reference point on the
     * bottom of the receiver: what the receiver's reported height refers to, relative to the point
     * users measure to. Null when unknown (the user enters it on the Receiver page).
     */
    val phaseCenterOffsetM: Double?,
    /** What the receiver reports beyond NMEA (drives which status UI is shown). */
    val capabilities: ReceiverCapabilities,
    /**
     * Proprietary NMEA sentence parsers this receiver needs, keyed by sentence tag. The live registry
     * is built once (TcpNmeaSource holds it for the app's lifetime), so it registers the union of
     * every profile's parsers; see GnssModule.provideNmeaRegistry.
     */
    val extraParsers: () -> Map<String, SentenceParser<out NmeaSentence>>,
) {
    GENERIC_NMEA_TCP(
        prefKey = "generic_nmea_tcp",
        label = "Generic NMEA TCP",
        shortLabel = "EXT",
        defaultPort = 9000,
        hint = null,
        vendor = ReceiverVendor.GENERIC,
        // Could be anything: assume single-frequency rather than survey grade.
        accuracyClass = AccuracyClass.SINGLE_FREQUENCY,
        phaseCenterOffsetM = null,
        capabilities = ReceiverCapabilities.NONE,
        extraParsers = { emptyMap() },
    ),
    REACH_RS2_PLUS(
        prefKey = "reach_rs2_plus",
        label = "Emlid Reach RS2+",
        shortLabel = "RS2+",
        defaultPort = 9001,
        hint = null,
        vendor = ReceiverVendor.EMLID,
        accuracyClass = AccuracyClass.SURVEY_MULTIBAND,
        phaseCenterOffsetM = 0.134,   // Emlid RS2/RS2+ manual: ARP → APC 134 mm
        capabilities = ReceiverCapabilities.ALL,
        extraParsers = ::emlidParsers,
    ),
    REACH_RS4(
        prefKey = "reach_rs4",
        label = "Emlid Reach RS4",
        shortLabel = "RS4",
        defaultPort = 9001,
        hint = "Configure Emlid Flow → Position Streaming 1 as a TCP server with NMEA format.",
        vendor = ReceiverVendor.EMLID,
        accuracyClass = AccuracyClass.SURVEY_MULTIBAND,
        phaseCenterOffsetM = 0.095,   // Emlid RS4 specs: L1 phase-centre offset 95 mm
        capabilities = ReceiverCapabilities.ALL,
        extraParsers = ::emlidParsers,
    ),
    REACH_RS4_PRO(
        prefKey = "reach_rs4_pro",
        label = "Emlid Reach RS4 Pro",
        shortLabel = "RS4 Pro",
        defaultPort = 9001,
        hint = "Configure Emlid Flow → Position Streaming 1 as a TCP server with NMEA format.",
        vendor = ReceiverVendor.EMLID,
        accuracyClass = AccuracyClass.SURVEY_MULTIBAND,
        phaseCenterOffsetM = null,    // not confirmed separately from the RS4; user enters it
        capabilities = ReceiverCapabilities.ALL,
        extraParsers = ::emlidParsers,
    );

    companion object {
        val DEFAULT = REACH_RS2_PLUS

        /** Resolves a persisted [prefKey] back to a profile; unknown/blank → [DEFAULT]. */
        fun fromPrefKey(key: String?): ExternalReceiverProfile =
            entries.firstOrNull { it.prefKey == key } ?: DEFAULT

        /** The profile whose [label] is [label] (as stored in a point's sourceDevice), or null. */
        fun fromLabel(label: String?): ExternalReceiverProfile? =
            label?.trim()?.let { l -> entries.firstOrNull { it.label.equals(l, ignoreCase = true) } }

        /** Parsers for every profile's proprietary sentences (what the single live registry registers). */
        fun allExtraParsers(): Map<String, SentenceParser<out NmeaSentence>> =
            entries.fold(emptyMap()) { acc, p -> acc + p.extraParsers() }

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

/** Emlid Reach RS4 / RS4 Pro custom messages (diagnostics only; they never affect fixes). */
private fun emlidParsers(): Map<String, SentenceParser<out NmeaSentence>> =
    mapOf("EBP" to EbpParser(), "ETC" to EtcParser())
