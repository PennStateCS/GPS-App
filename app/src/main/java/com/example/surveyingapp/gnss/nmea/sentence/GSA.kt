package com.example.surveyingapp.gnss.nmea.sentence

/**
 * $..GSA: GNSS DOP and Active Satellites
 * - fixMode: 1 = No fix, 2 = 2D, 3 = 3D (may be null if missing)
 * - usedSvids: up to 12 PRNs/SVIDs used in the solution
 * - pdop/hdop/vdop: dilution of precision values, if provided
 */
data class GSA(
    override val talker: String,
    val fixMode: Int?,
    val usedSvids: List<Int>,
    val pdop: Double?,
    val hdop: Double?,
    val vdop: Double?
) : NmeaSentence {
    override val tag: String = "GSA"
}
