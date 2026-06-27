package app.surrealar.gnss.nmea.sentence

/**
 * $..GSV: Satellites in view
 *
 * - totalMessages: number of GSV sentences in this sequence
 * - messageNumber: index of this sentence (1..totalMessages)
 * - totalSatellites: total satellites in view
 * - satellites: up to 4 satellites with SVID, elevation, azimuth, and SNR
 */
data class GSVSatellite(
    val svid: Int?,
    val elevationDeg: Int?,
    val azimuthDeg: Int?,
    val snrDb: Int?
)

data class GSV(
    override val talker: String,
    val totalMessages: Int?,
    val messageNumber: Int?,
    val totalSatellites: Int?,
    val satellites: List<GSVSatellite>
) : NmeaSentence {
    override val tag: String = "GSV"
}
