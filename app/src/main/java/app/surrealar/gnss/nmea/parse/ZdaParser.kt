package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.ZDA
import java.time.*

/**
 * Parses $..ZDA sentences (time & date).
 *
 * Fields (without leading '$' or trailing *CS):
 * [0] TALKERTAG (e.g., GPZDA)
 * [1] UTC time hhmmss[.sss]
 * [2] Day (1-31)
 * [3] Month (1-12)
 * [4] Year (e.g., 2025)  // Some devices may send 2-digit; we'll normalize.
 * [5] Local zone hours (optional, -13..+13)
 * [6] Local zone minutes (optional, 0..59)
 *
 * We compute epochMillis in UTC. If any component is invalid, epochMillis will be null.
 */
class ZdaParser : SentenceParser<ZDA> {
    override val tag: String = "ZDA"

    override fun parse(talker: String, fields: List<String>): ZDA? {
        if (fields.isEmpty()) return null

        val timeRaw = fields.getOrNull(1)?.takeIf { it.isNotBlank() }
        val day     = fields.getOrNull(2).toIntOrNullSafe()?.takeIf { it in 1..31 }
        val month   = fields.getOrNull(3).toIntOrNullSafe()?.takeIf { it in 1..12 }
        val yearRaw = fields.getOrNull(4).toIntOrNullSafe()

        // Normalize year: if 2-digit (rare), bias to 2000+. Otherwise use as-is.
        val year = yearRaw?.let { y ->
            when {
                y >= 100    -> y
                y in 0..79  -> 2000 + y
                y in 80..99 -> 1900 + y
                else        -> null
            }
        }

        val epochMillis = computeEpochMillisUtc(year, month, day, timeRaw)

        return ZDA(
            talker = talker,
            timeRaw = timeRaw,
            day = day,
            month = month,
            year = year,
            epochMillis = epochMillis
        )
    }

    private fun computeEpochMillisUtc(year: Int?, month: Int?, day: Int?, timeStr: String?): Long? {
        if (year == null || month == null || day == null || timeStr.isNullOrBlank()) return null
        val lt = parseHms(timeStr) ?: return null
        return try {
            val zdt = ZonedDateTime.of(year, month, day, lt.hour, lt.minute, lt.second, lt.nano, ZoneOffset.UTC)
            zdt.toInstant().toEpochMilli()
        } catch (_: Exception) {
            null // catches invalid dates like Feb 30, leap-second oddities, etc.
        }
    }

    /** Parses hhmmss[.sss...] into LocalTime. Fractional seconds supported up to nanoseconds. */
    private fun parseHms(timeStr: String): LocalTime? {
        val s = timeStr.trim()
        if (s.length < 6) return null // need at least hhmmss
        val hh = s.substring(0, 2).toIntOrNull() ?: return null
        val mm = s.substring(2, 4).toIntOrNull() ?: return null

        val ssPart = s.substring(4)
        val (ss, nanos) = if (ssPart.startsWith(".")) {
            // malformed (".sss" without seconds); reject
            return null
        } else {
            val dot = ssPart.indexOf('.')
            if (dot >= 0) {
                val secStr = ssPart.substring(0, dot)
                val frac   = ssPart.substring(dot + 1)
                val sec    = secStr.toIntOrNull() ?: return null
                val ns     = frac.padEnd(9, '0').take(9).toIntOrNull() ?: return null
                sec to ns
            } else {
                val sec = ssPart.toIntOrNull() ?: return null
                sec to 0
            }
        }

        if (hh !in 0..23 || mm !in 0..59 || ss !in 0..60) return null // allow 60 for leap second tolerance
        return try {
            LocalTime.of(hh, mm, ss, nanos)
        } catch (_: Exception) {
            null
        }
    }

    private fun String?.toIntOrNullSafe(): Int? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
}
