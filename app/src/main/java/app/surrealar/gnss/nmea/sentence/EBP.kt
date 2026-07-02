package app.surrealar.gnss.nmea.sentence

/**
 * Emlid-specific `$..EBP` sentence (Reach RS4 / RS4 Pro) — **base position**.
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
data class EBP(
    override val talker: String,
    val baseLat: Double?,
    val baseLon: Double?,
    val baseAltM: Double?,
) : NmeaSentence {
    override val tag: String = "EBP"
}
