package app.surrealar.ui.common

import kotlin.math.roundToInt

/**
 * C/N0 values for the satellite chart and skyplot. Rounds (25.9 → 26, not 25), caps at [MAX_DB]
 * (Emlid reports 51–52 dB-Hz; the old cap of 50 clipped them) and returns null for an unknown SNR
 * so callers skip it rather than drawing 0.
 */
object SnrDisplay {
    const val MAX_DB = 55

    fun db(raw: Double?): Int? =
        raw?.takeIf { it.isFinite() && it > 0.0 }?.roundToInt()?.coerceIn(0, MAX_DB)

    /** 0..1 fraction of the display scale for an SNR in dB-Hz (unknown → 0). */
    fun fraction(db: Int?): Float = (db ?: 0).toFloat() / MAX_DB
}
