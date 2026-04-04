package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.NmeaSentence

class NmeaRegistry(
    private val parsers: Map<String, SentenceParser<out NmeaSentence>>,
    private val verifyChecksum: Boolean = true
) {
    fun parse(line: String): NmeaSentence? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed[0] != '$') return null

        val starIdx = trimmed.indexOf('*')
        val payloadEnd = if (starIdx >= 0) starIdx else trimmed.length
        if (payloadEnd <= 1) return null

        val payload = trimmed.substring(1, payloadEnd)

        if (verifyChecksum && starIdx >= 0 && starIdx + 3 <= trimmed.length) {
            val csHex = trimmed.substring(starIdx + 1, starIdx + 3)
            val expected = csHex.toIntOrNull(16) ?: return null
            if (xorChecksum(payload) != expected) return null
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
