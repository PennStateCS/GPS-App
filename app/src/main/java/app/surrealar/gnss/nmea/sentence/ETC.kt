package app.surrealar.gnss.nmea.sentence

/**
 * Emlid-specific `$..ETC` sentence (Reach RS4 / RS4 Pro).
 *
 * The field semantics are **not documented** by Emlid in this build. In captures the message ranges
 * from timestamp-only (`$GNETC,hhmmss.ss,,,,,,,,`) to an orientation/IMU-style payload
 * (`$GNETC,hhmmss.ss,30,00,268.660,116.146,17.651,6.280,6.280,6.991`). Because the layout is
 * unverified we deliberately do **not** map specific fields to heading/tilt/roll/pitch — guessing
 * would risk feeding wrong orientation into AR later. The raw data fields are carried for diagnostics
 * only. ETC never modifies the live fix, coordinates, or AR orientation in this build.
 *
 * @param timeRaw    UTC time (field 1), if present.
 * @param dataFields raw fields after the timestamp, unparsed (semantics undocumented).
 */
data class ETC(
    override val talker: String,
    val timeRaw: String?,
    val dataFields: List<String>,
) : NmeaSentence {
    override val tag: String = "ETC"

    /** True when ETC carries any non-blank data beyond the timestamp (orientation/IMU-style output). */
    val hasOrientationData: Boolean get() = dataFields.any { it.isNotBlank() }
}
