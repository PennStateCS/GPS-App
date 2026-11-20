package app.surrealar.gnss.nmea.sentence

/**
 * One satellite entry from a `GSV` sentence. Any field may be null when the receiver leaves it blank.
 * Elevation is degrees (0–90), azimuth degrees (0–359), and SNR is C/N0 in dB (higher is stronger;
 * absent for a satellite that is in view but not tracked).
 */
data class GSVSatellite(
    val svid: Int?,
    val elevationDeg: Int?,
    val azimuthDeg: Int?,
    val snrDb: Double?   // C/N0 dB-Hz; some chipsets send decimals ("25.3")
)

/**
 * `$..GSV` — satellites in view for one constellation (talker).
 *
 * GSV is paginated: a full update spans [totalMessages] sentences, this one being [messageNumber]
 * (1-based), each carrying up to four [satellites]. [totalSatellites] is the count across the whole
 * sequence, so a single sentence is a fragment — accumulate by talker until the sequence completes.
 * Fields are nullable because partial sentences are valid on the wire.
 */
data class GSV(
    override val talker: String,
    val totalMessages: Int?,
    val messageNumber: Int?,
    val totalSatellites: Int?,
    val satellites: List<GSVSatellite>,
    /**
     * NMEA 4.10+ signal ID (e.g. 1 = GPS L1 C/A, 8 = GPS L5). Receivers that track several signals
     * (Android dual-frequency chips, Emlid Reach) emit one GSV sequence PER SIGNAL for the same
     * talker; null on older receivers that send a single sequence.
     */
    val signalId: Int? = null
) : NmeaSentence {
    override val tag: String = "GSV"
}
