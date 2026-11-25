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
 * Emlid-specific `$..ETC` sentence (Reach RS4 / RS4 Pro) — **tilt compensation data** (Emlid "NMEA
 * Message: ETC", message rev. 1, document rev. 3; https://docs.emlid.com/reachrs4/specifications/nmea-format/).
 *
 * Layout: `$GNETC,<UTC time-of-fix>,<State>,<Notification>,<Heading>,<Tilt Direction>,<Tilt Value>,
 * <n-acc>,<e-acc>,<u-acc>`. The constructor keeps the raw fields exactly as before (diagnostics and the
 * pipeline goldens print them); [decoded] is the documented field-by-field view ([EtcFields], never
 * throws; malformed sentences carry [EtcFields.malformedReason]). Captures range from timestamp-only
 * (`$GNETC,hhmmss.ss,,,,,,,,` = tilt off) to compensating (`$GNETC,hhmmss.ss,30,00,268.660,116.146,
 * 17.651,6.280,6.280,6.991`).
 *
 * ETC never modifies the live fix, coordinates, or AR orientation. Whether the GGA position is the
 * antenna or the compensated pole tip is NOT stated by this sentence — see
 * [io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference].
 *
 * @param timeRaw    UTC time-of-fix (field 1), if present.
 * @param dataFields raw fields after the time (state, notification, heading, tilt direction, tilt value,
 *   n/e/u accuracy coefficients), trimmed.
 */
public data class ETC(
    override val talker: String,
    val timeRaw: String?,
    val dataFields: List<String>,
) : FlaggedNmeaSentence {
    override val tag: String = EmlidNmea.TAG_ETC

    /** The documented fields, decoded once (not part of equals/toString). */
    public val decoded: EtcFields = EtcFields.decode(timeRaw, dataFields)

    /** True when ETC carries any non-blank data beyond the timestamp (tilt is on in some state). */
    public val hasOrientationData: Boolean get() = dataFields.any { it.isNotBlank() }

    /** [EmlidNmea.FLAG_ORIENTATION] when [hasOrientationData]; the fuser remembers it was ever seen. */
    override val diagnosticFlags: Set<String>
        get() = if (hasOrientationData) setOf(EmlidNmea.FLAG_ORIENTATION) else emptySet()
}
