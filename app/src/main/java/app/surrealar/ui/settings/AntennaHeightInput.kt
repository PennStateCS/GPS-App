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

    /** Text shown for a stored value (always shown, including 0, so the field is never blank). */
    fun format(meters: Double): String = String.format(java.util.Locale.US, "%.3f", meters)
}
