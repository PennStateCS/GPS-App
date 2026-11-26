package app.surrealar.receiver.selection

import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import io.github.pennstatecs.geokit.gnss.nmea.parse.NmeaRegistry
import io.github.pennstatecs.geokit.gnss.nmea.sentence.GGA
import io.github.pennstatecs.geokit.gnss.nmea.sentence.NmeaSentence
import io.github.pennstatecs.geokit.gnss.nmea.sentence.RMC

/**
 * Decides what the connection test's TCP sniff received (stabilization item 9). One instance per probe; not
 * thread-safe (the probe reads lines on one coroutine).
 *
 * A line is **valid NMEA** only when it has the NMEA shape — `$`, a talker + sentence type (`GNGGA`, `PUBX`…),
 * comma-separated fields and a trailing `*hh` checksum of exactly two hex digits — AND geokit's live-stream registry
 * ([DefaultNmeaRegistry] with `requireChecksum = true` and the profiles' extra parsers, the same configuration as the
 * live TCP source) accepts it, which verifies the checksum and parses the sentence. Anything else that starts with `$`
 * (arbitrary text, a bad checksum, a truncated line, a type the live pipeline cannot read) is not counted as NMEA.
 * Parsing itself is geokit's and unchanged; this only reads the result.
 *
 * Generic: no vendor request is made and no RTK state is required. A receiver that streams valid sentences but has no
 * position yet (GGA fix quality 0, RMC status V, only GSV/GSA…) is reachable: [Verdict.NmeaNoPosition].
 */
class NmeaProbeClassifier(
    private val registry: NmeaRegistry = liveRegistry(),
) {
    /** What the sniff saw so far; [verdict] turns it into a [TcpProbeResult]. */
    var validSentences = 0
        private set
    var positionSentences = 0
        private set
    /** Lines that started with `$` but were not valid NMEA (shape, checksum or unparseable). */
    var invalidDollarLines = 0
        private set
    /** Non-empty lines that did not start with `$` (RTCM / binary / other text). */
    var otherLines = 0
        private set
    /** A valid GGA or RMC said explicitly that there is no position (quality 0 / status V). */
    var sawExplicitNoPosition = false
        private set

    /**
     * Feeds one line read from the socket. Returns a final result when no more reading is needed: a position was
     * received (RECEIVING_NMEA), or a valid GGA/RMC explicitly reported no position (RECEIVING_NMEA_NO_POSITION).
     * Otherwise null: keep reading until the window ends, then call [verdict].
     */
    fun offer(rawLine: String): TcpProbeResult? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        if (line[0] != '$') { otherLines++; return null }
        val sentence = if (SHAPE.matches(line)) runCatching { registry.parse(line) }.getOrNull() else null
        if (sentence == null) { invalidDollarLines++; return null }
        validSentences++
        return when (position(sentence)) {
            true -> { positionSentences++; TcpProbeResult.RECEIVING_NMEA }
            false -> { sawExplicitNoPosition = true; TcpProbeResult.RECEIVING_NMEA_NO_POSITION }
            null -> null      // GSV, GSA, ZDA, GST, vendor sentences: valid, but say nothing about the position
        }
    }

    /** The result at the end of the read window (or when the stream ended). */
    fun verdict(): TcpProbeResult = when {
        positionSentences > 0 -> TcpProbeResult.RECEIVING_NMEA
        validSentences > 0 -> TcpProbeResult.RECEIVING_NMEA_NO_POSITION
        invalidDollarLines > 0 -> TcpProbeResult.INVALID_NMEA
        otherLines > 0 -> TcpProbeResult.RECEIVING_RTCM_OR_BIN
        else -> TcpProbeResult.CONNECTED_NO_DATA
    }

    companion object {
        /**
         * `$` + address field (talker + type, or a proprietary `P…` id: 3–10 letters/digits starting with a letter),
         * at least one comma-separated field, `*` + exactly two hex digits at the very end.
         */
        private val SHAPE = Regex("""^\$[A-Z][A-Z0-9]{2,9},[\x20-\x7E]*\*[0-9A-Fa-f]{2}$""")

        /** The live TCP source's registry configuration (GnssModule.provideNmeaRegistry). */
        fun liveRegistry(): NmeaRegistry = DefaultNmeaRegistry.create(
            verifyChecksum = true,
            requireChecksum = true,
            extraParsers = ExternalReceiverProfile.allExtraParsers(),
        )

        /** True: a position; false: explicitly none; null: the sentence does not say. */
        internal fun position(s: NmeaSentence): Boolean? = when (s) {
            is GGA -> (s.fixQuality ?: 0) > 0 && s.lat != null && s.lon != null
            // The geokit RMC parser keeps lat/lon only for status A (active).
            is RMC -> s.lat != null && s.lon != null
            else -> null
        }
    }
}
