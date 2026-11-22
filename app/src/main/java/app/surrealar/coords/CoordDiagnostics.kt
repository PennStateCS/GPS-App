package app.surrealar.coords

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags

/**
 * COORD lines from the one conversion entry point: zone/projection changes (on change) and
 * failures by type, at most one line per type per minute. Never logs positions.
 *
 * Only the **live fix** conversion feeds the system/height state: stored points (list rows, map
 * labels, CSV export) sit in other zones or lack heights, and mixing them in made the single state
 * flap ("Height unavailable" / "available again", zone=… lines) on every redraw.
 */
object CoordDiagnostics {
    const val THROTTLE_MS = 60_000L

    /** Reason used while the GEOID18 grid is still loading in the background (not a coverage problem). */
    const val GEOID_NOT_LOADED = "geoid not loaded yet"

    /** Allocation-light change key (no string building per conversion). */
    private data class SystemKey(val h: Any, val d: Any, val z: String?, val v: Any)

    /**
     * [live] = this conversion is of the live fix; anything else is ignored. [geoidLoaded] = false while
     * the grid is still loading, so a missing geoid height is reported as [GEOID_NOT_LOADED] (debug only)
     * rather than as "outside GEOID18 coverage".
     */
    fun onResult(p: ConvertedPosition, live: Boolean, geoidLoaded: Boolean = true) {
        if (!live) return
        DiagnosticsLogger.logOnChange(DiagTags.COORD, "coord.system", SystemKey(p.horizontal, p.datum, p.zoneLabel, p.vertical)) {
            "Coordinate system changed horizontal=${p.horizontal} datum=${p.datum} zone=${p.zoneLabel?.let { "\"$it\"" } ?: "none"} vertical=${p.vertical} unit=${p.unit}"
        }
        val reason = p.heightUnavailableReason?.let { if (!geoidLoaded) GEOID_NOT_LOADED else it }
        if (reason == lastHeightReason) {
            // Same state: a reminder at most once a minute while heights stay unavailable.
            if (reason != null && reason != GEOID_NOT_LOADED) {
                val now = DiagnosticsLogger.nowMs()
                if (now - lastHeightLogMs >= THROTTLE_MS) {
                    lastHeightLogMs = now
                    DiagnosticsLogger.w(DiagTags.COORD, "Height still unavailable: $reason vertical=${p.vertical}")
                }
            }
            return
        }
        val before = lastHeightReason
        lastHeightReason = reason
        lastHeightLogMs = DiagnosticsLogger.nowMs()
        when {
            reason == GEOID_NOT_LOADED -> DiagnosticsLogger.d(DiagTags.COORD) { "Height pending: $reason vertical=${p.vertical}" }
            reason != null -> DiagnosticsLogger.w(DiagTags.COORD, "Height unavailable: $reason vertical=${p.vertical}")
            before == GEOID_NOT_LOADED -> DiagnosticsLogger.d(DiagTags.COORD) { "Height available: geoid loaded" }
            before != null -> DiagnosticsLogger.i(DiagTags.COORD, "Height available again")
        }
    }

    @Volatile private var lastHeightReason: String? = null
    @Volatile private var lastHeightLogMs = 0L

    /** Tests only. */
    internal fun reset() { lastHeightReason = null; lastHeightLogMs = 0L }

    fun onFailure(s: CoordinateSystemSettings, e: Exception) {
        val type = e.javaClass.simpleName
        DiagnosticsLogger.every(DiagTags.COORD, "coord.fail.$type", THROTTLE_MS, 'W', throwable = e) {
            "Coordinate conversion failed horizontal=${s.horizontal} error=$type"
        }
    }
}
