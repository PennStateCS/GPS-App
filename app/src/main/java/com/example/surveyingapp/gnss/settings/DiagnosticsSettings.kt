package com.example.surveyingapp.gnss.settings

/**
 * Persistent user preferences controlling diagnostics output and logging behavior.
 *
 * All high-rate logging defaults to disabled. Enabling granular logging options
 * (logRawNmeaLines, logParsedFixes, logSatelliteDetails) may increase storage use
 * and should be disabled during normal field work.
 */
data class DiagnosticsSettings(
    /** Whether NMEA sentences are written to a rolling log file. */
    val nmeaLoggingEnabled: Boolean = false,

    /** Maximum size in megabytes before the NMEA log file rolls over. */
    val nmeaLogMaxFileSizeMB: Int = 5,

    /** Whether the raw NMEA diagnostics panel is visible in the live GNSS display. */
    val showRawNmea: Boolean = false,

    /** Whether the satellite diagnostics panel is visible in the live GNSS display. */
    val showSatelliteDiagnostics: Boolean = false,

    /** Advanced: log every raw NMEA sentence to Logcat. High-rate; disable for field use. */
    val logRawNmeaLines: Boolean = false,

    /** Advanced: log every parsed Fix struct to Logcat. High-rate; disable for field use. */
    val logParsedFixes: Boolean = false,

    /** Advanced: log satellite/GSV epoch details to Logcat. High-rate; disable for field use. */
    val logSatelliteDetails: Boolean = false
)
