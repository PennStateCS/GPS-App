package app.surrealar.ui.toolbar

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
    val accuracyText: String?,         // "±x.xx m", "±x.xx V ±y.yy m" (external, V reported), or null to hide
    val accuracyLevel: GnssStatusLevel,
    val latLonText: String,            // "lat, lon" or "--"
    val altitudeText: String,          // "x.xx m" or "--" (always shown)
    val correctionAgeText: String?,    // "1.2s" / "13s" correction age (external only), or null to hide
    val batteryVisible: Boolean,       // external → poll/show; internal → hide
    val isExternal: Boolean,
    val isWaiting: Boolean,            // no current-provider fix yet (acquiring/reconnecting)
    val isStale: Boolean,
    val coordLabel: String = "LL",     // "LL" for lat/lon, "NE" for grid coordinates
    val altitudeLabel: String = "ALT", // "ALT", or the height reference ("NAVD88", "ELLIP", "MSL")
    val coordToggleAvailable: Boolean = false, // a grid system is configured: tapping LL/NE switches the display
    val isReplay: Boolean = false,      // an NMEA log is replaying: source shows "REPLAY" with the replay icon
    val accuracyCompactText: String? = null, // H only; used instead of [accuracyText] when the strip is too narrow
    // Spoken descriptions (TalkBack), one per token, built by the mapper. Null = token hidden.
    val sourceDescription: String = "",
    val fixDescription: String = "",
    val satsDescription: String? = null,
    val accuracyDescription: String? = null,
    val ageDescription: String? = null,
    val coordDescription: String = "",
    val altitudeDescription: String = "",
)
