package app.surrealar.ui.rendermap

import kotlin.math.abs
import kotlin.math.ln

/**
 * Pure (Android-free) helpers for the survey meter grid: the display mode, "nice number" metric
 * spacing selection, and label formatting. The actual line drawing lives in [RenderMapFragment];
 * everything here is unit-testable.
 */
enum class MapGridMode(val label: String, val prefKey: String) {
    OFF("Off", "off"),
    AUTO("Auto", "auto"),
    FINE("Fine", "fine"),
    COARSE("Coarse", "coarse");

    fun next(): MapGridMode = when (this) {
        OFF -> AUTO
        AUTO -> FINE
        FINE -> COARSE
        COARSE -> OFF
    }

    companion object {
        val DEFAULT = OFF
        /** Resolves a persisted token (new prefKey or legacy enum name); unknown/null → [DEFAULT]. */
        fun fromPrefKey(value: String?): MapGridMode =
            entries.firstOrNull { it.prefKey.equals(value, true) || it.name.equals(value, true) } ?: DEFAULT
    }
}

object MapGrid {

    /** "Nice number" metric grid spacings, in metres, smallest → largest. */
    val SPACINGS_M = doubleArrayOf(
        0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0,
    )

    /** Hard cap on grid lines per axis to keep the map readable and fast. */
    const val MAX_LINES_PER_AXIS = 80

    /**
     * Auto spacing: the "nice" spacing whose on-screen gap is closest (in ratio) to [targetPx] pixels,
     * so lines sit roughly [targetPx] apart — neither too dense nor too sparse. Clamped to the
     * sequence ends.
     *
     * @param metersPerPixel map scale at the view centre (156543.03 * cos(lat) / 2^zoom for Google Maps)
     */
    fun autoSpacingMeters(metersPerPixel: Double, targetPx: Double = 110.0): Double {
        if (metersPerPixel <= 0.0 || metersPerPixel.isNaN()) return SPACINGS_M.first()
        val desired = targetPx * metersPerPixel
        return SPACINGS_M.minByOrNull { abs(ln(it / desired)) } ?: SPACINGS_M.first()
    }

    /** Index of a spacing in [SPACINGS_M] (nearest match), used to step finer/coarser. */
    fun indexOfSpacing(meters: Double): Int {
        var best = 0
        var bestDiff = Double.MAX_VALUE
        for (i in SPACINGS_M.indices) {
            val diff = abs(SPACINGS_M[i] - meters)
            if (diff < bestDiff) { bestDiff = diff; best = i }
        }
        return best
    }

    /**
     * Spacing (metres) for a mode given the current [autoSpacingMeters], or null for OFF.
     * FINE = one step smaller than Auto; COARSE = two steps larger (both clamped to the sequence).
     */
    fun spacingForMode(mode: MapGridMode, autoSpacingMeters: Double): Double? {
        if (mode == MapGridMode.OFF) return null
        val autoIdx = indexOfSpacing(autoSpacingMeters)
        val idx = when (mode) {
            MapGridMode.FINE -> autoIdx - 1
            MapGridMode.COARSE -> autoIdx + 2
            else -> autoIdx
        }.coerceIn(0, SPACINGS_M.lastIndex)
        return SPACINGS_M[idx]
    }

    /** Human label for a spacing: "0.5 m", "10 m", "1 km", "2 km". */
    fun formatSpacing(meters: Double): String {
        if (meters >= 1000.0) {
            val km = meters / 1000.0
            return trim(km) + " km"
        }
        return trim(meters) + " m"
    }

    /**
     * Full button text for a mode, e.g. "Off", "Auto 10 m", "Fine 5 m", "Coarse 50 m".
     * [spacingMeters] is the resolved spacing for the mode (null/OFF → just "Off").
     */
    fun buttonLabel(mode: MapGridMode, spacingMeters: Double?): String =
        if (mode == MapGridMode.OFF || spacingMeters == null) MapGridMode.OFF.label
        else "${mode.label} ${formatSpacing(spacingMeters)}"

    /** Accessibility description: current state + the next mode the tap will switch to. */
    fun contentDescription(mode: MapGridMode, spacingMeters: Double?): String {
        val nextWord = when (mode.next()) {
            MapGridMode.OFF -> "turn grid off"
            MapGridMode.AUTO -> "switch to auto grid"
            MapGridMode.FINE -> "switch to fine grid"
            MapGridMode.COARSE -> "switch to coarse grid"
        }
        val state = if (mode == MapGridMode.OFF || spacingMeters == null) {
            "Grid off"
        } else {
            "Grid ${mode.label.lowercase()}, ${formatSpacing(spacingMeters)}"
        }
        return "$state. Tap to $nextWord."
    }

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
