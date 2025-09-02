package com.example.surveyingapp.domain.model

/**
 * Domain model for a captured coordinate point
 **/
data class Coordinate(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestamp: Long,
    val icon: String,
    val color: Int,
    val provider: String = "fused",
    val rtkStatus: String? = null,
    val satsUsed: Int? = null,
    val hdop: Double? = null,
    val horizontalAccuracyM: Double? = null,
    val verticalAccuracyM: Double? = null,
    val correctionSource: String? = null,
    val correctionAgeS: Double? = null,
    val altitudeMsl: Double? = null,
    val geoidSeparationM: Double? = null,
    val crsEpsg: Int? = 4326,
    val easting: Double? = null,
    val northing: Double? = null,
    val utmZone: String? = null,
    val note: String? = null,
    val averagedSamples: Int? = null,
    val averageDurationMs: Long? = null,
    val stdLatM: Double? = null,
    val stdLonM: Double? = null,
    val stdAltM: Double? = null,
    val sourceDevice: String? = null,
    val appVersion: String? = null
)
