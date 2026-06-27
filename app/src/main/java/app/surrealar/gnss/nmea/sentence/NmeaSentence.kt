package app.surrealar.gnss.nmea.sentence

/**
 * Common base for all parsed NMEA sentences.
 *
 * Every sentence has a talker ID and a three-letter sentence tag. The talker tells
 * you which GNSS system produced the data (e.g. "GP" for GPS, "GL" for GLONASS,
 * "GN" for combined). The tag identifies the sentence type (e.g. "GGA", "RMC").
 *
 * Implementations should be pure data holders with no side effects. All sentence
 * types are declared as data classes so they can be compared and copied easily.
 */
interface NmeaSentence {
    /** Two-letter talker prefix extracted from the sentence header (e.g. "GP", "GL"). */
    val talker: String

    /** Three-letter sentence type tag (e.g. "GGA", "RMC", "GSA"). Always uppercase. */
    val tag: String
}
