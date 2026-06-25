package com.example.surveyingapp.ui.toolbar

/**
 * Severity level for a toolbar field, resolved to an actual color by the renderer in the Activity.
 * Keeping this as a plain enum (no Android `@ColorInt`/Context) makes the mapper pure and testable.
 */
enum class GnssStatusLevel { NONE, ERROR, WARNING, INFO, SUCCESS }

/**
 * Display-ready snapshot of the live GNSS toolbar. Produced by [GnssToolbarStateMapper] and applied
 * verbatim by `MainActivity.renderGnssToolbarState`. Contains no Android view or decision logic — a
 * `null` text field means "hide that token".
 */
data class GnssToolbarState(
    val sourceText: String,            // "Internal" / "RS2+"
    val fixText: String,               // "GPS" / "Fixed" / "No Fix" / "Waiting" / "--"
    val fixLevel: GnssStatusLevel,     // color of the fix/SOL token
    val satelliteText: String?,        // "used/visible", or null to hide the sats token
    val accuracyText: String?,         // "±x.xx m", or null to hide the accuracy token
    val accuracyLevel: GnssStatusLevel,
    val latLonText: String,            // "lat, lon" or "--"
    val altitudeText: String,          // "x.xx m" or "--" (always shown)
    val correctionText: String?,       // reserved; no dedicated toolbar token today
    val batteryVisible: Boolean,       // external → poll/show; internal → hide
    val batteryText: String?,          // battery value is rendered by the separate polling pipeline
    val statusLevel: GnssStatusLevel,  // overall level (mirrors fixLevel today)
    val isExternal: Boolean,
    val isWaiting: Boolean,            // no current-provider fix yet (acquiring/reconnecting)
    val isStale: Boolean
)
