package com.example.surveyingapp.gnss.parser

import com.example.surveyingapp.gnss.model.Constellation
import com.example.surveyingapp.gnss.nmea.parse.*
import com.example.surveyingapp.gnss.nmea.sentence.NmeaSentence

/**
 * Consolidated NMEA parser that uses the proper Constellation enum from gnss.model
 * This class serves as a bridge for legacy code while ensuring all enums come from gnss.model
 */
class NmeaParser {

    private val registry = NmeaRegistry(
        mapOf(
            "GGA" to GgaParser(),
            "GSA" to GsaParser(),
            "GSV" to GsvParser(),
            "RMC" to RmcParser(),
            "ZDA" to ZdaParser()
        )
    )

    sealed class ParseResult {
        data class Success(val sentence: NmeaSentence) : ParseResult()
        data class Error(val message: String) : ParseResult()
    }

    /**
     * Legacy satellite data structure for backward compatibility
     */
    data class Satellite(
        val prn: Int,
        val constellation: Constellation,
        val elevationDeg: Double?,
        val azimuthDeg: Double?,
        val cn0DbHz: Double?
    )

    fun parse(line: String): ParseResult {
        return try {
            val sentence = registry.parse(line)
            if (sentence != null) {
                ParseResult.Success(sentence)
            } else {
                ParseResult.Error("Failed to parse NMEA sentence: $line")
            }
        } catch (e: Exception) {
            ParseResult.Error("Parse error: ${e.message}")
        }
    }
}
