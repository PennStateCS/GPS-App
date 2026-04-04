package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.NmeaSentence

/**
 * Contract for parsing a specific NMEA sentence type (e.g., GGA, RMC, GSA, GSV, ZDA).
 *
 * Implementations MUST:
 *  - Expose the 3-letter NMEA tag they parse via `tag` (e.g., "GGA").
 *  - Never throw; return null on malformed/unsupported input.
 *  - Keep parsing PURE (no side effects, no accumulation).
 *
 * The `talker` is the 2-letter prefix (e.g., "GP", "GL", "GA") extracted by NmeaRegistry.
 * The `fields` list includes field[0] == "<TALKER><TAG>" to keep indices stable
 * with common NMEA field numbering (so field[1] is the first data field).
 */
interface SentenceParser<S : NmeaSentence> {
    /** The 3-letter NMEA sentence tag (e.g., "GGA", "RMC"). Uppercase. */
    val tag: String

    /**
     * Parse a typed sentence from talker + CSV fields.
     * @param talker The two-letter talker ID (e.g., "GP", "GL"), already uppercased.
     * @param fields CSV-split fields including head at index 0 ("<TALKER><TAG>").
     * @return A typed sentence or null if not parseable/invalid.
     */
    fun parse(talker: String, fields: List<String>): S?
}

/** Handy alias when building parser registries/maps. */
typealias AnySentenceParser = SentenceParser<out NmeaSentence>
