package com.example.surveyingapp.gnss.nmea.sentence

import java.time.LocalDate
import java.time.LocalTime

/**
 * $..RMC: Recommended Minimum Navigation Information.
 *
 * Parsers may choose to fill only raw fields (timeRaw/dateRaw) and leave time/date/epochMillis null,
 * or populate them when available. Accumulator decides how to fuse/interpret time.
 */
data class RMC(
    override val talker: String,
    val timeRaw: String?,
    val dateRaw: String?,
    val time: LocalTime?,     // optional parsed time (may be null if parser keeps it raw)
    val date: LocalDate?,     // optional parsed date
    val epochMillis: Long?,   // optional UTC epoch if computed by parser (often kept null for purity)
    val lat: Double?,
    val lon: Double?,
    val speedKnots: Double?,
    val courseDeg: Double?
) : NmeaSentence {
    override val tag: String = "RMC"
}
