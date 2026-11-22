package app.surrealar.ui.settings

/**
 * Validates antenna / pole height text typed in Settings (metres — the fields carry an "m" suffix).
 *
 * Returns the height in metres, or null when the text is not a usable height. Callers must then keep
 * the previously stored value and show an error: silently saving 0 (the old behaviour for a cleared
 * field or a lone ".") would store antenna-height altitudes as ground marks.
 */
object AntennaHeightInput {

    /** Largest accepted pole/mount height. Anything above is almost certainly a typo (e.g. mm or ft). */
    const val MAX_M = 30.0

    fun parseMeters(text: CharSequence?): Double? {
        val s = text?.toString()?.trim()?.replace(',', '.') ?: return null
        if (s.isEmpty()) return null
        val v = s.toDoubleOrNull() ?: return null
        if (!v.isFinite() || v < 0.0 || v > MAX_M) return null
        return v
    }

    /** Range of the tablet-camera-below-receiver distance (negative = camera above the receiver). */
    const val CAMERA_BELOW_RANGE_M = 3.0

    /** Tablet camera below the bottom of the receiver, metres, −3…3; null when unusable. */
    fun parseCameraBelowMeters(text: CharSequence?): Double? =
        parseAny(text)?.takeIf { it in -CAMERA_BELOW_RANGE_M..CAMERA_BELOW_RANGE_M }

    /** Antenna phase-centre offset above the bottom of the receiver, metres, 0…0.5; null when unusable. */
    fun parsePhaseCenterOffsetMeters(text: CharSequence?): Double? =
        parseAny(text)?.takeIf { it in 0.0..app.surrealar.settings.model.AntennaSetup.MAX_PHASE_CENTER_OFFSET_M }

    private fun parseAny(text: CharSequence?): Double? {
        val s = text?.toString()?.trim()?.replace(',', '.')?.replace('\u2212', '-') ?: return null
        if (s.isEmpty()) return null
        return s.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** Text shown for a stored value (always shown, including 0, so the field is never blank). */
    fun format(meters: Double): String = String.format(java.util.Locale.US, "%.3f", meters)
}
