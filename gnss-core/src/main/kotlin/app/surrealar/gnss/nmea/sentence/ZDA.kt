package app.surrealar.gnss.nmea.sentence

/**
 * $..ZDA: Time & Date
 *
 * Provides precise UTC time and date, optionally with timezone offsets.
 * Accumulator should decide how to fuse this into the unified timestamp.
 */
data class ZDA(
    override val talker: String,
    val timeRaw: String?,
    val day: Int?,
    val month: Int?,
    val year: Int?,
    val epochMillis: Long?
) : NmeaSentence {
    override val tag: String = "ZDA"
}
