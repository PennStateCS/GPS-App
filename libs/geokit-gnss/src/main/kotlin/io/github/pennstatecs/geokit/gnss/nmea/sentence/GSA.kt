package io.github.pennstatecs.geokit.gnss.nmea.sentence

/**
 * $..GSA: GNSS DOP and Active Satellites
 * - fixMode: 1 = No fix, 2 = 2D, 3 = 3D (may be null if missing)
 * - usedSvids: up to 12 PRNs/SVIDs used in the solution
 * - pdop/hdop/vdop: dilution of precision values, if provided
 */
public data class GSA(
    override val talker: String,
    val fixMode: Int?,
    val usedSvids: List<Int>,
    val pdop: Double?,
    val hdop: Double?,
    val vdop: Double?,
    /** NMEA 4.10+ GNSS System ID (1=GPS, 2=GLONASS, 3=Galileo, 4=BeiDou, 5=QZSS, 6=NavIC); null if absent. */
    val systemId: Int? = null
) : NmeaSentence {
    override val tag: String = "GSA"
}
