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
public class GsvParser : SentenceParser<GSV> {
    override val tag: String = "GSV"

    override fun parse(talker: String, fields: List<String>): GSV? {
        if (fields.size < 4) return null

        val totalMessages   = fields.getOrNull(1).toIntOrNullSafe()
        val messageNumber   = fields.getOrNull(2).toIntOrNullSafe()
        val totalSatellites = fields.getOrNull(3).toIntOrNullSafe()

        val satellites = ArrayList<GSVSatellite>(4)

        // Detect the signal ID BEFORE reading quartets, so it is never read as an SNR.
        val (signalId, dataEnd, lastQuartetShort) = locateSignalId(fields)

        // Satellites start at index 4; groups of 4 fields per satellite
        // Guard for truncated lines: stop when we don't have the full quartet — except the known
        // "final SNR omitted + signal ID" form, whose last quartet legitimately has 3 fields.
        val start = 4
        val endExclusive = min(dataEnd, start + 4 * 4) // at most 4 sats per message
        val minFields = if (lastQuartetShort) 3 else 4
        var i = start
        while (i + minFields - 1 < endExclusive) {
            val svid      = fields.getOrNull(i    ).toIntOrNullSafe()
            // Elevation/azimuth/SNR may carry decimals on some chipsets (e.g. Samsung tablets send
            // "25.3" dB-Hz); integer-only parsing dropped every SNR there, blanking the skyplot.
            val elevDeg   = fields.getOrNull(i + 1).toDoubleOrNullSafe()?.let { kotlin.math.round(it).toInt() }
            val azimDeg   = fields.getOrNull(i + 2).toDoubleOrNullSafe()?.let { kotlin.math.round(it).toInt() }
            val snrDb     = if (i + 3 < endExclusive) fields.getOrNull(i + 3).toDoubleOrNullSafe() else null

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
            signalId = signalId
        )
    }

    private data class SignalLayout(val signalId: Int?, val dataEnd: Int, val lastQuartetShort: Boolean)

    /**
     * NMEA 4.10+ appends a single signal-ID field (one hex digit, 1–F) after the satellite quartets:
     *  - 4n + 1 data fields: complete quartets (final SNR possibly blank) + signal ID.
     *  - 4n data fields whose last field is a single hex digit: the final SNR field was omitted
     *    entirely, then the signal ID. A real SNR is two digits ("xx"), so a lone digit is the ID.
     *    Without this the ID was read as SNR (1 dB-Hz).
     * Otherwise there is no signal ID.
     */
    private fun locateSignalId(fields: List<String>): SignalLayout {
        val data = fields.size - 4
        val last = fields.lastOrNull()?.trim().orEmpty()
        if (data >= 1 && data % 4 == 1) {
            return SignalLayout(last.takeIf { it.isNotEmpty() }?.toIntOrNull(16), fields.size - 1, false)
        }
        if (data >= 4 && data % 4 == 0 && last.length == 1 && last[0].uppercaseChar() in SIGNAL_DIGITS) {
            return SignalLayout(last.toInt(16), fields.size - 1, true)
        }
        return SignalLayout(null, fields.size, false)
    }

    private companion object {
        val SIGNAL_DIGITS = ('1'..'9') + ('A'..'F')
    }

    private fun String?.toDoubleOrNullSafe(): Double? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun String?.toIntOrNullSafe(): Int? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
}
