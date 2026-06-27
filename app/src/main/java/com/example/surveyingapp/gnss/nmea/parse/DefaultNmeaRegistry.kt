package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.NmeaSentence

/**
 * Builds the default [NmeaRegistry] with all sentence parsers the app supports.
 *
 * This factory is used in tests and the replay pipeline where a standalone
 * registry is needed. Production code gets the registry through Hilt via
 * `GnssModule`, which calls this same factory under the hood.
 *
 * To add support for a new sentence type, create a [SentenceParser] subclass
 * and add it to the map here and in `GnssModule`.
 */
object DefaultNmeaRegistry {
    fun create(verifyChecksum: Boolean = true): NmeaRegistry {
        val map: Map<String, SentenceParser<out NmeaSentence>> = mapOf(
            "GGA" to GgaParser(),
            "RMC" to RmcParser(),
            "GSA" to GsaParser(),
            "GSV" to GsvParser(),
            "ZDA" to ZdaParser(),
            "GST" to GstParser(),
            // Emlid Reach RS4 / RS4 Pro custom messages (diagnostics only; do not affect fixes).
            "EBP" to EbpParser(),
            "ETC" to EtcParser(),
        )
        return NmeaRegistry(map, verifyChecksum)
    }
}
