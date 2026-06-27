package app.surrealar.gnss.nmea.sentence

/**
 * Emlid-specific `$..ETC` sentence (Reach RS4 / RS4 Pro) — **tilt compensation** state.
 *
 * Vendor-custom message; the field order reflects the documented/assumed Emlid layout and may need
 * reconciling with the official spec. The parser is tolerant — missing/unknown fields are left null.
 * ETC is **diagnostics-only**: it does NOT modify coordinates or the live fix in this build.
 *
 * Assumed fields (after talker+tag, without `$` / `*CS`):
 *  [1]=UTC time (hhmmss[.sss])
 *  [2]=tilt status (raw token, e.g. "0"/"1" or a state word)
 *  [3]=tilt angle (degrees from vertical)
 *  [4]=heading / azimuth (degrees)
 *  [5]=warning / quality code (raw token)
 */
data class ETC(
    override val talker: String,
    val timeRaw: String?,
    val tiltStatusRaw: String?,
    val tiltAngleDeg: Double?,
    val headingDeg: Double?,
    val warningRaw: String?,
) : NmeaSentence {
    override val tag: String = "ETC"

    /** True when a non-zero/non-blank tilt status is reported (best-effort, for diagnostics). */
    val tiltActive: Boolean
        get() = tiltStatusRaw?.trim()?.let { it.isNotEmpty() && it != "0" && !it.equals("off", true) } ?: false
}
