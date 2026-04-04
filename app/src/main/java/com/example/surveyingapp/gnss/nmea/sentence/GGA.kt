package com.example.surveyingapp.gnss.nmea.sentence

/**
 * $..GGA: Global Positioning System Fix Data
 *
 * - timeRaw: UTC time of fix (hhmmss[.sss])
 * - lat/lon: decimal degrees (nullable if parse failed)
 * - fixQuality: 0 = invalid, 1 = GPS fix, 2 = DGPS fix, etc.
 * - numSatellites: satellites used in solution
 * - hdop: horizontal dilution of precision
 * - altitudeMsl: altitude in meters above mean sea level
 * - geoidSeparation: separation between ellipsoid and MSL
 * - diffAge: age of differential corrections (seconds)
 * - stationId: differential reference station ID
 */
data class GGA(
    override val talker: String,
    val timeRaw: String?,
    val lat: Double?,
    val lon: Double?,
    val fixQuality: Int?,
    val satsUsed: Int?,
    val hdop: Double?,
    val altMsl: Double?,
    val geoidSeparation: Double?,
    val diffAge: Double?,
    val stationId: String?
) : NmeaSentence {
    override val tag: String = "GGA"
}
