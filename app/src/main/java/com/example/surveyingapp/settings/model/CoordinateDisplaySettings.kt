package com.example.surveyingapp.settings.model

/**
 * Persistent user preferences controlling how coordinates are displayed and named.
 *
 * These settings affect display only — they do not change stored coordinate values,
 * database records, or any arithmetic used during GNSS capture.
 */
data class CoordinateDisplaySettings(
    /** Whether accuracy labels are shown alongside coordinates in detail views. */
    val showAccuracyIndicators: Boolean = true,

    /** Default prefix for auto-generated coordinate names, e.g. "Point". */
    val defaultNamePrefix: String = "Point",

    /** When true, new coordinates are named "<prefix> 1", "<prefix> 2", etc. */
    val autoIncrementNames: Boolean = true
)
