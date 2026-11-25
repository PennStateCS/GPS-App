package io.github.pennstatecs.geokit.receiver.emlid.nmea

import io.github.pennstatecs.geokit.gnss.nmea.parse.NmeaFields
import io.github.pennstatecs.geokit.gnss.nmea.parse.SentenceParser

/**
 * Parses Emlid-specific `$..EBP` (base position) sentences. Tolerant: any missing/unparseable field
 * yields null, and EBP never participates in fix generation. See [EBP] for the assumed field order.
 */
public class EbpParser : SentenceParser<EBP> {
    override val tag: String = EmlidNmea.TAG_EBP

    override fun parse(talker: String, fields: List<String>): EBP? {
        if (fields.isEmpty()) return null

        // Real RS4 format: $..EBP,lat,N,lon,W,alt,M — no leading time field.
        val lat = NmeaFields.latitude(
            fields.getOrNull(1).orEmpty().ifBlank { null },
            fields.getOrNull(2).orEmpty().ifBlank { null }?.uppercase()
        )
        val lon = NmeaFields.longitude(
            fields.getOrNull(3).orEmpty().ifBlank { null },
            fields.getOrNull(4).orEmpty().ifBlank { null }?.uppercase()
        )
        val altM = NmeaFields.finiteDouble(fields.getOrNull(5))

        return EBP(talker = talker, baseLat = lat, baseLon = lon, baseAltM = altM)
    }
}

/**
 * Parses Emlid-specific `$..ETC` (tilt compensation data) sentences. The checksum is checked by the
 * registry like any sentence. The parser keeps the time and the raw data fields and decodes them per the
 * documented layout into [ETC.decoded]; it never throws and never rejects a sentence for its content
 * (a malformed ETC is still counted for diagnostics and reads as unknown). ETC never modifies
 * coordinates, the live fix, or AR orientation.
 */
public class EtcParser : SentenceParser<ETC> {
    override val tag: String = EmlidNmea.TAG_ETC

    override fun parse(talker: String, fields: List<String>): ETC? {
        if (fields.isEmpty()) return null
        val timeRaw = fields.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        val dataFields = if (fields.size > 2) fields.subList(2, fields.size).map { it.trim() } else emptyList()
        return ETC(talker = talker, timeRaw = timeRaw, dataFields = dataFields)
    }
}
