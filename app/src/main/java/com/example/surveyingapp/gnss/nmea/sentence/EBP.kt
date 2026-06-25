package com.example.surveyingapp.gnss.nmea.sentence

/**
 * Emlid-specific `$..EBP` sentence (Reach RS4 / RS4 Pro) — **base position**.
 *
 * This is a vendor-custom message; the field order below reflects the documented/assumed Emlid
 * layout and may need reconciling with the official spec. The parser is deliberately tolerant —
 * missing/unknown fields are left null and EBP **never affects the live position fix** (the app has
 * no defined use for the base position yet). Carried purely for diagnostics / future use.
 *
 * Assumed fields (after talker+tag, without `$` / `*CS`):
 *  [1]=UTC time (hhmmss[.sss])
 *  [2]=base latitude (ddmm.mmmm)   [3]=N|S
 *  [4]=base longitude (dddmm.mmmm) [5]=E|W
 *  [6]=base altitude (metres)
 */
data class EBP(
    override val talker: String,
    val timeRaw: String?,
    val baseLat: Double?,
    val baseLon: Double?,
    val baseAltM: Double?,
) : NmeaSentence {
    override val tag: String = "EBP"
}
