package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.GGA

/**
 * Parses $..GGA sentences.
 *
 * Fields (without leading '$' or trailing *CS):
 * [0]=TALKERTAG (e.g., GPGGA)
 * [1]=time (hhmmss[.sss])
 * [2]=lat (ddmm.mmmm)
 * [3]=N|S
 * [4]=lon (dddmm.mmmm)
 * [5]=E|W
 * [6]=fixQuality (0..8, vendor-specific higher values possible)
 * [7]=numSV (satellites used)
 * [8]=HDOP
 * [9]=altitude (meters)
 * [10]=alt unit ('M' for meters)
 * [11]=geoid separation (meters)
 * [12]=geoid unit ('M')
 * [13]=age of diff (seconds, optional)
 * [14]=station ID (optional)
 */
class GgaParser : SentenceParser<GGA> {
    override val tag: String = "GGA"

    override fun parse(talker: String, fields: List<String>): GGA? {
        if (fields.isEmpty()) return null

        val timeRaw = fields.getOrNull(1).orEmpty().ifBlank { null }

        // Latitude
        val latRaw = fields.getOrNull(2).orEmpty().ifBlank { null }
        val latHem = fields.getOrNull(3).orEmpty().ifBlank { null }?.uppercase()
        val lat = ddmmToDecimal(latRaw, latHem)

        // Longitude
        val lonRaw = fields.getOrNull(4).orEmpty().ifBlank { null }
        val lonHem = fields.getOrNull(5).orEmpty().ifBlank { null }?.uppercase()
        val lon = dddmmToDecimal(lonRaw, lonHem)

        // Fix quality, satellites, HDOP
        val fixQuality = fields.getOrNull(6).toIntOrNullSafe()
        val satsUsed   = fields.getOrNull(7).toIntOrNullSafe()
        val hdop       = fields.getOrNull(8).toDoubleOrNullSafe()

        // Altitude MSL and Geoid separation (meters)
        val altMslVal   = fields.getOrNull(9).toDoubleOrNullSafe()
        val altMslUnit  = fields.getOrNull(10)?.uppercase()
        val geoidVal    = fields.getOrNull(11).toDoubleOrNullSafe()
        val geoidUnit   = fields.getOrNull(12)?.uppercase()

        val altMsl = if (altMslVal != null && (altMslUnit == null || altMslUnit == "M")) altMslVal else null
        val geoidSeparation = if (geoidVal != null && (geoidUnit == null || geoidUnit == "M")) geoidVal else null

        val diffAge   = fields.getOrNull(13).toDoubleOrNullSafe()
        val stationId = fields.getOrNull(14).orEmpty().ifBlank { null }

        return GGA(
            talker = talker,
            timeRaw = timeRaw,
            lat = lat,
            lon = lon,
            fixQuality = fixQuality,
            satsUsed = satsUsed,
            hdop = hdop,
            altMsl = altMsl,
            geoidSeparation = geoidSeparation,
            diffAge = diffAge,
            stationId = stationId
        )
    }

    /** Latitude in ddmm.mmmm and hemisphere N/S → decimal degrees. */
    private fun ddmmToDecimal(ddmm: String?, hemisphere: String?): Double? {
        val v = ddmm?.trim().orEmpty()
        if (v.isEmpty() || v.length < 4) return null
        val deg = v.substring(0, 2).toIntOrNull() ?: return null
        val min = v.substring(2).toDoubleOrNull() ?: return null
        var value = deg + (min / 60.0)
        when (hemisphere) {
            "S" -> value = -value
            "N", null -> { /* keep + */ }
            else -> return null
        }
        return value
    }

    /** Longitude in dddmm.mmmm and hemisphere E/W → decimal degrees. */
    private fun dddmmToDecimal(dddmm: String?, hemisphere: String?): Double? {
        val v = dddmm?.trim().orEmpty()
        if (v.isEmpty() || v.length < 5) return null
        val deg = v.substring(0, 3).toIntOrNull() ?: return null
        val min = v.substring(3).toDoubleOrNull() ?: return null
        var value = deg + (min / 60.0)
        when (hemisphere) {
            "W" -> value = -value
            "E", null -> { /* keep + */ }
            else -> return null
        }
        return value
    }

    private fun String?.toIntOrNullSafe(): Int? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    private fun String?.toDoubleOrNullSafe(): Double? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
}
