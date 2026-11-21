package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.ETC

/**
 * Parses Emlid-specific `$..ETC` sentences. Tolerant by design: it captures the UTC time and the raw
 * data fields WITHOUT interpreting their semantics (the layout is undocumented — see [ETC]). ETC is
 * diagnostics-only and never modifies coordinates, the live fix, or AR orientation.
 */
class EtcParser : SentenceParser<ETC> {
    override val tag: String = "ETC"

    override fun parse(talker: String, fields: List<String>): ETC? {
        if (fields.isEmpty()) return null
        val timeRaw = fields.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        val dataFields = if (fields.size > 2) fields.subList(2, fields.size).map { it.trim() } else emptyList()
        return ETC(talker = talker, timeRaw = timeRaw, dataFields = dataFields)
    }
}
