package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.ACCURACY

/**
 * Parses MediaTek `$..ACCURACY,<hAcc>,<?>,<speedAcc>,<vAcc>` (see [ACCURACY]).
 * Non-positive or non-numeric accuracies are treated as unknown (null), never 0.0.
 */
public class AccuracyParser : SentenceParser<ACCURACY> {
    override val tag: String = "ACCURACY"

    override fun parse(talker: String, fields: List<String>): ACCURACY? {
        if (fields.size < 2) return null
        val h = fields.getOrNull(1).positiveOrNull()
        val v = fields.getOrNull(4).positiveOrNull()
        if (h == null && v == null) return null
        return ACCURACY(
            talker = talker,
            hAccM = h,
            field2 = fields.getOrNull(2)?.trim()?.toDoubleOrNull(),
            speedAccMps = fields.getOrNull(3)?.trim()?.toDoubleOrNull(),
            vAccM = v,
        )
    }

    private fun String?.positiveOrNull(): Double? =
        this?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}
