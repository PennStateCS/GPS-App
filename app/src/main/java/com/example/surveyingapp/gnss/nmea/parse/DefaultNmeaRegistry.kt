package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.NmeaSentence

object DefaultNmeaRegistry {
    fun create(verifyChecksum: Boolean = true): NmeaRegistry {
        val map: Map<String, SentenceParser<out NmeaSentence>> = mapOf(
            "GGA" to GgaParser(),
            "RMC" to RmcParser(),
            "GSA" to GsaParser(),
            "GSV" to GsvParser(),
            "ZDA" to ZdaParser(),
            "GST" to GstParser(),
        )
        return NmeaRegistry(map, verifyChecksum)
    }
}
