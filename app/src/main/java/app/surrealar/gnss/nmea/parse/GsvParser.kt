package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.GSV
import app.surrealar.gnss.nmea.sentence.GSVSatellite
import kotlin.math.min

/**
 * Parses $..GSV (Satellites in view).
 *
 * Fields:
 * [0] TALKERTAG (e.g., GPGSV)
 * [1] totalMessages
 * [2] messageNumber
 * [3] totalSatellites
 * [4..] repeated groups of 4 per satellite:
 *      svid, elevationDeg, azimuthDeg, snrDb
 *
 * Each GSV message carries up to 4 satellites.
 */
class GsvParser : SentenceParser<GSV> {
    override val tag: String = "GSV"

    override fun parse(talker: String, fields: List<String>): GSV? {
        if (fields.size < 4) return null

        val totalMessages   = fields.getOrNull(1).toIntOrNullSafe()
        val messageNumber   = fields.getOrNull(2).toIntOrNullSafe()
        val totalSatellites = fields.getOrNull(3).toIntOrNullSafe()

        val satellites = ArrayList<GSVSatellite>(4)

        // Satellites start at index 4; groups of 4 fields per satellite
        // Guard for truncated lines: stop when we don't have the full quartet
        val start = 4
        val endExclusive = min(fields.size, start + 4 * 4) // at most 4 sats per message
        var i = start
        while (i + 3 < endExclusive) {
            val svid      = fields.getOrNull(i    ).toIntOrNullSafe()
            // Elevation/azimuth/SNR may carry decimals on some chipsets (e.g. Samsung tablets send
            // "25.3" dB-Hz); integer-only parsing dropped every SNR there, blanking the skyplot.
            val elevDeg   = fields.getOrNull(i + 1).toDoubleOrNullSafe()?.let { kotlin.math.round(it).toInt() }
            val azimDeg   = fields.getOrNull(i + 2).toDoubleOrNullSafe()?.let { kotlin.math.round(it).toInt() }
            val snrDb     = fields.getOrNull(i + 3).toDoubleOrNullSafe()

            if (svid != null) {
                satellites.add(
                    GSVSatellite(
                        svid = svid,
                        elevationDeg = elevDeg,
                        azimuthDeg = azimDeg,
                        snrDb = snrDb
                    )
                )
            }
            i += 4
        }

        return GSV(
            talker = talker,
            totalMessages = totalMessages,
            messageNumber = messageNumber,
            totalSatellites = totalSatellites,
            satellites = satellites,
            signalId = parseSignalId(fields)
        )
    }

    /**
     * NMEA 4.10+ appends a single signal-ID field after the satellite quartets, so the field count
     * past the 4-field header is 4n + 1. The ID is a hex digit (1–F).
     */
    private fun parseSignalId(fields: List<String>): Int? {
        if (fields.size < 5 || (fields.size - 4) % 4 != 1) return null
        return fields.last().trim().takeIf { it.isNotEmpty() }?.toIntOrNull(16)
    }

    private fun String?.toDoubleOrNullSafe(): Double? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun String?.toIntOrNullSafe(): Int? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
}
