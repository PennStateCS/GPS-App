package app.surrealar.gnss.nmea.sentence

/**
 * `$..ACCURACY` — MediaTek proprietary accuracy estimate (seen as `$GNACCURACY` on the Samsung
 * SM-X940's internal GNSS). It carries no timestamp; it is emitted once per epoch alongside GGA/RMC.
 *
 * Field meanings were verified on-device against Android's Location for the same chip
 * (2026-11-19): field 1 tracks `Location.accuracy`, field 3 `speedAccuracy`, field 4
 * `verticalAccuracyMeters`. Field 2 was always 0.0 (likely bearing accuracy while stationary).
 *
 * @property hAccM    Horizontal accuracy estimate, metres (field 1); null if blank/invalid.
 * @property speedAccMps Speed accuracy, m/s (field 3).
 * @property vAccM    Vertical accuracy estimate, metres (field 4).
 */
public data class ACCURACY(
    override val talker: String,
    val hAccM: Double?,
    val field2: Double?,
    val speedAccMps: Double?,
    val vAccM: Double?,
) : NmeaSentence {
    override val tag: String = "ACCURACY"
}
