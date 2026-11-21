package app.surrealar.gnss.parser

import app.surrealar.gnss.model.Constellation
import app.surrealar.gnss.nmea.parse.DefaultNmeaRegistry
import app.surrealar.gnss.nmea.parse.NmeaRegistry
import app.surrealar.gnss.nmea.sentence.NmeaSentence

/**
 * Thin façade over [NmeaRegistry] used by tests and the NMEA replay pipeline.
 *
 * Production GNSS sources (`InternalNmeaSource`, `TcpNmeaSource`) use `NmeaFuser` directly
 * with the singleton [NmeaRegistry] provided by DI, so they do not instantiate this class.
 */
class NmeaParser(registry: NmeaRegistry = DefaultNmeaRegistry.create()) {

    private val registry: NmeaRegistry = registry

    /**
     * Outcome of parsing one line: [Success] with the decoded [NmeaSentence], or [Error] with a
     * human-readable reason for an unrecognized or malformed line. Parsing never throws — an
     * unparseable line is a normal [Error], not an exception.
     */
    sealed class ParseResult {
        data class Success(val sentence: NmeaSentence) : ParseResult()
        data class Error(val message: String) : ParseResult()
    }

    /** Legacy satellite data structure retained for test compatibility. */
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
                ParseResult.Error("Unrecognised or invalid NMEA sentence: $line")
            }
        } catch (e: Exception) {
            ParseResult.Error("Parse error: ${e.message}")
        }
    }
}
