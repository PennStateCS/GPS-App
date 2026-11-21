package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.EBP

/**
 * Parses Emlid-specific `$..EBP` (base position) sentences. Tolerant: any missing/unparseable field
 * yields null, and EBP never participates in fix generation. See [EBP] for the assumed field order.
 */
public class EbpParser : SentenceParser<EBP> {
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

    private fun ddmmToDecimal(ddmm: String?, hemisphere: String?): Double? =
        NmeaCoordinateUtils.toDecimalDegrees(ddmm, hemisphere, isLatitude = true)

    private fun dddmmToDecimal(dddmm: String?, hemisphere: String?): Double? =
        NmeaCoordinateUtils.toDecimalDegrees(dddmm, hemisphere, isLatitude = false)
}
