package app.surrealar.ui.toolbar

import app.surrealar.gnss.status.CorrectionHealth
import app.surrealar.gnss.status.GnssStatusInterpreter

/**
 * Severity level for a toolbar field, resolved to an actual color by the renderer in the Activity.
 * Keeping this as a plain enum (no Android `@ColorInt`/Context) makes the mapper pure and testable.
 */
enum class GnssStatusLevel { NONE, ERROR, WARNING, INFO, SUCCESS }

/**
 * Display-ready snapshot of the live GNSS status. Produced by [GnssToolbarStateMapper] and applied verbatim by
 * [GnssToolbarRenderer]; the GNSS Status report reads the same state. Contains no Android view or decision logic —
 * a `null` text field means "hide that token".
 *
 * The compact toolbar shows only: source, solution, horizontal accuracy (H, "~" when estimated), the most important
 * warning, coordinates, elevation (with its height reference) and a low-battery token. [satelliteText],
 * [correctionAgeText] and the vertical part of [accuracyText] are still computed (report, logs) but no longer drawn
 * in the compact toolbar.
 */
data class GnssToolbarState(
    val sourceText: String,            // "Internal" / "RS2+"
    val fixText: String,               // "GPS" / "Fixed" / "No Fix" / "Waiting" / "--"
    val fixLevel: GnssStatusLevel,     // color of the fix/SOL token
    val satelliteText: String?,        // "used/visible", or null when unknown (report only)
    val accuracyText: String?,         // "±x.xx m", "±x.xx V ±y.yy m" (external, V reported), or null to hide
    val accuracyLevel: GnssStatusLevel,
    val latLonText: String,            // "lat, lon" or "--"
    val altitudeText: String,          // "x.xx m" or "--" (always shown)
    val correctionAgeText: String?,    // "1.2s" / "13s" correction age (external only; report only)
    val batteryVisible: Boolean,       // external receiver: may report a battery (compact toolbar shows it only when low)
    val isExternal: Boolean,
    val isWaiting: Boolean,            // no current-provider fix yet (acquiring/reconnecting)
    val isStale: Boolean,
    val coordLabel: String = "LL",     // "LL" for lat/lon, "NE" for grid coordinates
    val altitudeLabel: String = "ALT", // "ALT", or the height reference ("NAVD88", "h", "H rcvr")
    val coordToggleAvailable: Boolean = false, // a grid system is configured: tapping LL/NE switches the display
    val isReplay: Boolean = false,      // an NMEA log is replaying: source shows "REPLAY" with the replay icon
    val accuracyCompactText: String? = null, // H only, when [accuracyText] also carries V (the compact toolbar shows H only)
    // Spoken descriptions (TalkBack), one per token, built by the mapper. Null = token hidden.
    val sourceDescription: String = "",
    val fixDescription: String = "",
    val satsDescription: String? = null,
    val accuracyDescription: String? = null,
    val ageDescription: String? = null,
    val coordDescription: String = "",
    val altitudeDescription: String = "",
    /** Spoken horizontal accuracy only (the compact toolbar's accuracy token). */
    val accuracyHDescription: String? = null,
    // ── Shared interpretation (GnssStatusInterpreter); empty / null when there is no live external fix ──
    /** Active warnings, highest priority first. The compact toolbar shows the first; the report lists all. */
    val warnings: List<GnssStatusInterpreter.Warning> = emptyList(),
    /** Solution quality of a live external fix; null otherwise. */
    val quality: GnssStatusInterpreter.Quality? = null,
    /** Stabilised correction health (hysteresis), as the compact toolbar uses it. */
    val correctionHealth: CorrectionHealth = CorrectionHealth.OK,
) {
    /** The compact toolbar's warning: the highest-priority one, or null (no permanent "OK"). */
    val primaryWarning: GnssStatusInterpreter.Warning? get() = warnings.firstOrNull()

    /** Horizontal accuracy as the compact toolbar shows it (H only). */
    val compactAccuracyText: String? get() = accuracyCompactText ?: accuracyText

    /** Spoken warnings, e.g. "Warning: Corrections stale. Also: Vertical accuracy invalid"; null when none. */
    val warningDescription: String?
        get() = warnings.takeIf { it.isNotEmpty() }?.let { w ->
            "Warning: ${w.first().text}" + if (w.size > 1) ". Also: " + w.drop(1).joinToString(", ") { it.text } else ""
        }
}
