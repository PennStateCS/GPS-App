package app.surrealar.util.diagnostics

import app.surrealar.util.DiagnosticsLogger

/**
 * Heading diagnostics (tag AR): which heading source is in use (rotation vector + declination,
 * magnetic only, GNSS course, north-up) and the compass accuracy dropping to unreliable. Callers
 * compare enums before calling, so the sensor path stays allocation-free.
 */
object HeadingDiagnostics {

    fun accuracyName(accuracy: Int): String = when (accuracy) {
        0 -> "UNRELIABLE"; 1 -> "LOW"; 2 -> "MEDIUM"; 3 -> "HIGH"; -1 -> "NO_CONTACT"; else -> "ACCURACY_$accuracy"
    }

    /** [source] = "rotation vector + declination (LIVE_FIX)", "GNSS course", "north-up"… */
    fun onSource(source: String) {
        DiagnosticsLogger.logOnChange(DiagTags.AR, "heading.source", source) { "Heading source: $source" }
    }

    fun onCompassAccuracy(accuracy: Int) {
        val unreliable = accuracy <= 0
        DiagnosticsLogger.logOnChange(DiagTags.AR, "heading.accuracy", accuracy, if (unreliable) 'W' else 'I') {
            "Compass accuracy ${accuracyName(accuracy)}" + if (unreliable) " (move the device in a figure-8 to calibrate)" else ""
        }
    }

    /** Describes the map/stakeout heading from its parts (only called when one of them changed). */
    fun describe(stakeoutSource: String?, declinationSource: String, magneticOnly: Boolean): String = when (stakeoutSource) {
        "COURSE_OVER_GROUND" -> "GNSS course over ground"
        "NORTH_UP" -> "north-up (no heading)"
        else -> if (magneticOnly) "rotation vector, magnetic only (no declination)" else "rotation vector + declination ($declinationSource)"
    }
}