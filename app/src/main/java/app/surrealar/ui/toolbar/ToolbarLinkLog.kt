package app.surrealar.ui.toolbar

import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags

/**
 * Toolbar link state for the log (GNSS tag), written only when it changes and the new state has
 * held for [STABLE_MS] — the toolbar re-renders every 250 ms, and short NMEA gaps would otherwise
 * write STALE then CONNECTED again.
 */
object ToolbarLinkLog {

    /** How long a link state must hold before it is logged. */
    const val STABLE_MS = 4_000L

    enum class Link(val text: String) {
        CONNECTED("connected: live position"),
        CONNECTING("connecting"),
        STALE("stale: connected, no data"),
        LOST("lost: last position greyed"),
        NO_FIX("no fix"),
        LOCATION_OFF("location off"),
    }

    fun classify(s: GnssToolbarState): Link = when {
        s.isStale -> Link.LOST
        s.fixText.startsWith("No data") -> Link.STALE
        s.fixText.startsWith("Connecting") -> Link.CONNECTING
        s.fixText == "Location off" -> Link.LOCATION_OFF
        s.fixText == "No fix" || s.latLonText == "--" -> Link.NO_FIX
        else -> Link.CONNECTED
    }

    /** Only a lost link (last position greyed) is a warning; a brief data gap (STALE) is info. */
    fun level(link: Link): Char = if (link == Link.LOST) 'W' else 'I'

    /** Logs [s]'s link state if it changed and held for [STABLE_MS]. Returns true if it wrote. */
    fun onState(s: GnssToolbarState): Boolean {
        val link = classify(s)
        return DiagnosticsLogger.logOnChange(DiagTags.GNSS, "toolbar.link", link, level(link), stableMs = STABLE_MS) {
            "Toolbar link ${link.text} source=\"${s.sourceText}\""
        }
    }
}
