package com.example.surveyingapp.gnss.settings

/**
 * Persistent user preferences controlling how coordinates and measurements are displayed.
 *
 * These settings affect display only — they do not change stored coordinate values,
 * database records, or any arithmetic used during GNSS capture.
 */
data class CoordinateDisplaySettings(
    /** Coordinate representation shown in lists and overlays.
     *  One of: "DECIMAL_DEGREES", "DMS", "UTM", "LAT_LON_UTM". */
    val format: String = "DECIMAL_DEGREES",

    /** Distance unit for labels, measurements, and distance filters.
     *  One of: "METERS", "FEET". */
    val distanceUnits: String = "METERS",

    /** Whether accuracy circles or bars are shown alongside coordinates. */
    val showAccuracyIndicators: Boolean = true,

    /** Whether RTK status badges (FIX / FLOAT / DGPS / SINGLE) are shown in lists. */
    val showRtkStatusBadges: Boolean = true,

    /** Default prefix for auto-generated coordinate names, e.g. "Point". */
    val defaultNamePrefix: String = "Point",

    /** When true, new coordinates are named "<prefix> 1", "<prefix> 2", etc. */
    val autoIncrementNames: Boolean = true
)
