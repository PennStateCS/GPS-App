package app.surrealar.util

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Display text for a direction given as an azimuth (degrees clockwise from true north, 0–360).
 *
 * Survey terminology: an *azimuth* runs 0–360°; a *bearing* is a quadrant angle 0–90° between two
 * cardinal letters, e.g. N 45°18′ E. Pure formatting only — the direction itself comes from geokit's
 * Geodesic.
 */
object AzimuthFormat {

    private const val FULL_CIRCLE_MIN = 360L * 60L
    /** Shown for a non-finite direction (never thrown on the UI thread). */
    const val NO_VALUE = "--"

    /** Normalises any angle into [0, 360). */
    fun normalize(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0.0) r + 360.0 else r
    }

    /**
     * Azimuth with [decimals] decimals and a degree sign: "42°", "45.3°". Rounding never yields
     * "360°" — it wraps to "0°" (or "0.0°").
     */
    fun azimuthText(deg: Double, decimals: Int = 0): String {
        require(decimals in 0..6) { "decimals out of range: $decimals" }
        if (!deg.isFinite()) return NO_VALUE
        val scale = Math.pow(10.0, decimals.toDouble())
        var rounded = (normalize(deg) * scale).roundToLong() / scale
        if (rounded >= 360.0) rounded -= 360.0
        return String.format(Locale.US, "%.${decimals}f°", rounded)
    }

    /**
     * Quadrant bearing to the nearest minute: "N 45°18′ E", "S 45°00′ W". A direction that rounds to
     * a cardinal point is just its letter ("N", "E", "S", "W").
     */
    fun quadrantBearing(azimuthDeg: Double): String {
        if (!azimuthDeg.isFinite()) return NO_VALUE
        val m = Math.floorMod((normalize(azimuthDeg) * 60.0).roundToLong(), FULL_CIRCLE_MIN)
        val quarter = FULL_CIRCLE_MIN / 4
        return when {
            m == 0L -> "N"
            m == quarter -> "E"
            m == 2 * quarter -> "S"
            m == 3 * quarter -> "W"
            m < quarter -> quadrant("N", m, "E")
            m < 2 * quarter -> quadrant("S", 2 * quarter - m, "E")
            m < 3 * quarter -> quadrant("S", m - 2 * quarter, "W")
            else -> quadrant("N", FULL_CIRCLE_MIN - m, "W")
        }
    }

    private fun quadrant(from: String, minutes: Long, to: String): String {
        val q = minutes
        return String.format(Locale.US, "%s %d°%02d′ %s", from, q / 60, q % 60, to)
    }
}
