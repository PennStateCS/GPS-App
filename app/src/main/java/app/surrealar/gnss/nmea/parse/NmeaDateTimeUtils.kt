package app.surrealar.gnss.nmea.parse

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Standalone date/time helpers for NMEA sentences.
 *
 * These utilities are shared by code that needs to parse NMEA time or date strings
 * outside of a specific sentence parser — for example, test helpers or legacy adapter
 * code. The individual sentence parsers (RmcParser, ZdaParser) contain their own
 * equivalent parsing because they have slightly different tolerance requirements
 * (leap-second handling, nanosecond precision, etc.).
 *
 * If you are adding a new sentence parser, prefer writing the parsing inline rather
 * than adding new methods here; keep this object narrow to avoid it growing into
 * a catch-all utilities class.
 */
object NmeaDateTimeUtils {

    /**
     * Parses an NMEA time field (HHMMSS[.sss]) into a [LocalTime].
     *
     * @param timeRaw Raw time string from an NMEA sentence field.
     * @return A [LocalTime] in UTC, or null if the string is missing or malformed.
     */
    fun parseNmeaTime(timeRaw: String?): LocalTime? {
        if (timeRaw.isNullOrEmpty()) return null

        try {
            // Ensure at least 6 digits for HHMMSS
            val timeStr = timeRaw.padEnd(6, '0')
            if (timeStr.length < 6) return null

            val hours = timeStr.substring(0, 2).toInt()
            val minutes = timeStr.substring(2, 4).toInt()
            val seconds = timeStr.substring(4, 6).toInt()

            // Handle fractional seconds if present
            val nanos = if (timeStr.length > 6 && timeStr[6] == '.') {
                val fracStr = timeStr.substring(7).padEnd(9, '0').substring(0, 9)
                fracStr.toInt()
            } else {
                0
            }

            return LocalTime.of(hours, minutes, seconds, nanos)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Parses an NMEA date field (DDMMYY) into a [LocalDate].
     *
     * Two-digit years 00–79 are treated as 2000–2079; years 80–99 as 1980–1999.
     *
     * @param dateRaw Raw date string from an NMEA sentence field.
     * @return A [LocalDate], or null if the string is missing or malformed.
     */
    fun parseNmeaDate(dateRaw: String?): LocalDate? {
        if (dateRaw.isNullOrEmpty() || dateRaw.length != 6) return null

        try {
            val day = dateRaw.substring(0, 2).toInt()
            val month = dateRaw.substring(2, 4).toInt()
            val year = dateRaw.substring(4, 6).toInt()

            // Convert 2-digit year to 4-digit year
            // Assume years 00-79 are 2000-2079, years 80-99 are 1980-1999
            val fullYear = if (year >= 80) 1900 + year else 2000 + year

            return LocalDate.of(fullYear, month, day)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Combines an NMEA time and date field into a UTC epoch timestamp.
     *
     * Both fields must parse successfully; if either is null or malformed the
     * function returns null rather than producing a partially-correct timestamp.
     *
     * @param timeRaw NMEA time string (HHMMSS[.sss]).
     * @param dateRaw NMEA date string (DDMMYY).
     * @return Milliseconds since the Unix epoch in UTC, or null on failure.
     */
    fun parseNmeaDateTime(timeRaw: String?, dateRaw: String?): Long? {
        val time = parseNmeaTime(timeRaw) ?: return null
        val date = parseNmeaDate(dateRaw) ?: return null

        return try {
            val zonedDateTime = ZonedDateTime.of(date, time, ZoneOffset.UTC)
            zonedDateTime.toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}
