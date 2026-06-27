package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.GSA

/**
 * Parses $..GSA (GNSS DOP and Active Satellites).
 *
 * Fields (without leading '$' or trailing *CS):
 * [0] TALKERTAG (e.g., GPGSA, GLGSA, GAGSA, etc.)
 * [1] Mode 1 (M = Manual, A = Automatic)           -> rarely needed downstream
 * [2] Mode 2 (Fix type: 1 = No Fix, 2 = 2D, 3 = 3D)
 * [3..14] IDs of up to 12 satellites used in solution (SVID/PRN) – may be blank
 * [15] PDOP
 * [16] HDOP
 * [17] VDOP
 *
 * Notes:
 * - Some receivers emit fewer than 12 SVID fields or leave some blank.
 * - DOP fields can be blank or non-numeric; we parse them as Double? safely.
 * - We expose mode2 as fixMode (Int?) and return the used SVIDs list.
 */
class GsaParser : SentenceParser<GSA> {
    override val tag: String = "GSA"

    override fun parse(talker: String, fields: List<String>): GSA? {
        if (fields.isEmpty()) return null

        // Mode 2 (fix type)
        val fixMode = fields.getOrNull(2).toIntOrNullSafe()

        // Collect up to 12 used SVIDs from [3..14]
        val used = mutableListOf<Int>()
        for (i in 3..14) {
            val svid = fields.getOrNull(i).toIntOrNullSafe()
            if (svid != null) used += svid
        }

        // Parse DOP values from fields 15-17
        val pDop = fields.getOrNull(15).toDoubleOrNullSafe()
        val hDop = fields.getOrNull(16).toDoubleOrNullSafe()
        val vDop = fields.getOrNull(17).toDoubleOrNullSafe()

        return GSA(
            talker = talker,
            fixMode = fixMode,
            usedSvids = used,
            pdop = pDop,
            hdop = hDop,
            vdop = vDop
        )
    }

    private fun String?.toIntOrNullSafe(): Int? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    private fun String?.toDoubleOrNullSafe(): Double? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
}
