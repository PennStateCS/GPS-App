package io.github.pennstatecs.geokit.gnss.nmea.parse

import io.github.pennstatecs.geokit.gnss.nmea.sentence.NmeaSentence

/**
 * Builds an [NmeaRegistry] with every standard sentence parser in this library, plus any extra parsers
 * the host supplies (receiver-specific / proprietary sentences from a receiver module).
 *
 * Extension: a receiver module writes a [SentenceParser] per proprietary sentence (its sentence type
 * implements [NmeaSentence], optionally [io.github.pennstatecs.geokit.gnss.nmea.sentence.FlaggedNmeaSentence])
 * and hands the map to [create] (or `ReplayAdapter(extraParsers = …)`). Those sentences never feed a
 * fix: [io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser] counts them per tag and keeps the
 * latest of each for diagnostics (`NmeaFuser.extraSentenceStats`).
 */
public object DefaultNmeaRegistry {

    /** The standard parsers by tag: GGA, RMC, GSA, GSV, ZDA, GST and MediaTek `$..ACCURACY`. */
    public fun standardParsers(): Map<String, SentenceParser<out NmeaSentence>> = mapOf(
        "GGA" to GgaParser(),
        "RMC" to RmcParser(),
        "GSA" to GsaParser(),
        "GSV" to GsvParser(),
        "ZDA" to ZdaParser(),
        "GST" to GstParser(),
        // MediaTek proprietary accuracy (e.g. Samsung SM-X940 internal GNSS) — fallback when no GST.
        "ACCURACY" to AccuracyParser(),
    )

    /**
     * A registry with the [standardParsers] plus [extraParsers].
     * @param verifyChecksum reject lines whose `*hh` checksum doesn't match.
     * @param requireChecksum also reject lines with no checksum (live streams); false for log files.
     * @param extraParsers additional parsers keyed by sentence tag (the part after the two-letter
     *   talker, uppercase). An extra parser with a standard tag replaces the standard one.
     */
    public fun create(
        verifyChecksum: Boolean = true,
        requireChecksum: Boolean = false,
        extraParsers: Map<String, SentenceParser<out NmeaSentence>> = emptyMap(),
    ): NmeaRegistry = NmeaRegistry(standardParsers() + extraParsers, verifyChecksum, requireChecksum)
}
