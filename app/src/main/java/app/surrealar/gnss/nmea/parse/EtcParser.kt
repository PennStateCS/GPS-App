package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.ETC

/**
 * Parses Emlid-specific `$..ETC` (tilt compensation) sentences. Tolerant: any missing/unparseable
 * field yields null. ETC is diagnostics-only and never modifies coordinates. See [ETC] for the
 * assumed field order.
 */
class EtcParser : SentenceParser<ETC> {
    override val tag: String = "ETC"

    override fun parse(talker: String, fields: List<String>): ETC? {
        if (fields.isEmpty()) return null
        fun str(i: Int) = fields.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
        fun num(i: Int) = str(i)?.toDoubleOrNull()

        return ETC(
            talker = talker,
            timeRaw = str(1),
            tiltStatusRaw = str(2),
            tiltAngleDeg = num(3),
            headingDeg = num(4),
            warningRaw = str(5),
        )
    }
}
