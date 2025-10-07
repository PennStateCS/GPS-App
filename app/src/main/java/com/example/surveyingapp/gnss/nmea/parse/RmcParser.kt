package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.RMC
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Parses $..RMC (Recommended Minimum) sentences.
 *
 * Fields (without leading '$' or trailing *CS):
 * [0] TALKERTAG (e.g., GPRMC)
 * [1] UTC time (hhmmss[.sss])
 * [2] Status A=active, V=void
 * [3] Latitude (ddmm.mmmm)
 * [4] N/S
 * [5] Longitude (dddmm.mmmm)
 * [6] E/W
 * [7] Speed over ground in knots
 * [8] Course over ground (degrees)
 * [9] Date (ddmmyy)
 * [10] Magnetic variation (optional)
 * [11] MagVar E/W (optional)
 * [12] Mode (optional; e.g., A/D/E/N)
 */
class RmcParser : SentenceParser<RMC> {
    override val tag: String = "RMC"

    override fun parse(talker: String, fields: List<String>): RMC? {
        if (fields.isEmpty()) return null

        val timeRaw = fields.getOrNull(1).orEmpty().ifBlank { null }
        val status  = fields.getOrNull(2).orEmpty().trim().uppercase().firstOrNull()

        // Lat/Lon trusted only when status == 'A' (active)
        val lat = if (status == 'A') {
            val latRaw = fields.getOrNull(3).orEmpty().ifBlank { null }
            val latHem = fields.getOrNull(4).orEmpty().ifBlank { null }?.uppercase()
            ddmmToDecimal(latRaw, latHem)
        } else null

        val lon = if (status == 'A') {
            val lonRaw = fields.getOrNull(5).orEmpty().ifBlank { null }
            val lonHem = fields.getOrNull(6).orEmpty().ifBlank { null }?.uppercase()
            dddmmToDecimal(lonRaw, lonHem)
        } else null

        val speedKnots = fields.getOrNull(7).toDoubleOrNullSafe()
        val courseDeg  = fields.getOrNull(8).toDoubleOrNullSafe()
        val dateRaw    = fields.getOrNull(9).orEmpty().ifBlank { null }

        val time = parseTime(timeRaw)
        val date = parseDate(dateRaw)
        val epochMillis = parseEpochMillis(time, date)

        return RMC(
            talker = talker,
            timeRaw = timeRaw,
            dateRaw = dateRaw,
            time = time,
            date = date,
            epochMillis = epochMillis,
            lat = lat,
            lon = lon,
            speedKnots = speedKnots,
            courseDeg = courseDeg
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

    private fun String?.toDoubleOrNullSafe(): Double? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    /** Parse NMEA time format hhmmss[.sss] to LocalTime. */
    private fun parseTime(timeRaw: String?): LocalTime? {
        if (timeRaw.isNullOrBlank()) return null
        val s = timeRaw.trim()
        if (s.length < 6) return null

        return try {
            val hh = s.substring(0, 2).toInt()
            val mm = s.substring(2, 4).toInt()
            val ss = s.substring(4, 6).toInt()
            val nanos = if (s.length > 6 && s[6] == '.') {
                val frac = s.substring(7).takeIf { it.isNotEmpty() } ?: "0"
                frac.padEnd(9, '0').take(9).toIntOrNull() ?: 0
            } else 0
            LocalTime.of(hh, mm, ss, nanos)
        } catch (_: Exception) {
            null
        }
    }

    /** Parse NMEA date format ddmmyy to LocalDate (00–68 → 2000–2068; 69–99 → 1969–1999). */
    private fun parseDate(dateRaw: String?): LocalDate? {
        if (dateRaw.isNullOrBlank()) return null
        val s = dateRaw.trim()
        if (s.length != 6) return null
        return try {
            val dd = s.substring(0, 2).toInt()
            val mm = s.substring(2, 4).toInt()
            val yy = s.substring(4, 6).toInt()
            val year = if (yy <= 68) 2000 + yy else 1900 + yy
            LocalDate.of(year, mm, dd)
        } catch (_: Exception) {
            null
        }
    }

    /** Build epoch millis from parsed time/date if both are available; otherwise null. */
    private fun parseEpochMillis(time: LocalTime?, date: LocalDate?): Long? {
        if (time == null || date == null) return null
        return try {
            LocalDateTime.of(date, time).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
