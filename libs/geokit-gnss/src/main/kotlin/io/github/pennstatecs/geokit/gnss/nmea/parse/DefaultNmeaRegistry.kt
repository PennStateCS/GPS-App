package io.github.pennstatecs.geokit.gnss.nmea.parse

import io.github.pennstatecs.geokit.gnss.nmea.sentence.NmeaSentence

/**
 * Builds the default [NmeaRegistry] with every sentence parser in this library.
 *
 * Used by tests and NMEA replay. A host can also build its own registry from the same parsers, e.g.
 * with checksums required for live streams (`NmeaRegistry(parsers, requireChecksum = true)`).
 *
 * To add support for a new sentence type, create a [SentenceParser] subclass
 * and add it to the map here (and to any registry the host builds itself).
 */
public object DefaultNmeaRegistry {
    /**
     * A registry with GGA, RMC, GSA, GSV, ZDA, GST, $..ACCURACY, EBP and ETC parsers.
     * @param verifyChecksum reject lines whose `*hh` checksum doesn't match.
     * @param requireChecksum also reject lines with no checksum (live streams); false for log files.
     */
    public fun create(verifyChecksum: Boolean = true, requireChecksum: Boolean = false): NmeaRegistry {
        val map: Map<String, SentenceParser<out NmeaSentence>> = mapOf(
            "GGA" to GgaParser(),
            "RMC" to RmcParser(),
            "GSA" to GsaParser(),
            "GSV" to GsvParser(),
            "ZDA" to ZdaParser(),
            "GST" to GstParser(),
            // MediaTek proprietary accuracy (e.g. Samsung SM-X940 internal GNSS) — fallback when no GST.
            "ACCURACY" to AccuracyParser(),
            // Emlid Reach RS4 / RS4 Pro custom messages (diagnostics only; do not affect fixes).
            "EBP" to EbpParser(),
            "ETC" to EtcParser(),
        )
        return NmeaRegistry(map, verifyChecksum, requireChecksum)
    }
}
