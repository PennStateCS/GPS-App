package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.EBP

/**
 * Parses Emlid-specific `$..EBP` (base position) sentences. Tolerant: any missing/unparseable field
 * yields null, and EBP never participates in fix generation. See [EBP] for the assumed field order.
 */
class EbpParser : SentenceParser<EBP> {
    override val tag: String = "EBP"

    override fun parse(talker: String, fields: List<String>): EBP? {
        if (fields.isEmpty()) return null

        // Real RS4 format: $..EBP,lat,N,lon,W,alt,M — no leading time field.
        val lat = ddmmToDecimal(
            fields.getOrNull(1).orEmpty().ifBlank { null },
            fields.getOrNull(2).orEmpty().ifBlank { null }?.uppercase()
        )
        val lon = dddmmToDecimal(
            fields.getOrNull(3).orEmpty().ifBlank { null },
            fields.getOrNull(4).orEmpty().ifBlank { null }?.uppercase()
        )
        val altM = fields.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

        return EBP(talker = talker, baseLat = lat, baseLon = lon, baseAltM = altM)
    }

    private fun ddmmToDecimal(ddmm: String?, hemisphere: String?): Double? {
        val v = ddmm?.trim().orEmpty()
        if (v.length < 4) return null
        val deg = v.substring(0, 2).toIntOrNull() ?: return null
        val min = v.substring(2).toDoubleOrNull() ?: return null
        var value = deg + (min / 60.0)
        when (hemisphere) { "S" -> value = -value; "N", null -> {}; else -> return null }
        return value
    }

    private fun dddmmToDecimal(dddmm: String?, hemisphere: String?): Double? {
        val v = dddmm?.trim().orEmpty()
        if (v.length < 5) return null
        val deg = v.substring(0, 3).toIntOrNull() ?: return null
        val min = v.substring(3).toDoubleOrNull() ?: return null
        var value = deg + (min / 60.0)
        when (hemisphere) { "W" -> value = -value; "E", null -> {}; else -> return null }
        return value
    }
}
