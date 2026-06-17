package com.example.surveyingapp.domain.model

/**
 * Domain model for a captured coordinate point.
 *
 * Semantics:
 * - latitude/longitude in EPSG:4326 (degrees)
 * - altitude = ellipsoidal height (meters)
 * - altitudeMsl (meters) and geoidSeparationM (meters) are optional extras from GGA
 */
data class Coordinate(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestamp: Long,
    val icon: String,
    // Marker color (ARGB). No longer user-selectable — auto-assigned a default (app primary).
    // Retained because map/list/AR marker rendering still reads it; existing rows keep their value.
    val color: Int,
    val provider: String = "fused",
    val rtkStatus: String? = null,
    val satsUsed: Int? = null,
    val satsVisible: Int? = null,                   // Total satellites in view
    val hdop: Double? = null,
    val vDop: Double? = null,                       // Vertical dilution of precision
    val pDop: Double? = null,                       // Position dilution of precision
    val horizontalAccuracyM: Double? = null,
    val verticalAccuracyM: Double? = null,
    val correctionSource: String? = null,
    val correctionAgeS: Double? = null,
    val correctionStationId: String? = null,        // Correction station ID
    val altitudeMsl: Double? = null,
    val geoidSeparationM: Double? = null,
    val speedMps: Double? = null,                   // Speed over ground (m/s)
    val courseDeg: Double? = null,                  // Course over ground (degrees)
    val timestampSource: String? = null,            // Source of timestamp
    val multipathIndex: Double? = null,             // Multipath index
    val crsEpsg: Int? = 4326,
    val easting: Double? = null,
    val northing: Double? = null,
    val utmZone: String? = null,
    val note: String? = null,
    // How the position was acquired (auto-set): internal_gps | external_gnss | model_embedded | map_tap | manual | imported | averaged
    val captureMethod: String? = null,
    val averagedSamples: Int? = null,
    val averageDurationMs: Long? = null,
    val stdLatM: Double? = null,
    val stdLonM: Double? = null,
    val stdAltM: Double? = null,
    val sourceDevice: String? = null,
    val appVersion: String? = null
)
