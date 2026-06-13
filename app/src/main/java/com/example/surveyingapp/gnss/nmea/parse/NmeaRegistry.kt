package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.NmeaSentence

/**
 * Central NMEA sentence parser registry.
 *
 * @param parsers Map of sentence type (e.g., "GGA", "RMC") to parser implementation
 * @param verifyChecksum If true, validates checksums when present. If [requireChecksum] is also
 *                       true, rejects sentences without checksums entirely.
 * @param requireChecksum If true (and [verifyChecksum] is true), rejects sentences that lack a
 *                        checksum. Set to false for dev/replay scenarios where test data may
 *                        omit checksums.
 */
class NmeaRegistry(
    private val parsers: Map<String, SentenceParser<out NmeaSentence>>,
    private val verifyChecksum: Boolean = true,
    private val requireChecksum: Boolean = false
) {
    fun parse(line: String): NmeaSentence? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed[0] != '$') return null

        val starIdx = trimmed.indexOf('*')
        val payloadEnd = if (starIdx >= 0) starIdx else trimmed.length
        if (payloadEnd <= 1) return null

        val payload = trimmed.substring(1, payloadEnd)

        // Checksum validation
        if (verifyChecksum) {
            if (starIdx >= 0 && starIdx + 3 <= trimmed.length) {
                // Checksum present: validate it
                val csHex = trimmed.substring(starIdx + 1, starIdx + 3)
                val expected = csHex.toIntOrNull(16) ?: return null
                if (xorChecksum(payload) != expected) {
                    android.util.Log.w("NmeaRegistry", "Checksum mismatch: $trimmed")
                    return null
                }
            } else if (requireChecksum) {
                // Checksum missing but required: reject sentence
                android.util.Log.w("NmeaRegistry", "Missing required checksum: $trimmed")
                return null
            }
            // else: checksum missing but not required; allow sentence
        }

        val fields = payload.split(',')
        if (fields.isEmpty()) return null

        val talkerAndTag = fields[0]
        if (talkerAndTag.length < 5) return null // e.g., "GP" + "GGA"

        val talker = talkerAndTag.substring(0, 2).uppercase()
        val tag    = talkerAndTag.substring(2).uppercase()

        val parser = parsers[tag] ?: return null
        @Suppress("UNCHECKED_CAST")
        return (parser as SentenceParser<NmeaSentence>).parse(talker, fields)
    }

    private fun xorChecksum(s: String): Int {
        var cs = 0
        for (ch in s) cs = cs xor ch.code
        return cs and 0xFF
    }
}
