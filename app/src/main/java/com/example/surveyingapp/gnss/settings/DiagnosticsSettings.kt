package com.example.surveyingapp.gnss.settings

/**
 * Persistent user preferences controlling which diagnostics panels are visible.
 */
data class DiagnosticsSettings(
    /** Whether the raw NMEA sentence history panel is visible in Diagnostics. */
    val showRawNmea: Boolean = false,

    /** Whether the satellite sky panel is visible in Diagnostics. */
    val showSatelliteDiagnostics: Boolean = true
)
