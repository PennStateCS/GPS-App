package io.github.pennstatecs.geokit.receiver.emlid.nmea

import io.github.pennstatecs.geokit.gnss.nmea.sentence.FlaggedNmeaSentence
import io.github.pennstatecs.geokit.gnss.nmea.sentence.NmeaSentence

/**
 * Emlid-specific `$..EBP` sentence (Reach RS4 / RS4 Pro, also seen from RS2+) — **base position**.
 *
 * Field order confirmed against RS4 captures: the sentence carries the base lat/lon/alt with **no
 * leading time field** (e.g. `$GNEBP,4118.3990546,N,07601.0359618,W,374.038,M`). The parser is
 * deliberately tolerant — missing/unknown fields are left null and EBP **never affects the live
 * position fix** (it is the base, not the rover). Carried purely for diagnostics / future use, and
 * intentionally NOT surfaced as coordinates in the sanitized diagnostic report.
 *
 * Fields (after talker+tag, without `$` / `*CS`):
 *  [1]=base latitude (ddmm.mmmm)   [2]=N|S
 *  [3]=base longitude (dddmm.mmmm) [4]=E|W
 *  [5]=base altitude (metres)      [6]=unit ('M')
 */
public data class EBP(
    override val talker: String,
    val baseLat: Double?,
    val baseLon: Double?,
    val baseAltM: Double?,
) : NmeaSentence {
    override val tag: String = EmlidNmea.TAG_EBP
}

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
public data class ETC(
    override val talker: String,
    val timeRaw: String?,
    val dataFields: List<String>,
) : FlaggedNmeaSentence {
    override val tag: String = EmlidNmea.TAG_ETC

    /** True when ETC carries any non-blank data beyond the timestamp (orientation/IMU-style output). */
    public val hasOrientationData: Boolean get() = dataFields.any { it.isNotBlank() }

    /** [EmlidNmea.FLAG_ORIENTATION] when [hasOrientationData]; the fuser remembers it was ever seen. */
    override val diagnosticFlags: Set<String>
        get() = if (hasOrientationData) setOf(EmlidNmea.FLAG_ORIENTATION) else emptySet()
}
