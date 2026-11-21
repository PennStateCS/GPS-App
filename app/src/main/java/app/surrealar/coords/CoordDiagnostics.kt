package app.surrealar.coords

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags

/**
 * COORD lines from the one conversion entry point: zone/projection changes (on change) and
 * failures by type, at most one line per type per minute. Never logs positions.
 */
object CoordDiagnostics {
    const val THROTTLE_MS = 60_000L

    /** Allocation-light change key (no string building per conversion). */
    private data class SystemKey(val h: Any, val d: Any, val z: String?, val v: Any)

    fun onResult(p: ConvertedPosition) {
        DiagnosticsLogger.logOnChange(DiagTags.COORD, "coord.system", SystemKey(p.horizontal, p.datum, p.zoneLabel, p.vertical)) {
            "Coordinate system ${p.horizontal} datum=${p.datum} zone=${p.zoneLabel ?: "none"} vertical=${p.vertical} unit=${p.unit}"
        }
        val reason = p.heightUnavailableReason
        if (reason == lastHeightReason) {
            // Same state: a reminder at most once a minute while heights stay unavailable.
            if (reason != null) {
                val now = DiagnosticsLogger.nowMs()
                if (now - lastHeightLogMs >= THROTTLE_MS) {
                    lastHeightLogMs = now
                    DiagnosticsLogger.w(DiagTags.COORD, "Height still unavailable for ${p.vertical}: $reason")
                }
            }
            return
        }
        val before = lastHeightReason
        lastHeightReason = reason
        lastHeightLogMs = DiagnosticsLogger.nowMs()
        if (reason != null) DiagnosticsLogger.w(DiagTags.COORD, "Height unavailable for ${p.vertical}: $reason")
        else if (before != null) DiagnosticsLogger.i(DiagTags.COORD, "Height available again")
    }

    @Volatile private var lastHeightReason: String? = null
    @Volatile private var lastHeightLogMs = 0L

    /** Tests only. */
    internal fun reset() { lastHeightReason = null; lastHeightLogMs = 0L }

    fun onFailure(s: CoordinateSystemSettings, e: Exception) {
        val type = e.javaClass.simpleName
        DiagnosticsLogger.every(DiagTags.COORD, "coord.fail.$type", THROTTLE_MS, 'W') {
            "Conversion to ${s.horizontal} failed: $type: ${e.message} (repeats suppressed for 60 s)"
        }
    }
}