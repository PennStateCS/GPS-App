package com.example.surveyingapp.gnss.nmea.parse

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

object NmeaDateTimeUtils {

    /**
     * Parses NMEA time string in HHMMSS.sss format
     * @param timeRaw Raw time string from NMEA sentence
     * @return LocalTime object or null if parsing fails
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
     * Parses NMEA date string in DDMMYY format
     * @param dateRaw Raw date string from NMEA sentence
     * @return LocalDate object or null if parsing fails
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
     * Combines NMEA time and date into a UTC epoch timestamp
     * @param timeRaw Raw time string from NMEA sentence
     * @param dateRaw Raw date string from NMEA sentence
     * @return Epoch milliseconds in UTC or null if parsing fails
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
