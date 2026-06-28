package app.surrealar.ui.viewpoints

import java.util.Locale

/**
 * Surveyor-facing formatting for the Add Coordinate dialog's position-quality display. Pure string
 * formatting only — no GNSS math, no capture rules. It does not estimate accuracy; callers pass in the
 * receiver-reported value, the already-computed estimate, or neither, and this decides the wording.
 */
object PositionQualityFormatter {

    /** Accuracy in metres: two decimals under 1 m, one decimal at or above 1 m. */
    fun accuracyMeters(m: Double): String =
        if (m < 1.0) String.format(Locale.US, "%.2f m", m)
        else String.format(Locale.US, "%.1f m", m)

    /** Receiver-reported accuracy, e.g. "±0.04 m". */
    fun reported(m: Double): String = "±${accuracyMeters(m)}"

    /** Estimated accuracy, e.g. "~0.35 m". */
    fun estimated(m: Double): String = "~${accuracyMeters(m)}"

    /** DOP value, one decimal, e.g. "0.8". */
    fun dop(value: Double): String = String.format(Locale.US, "%.1f", value)

    /** Correction age in seconds, one decimal, e.g. "2.1 sec"; null when missing/negative. */
    fun correctionAgeSec(sec: Double?): String? =
        sec?.takeIf { it >= 0 }?.let { String.format(Locale.US, "%.1f sec", it) }

    enum class AccuracySource { REPORTED, ESTIMATED, UNAVAILABLE }

    /** The horizontal-accuracy line plus its source, so the caller can decide whether to show the note. */
    data class HorizontalAccuracy(val text: String, val source: AccuracySource)

    /**
     * Decides the horizontal-accuracy line from the available inputs.
     *
     * @param reportedM receiver-reported (GST) horizontal accuracy, or null.
     * @param estimatedM an already-computed estimate (DOP/UERE), used only when [reportedM] is null.
     */
    fun horizontalAccuracy(reportedM: Double?, estimatedM: Double?): HorizontalAccuracy = when {
        reportedM != null ->
            HorizontalAccuracy("${reported(reportedM)} reported by receiver", AccuracySource.REPORTED)
        estimatedM != null ->
            HorizontalAccuracy("${estimated(estimatedM)} estimated from HDOP", AccuracySource.ESTIMATED)
        else ->
            HorizontalAccuracy("Not reported by receiver", AccuracySource.UNAVAILABLE)
    }

    /** Vertical-accuracy value when available (reported or estimated), else null so the row is hidden. */
    fun verticalAccuracy(reportedM: Double?, estimatedM: Double?): String? = when {
        reportedM != null -> reported(reportedM)
        estimatedM != null -> estimated(estimatedM)
        else -> null
    }

    /** Compact horizontal-accuracy value for a metric cell: "±0.04 m", "~0.35 m", or "—". */
    fun horizontalAccuracyCompact(reportedM: Double?, estimatedM: Double?): String = when {
        reportedM != null -> reported(reportedM)
        estimatedM != null -> estimated(estimatedM)
        else -> "—"
    }

    /** Short, conditional note shown when accuracy is estimated or unavailable. */
    const val ACCURACY_INFO_NOTE = "Accuracy is informational; not required to save."
}
