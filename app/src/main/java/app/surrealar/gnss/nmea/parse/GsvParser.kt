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
            val elevDeg   = fields.getOrNull(i + 1).toIntOrNullSafe()
            val azimDeg   = fields.getOrNull(i + 2).toIntOrNullSafe()
            val snrDb     = fields.getOrNull(i + 3).toIntOrNullSafe()

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
            satellites = satellites
        )
    }

    private fun String?.toIntOrNullSafe(): Int? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
}
