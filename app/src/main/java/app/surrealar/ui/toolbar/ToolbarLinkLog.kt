package app.surrealar.ui.toolbar

import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags

/** Toolbar link state for the log (GNSS tag), written only when it changes. */
object ToolbarLinkLog {

    enum class Link(val text: String) {
        CONNECTED("connected (live position)"),
        CONNECTING("connecting"),
        STALE("stale (connected, no data)"),
        LOST("lost (last position greyed)"),
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

    fun onState(s: GnssToolbarState) {
        val link = classify(s)
        DiagnosticsLogger.logOnChange(DiagTags.GNSS, "toolbar.link", link, if (link == Link.LOST || link == Link.STALE) 'W' else 'I') {
            "Toolbar link ${link.text} source=${s.sourceText}"
        }
    }
}