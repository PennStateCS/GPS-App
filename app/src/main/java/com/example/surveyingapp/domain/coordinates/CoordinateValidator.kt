package com.example.surveyingapp.domain.coordinates

import com.example.surveyingapp.domain.model.Coordinate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of validating a single coordinate. */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)

/**
 * Pure (no Android, no DAO) coordinate validation and duplicate detection.
 *
 * Extracted from `CoordinateRepository` so the repository stays focused on data access. Use this
 * for any new validation/quality-control needs instead of adding methods back to the repository.
 * Behavior is preserved verbatim from the previous repository implementation.
 */
object CoordinateValidator {

    /** Validates lat/lon bounds and name; emits quality warnings for accuracy/HDOP/satellite count. */
    fun validate(coordinate: Coordinate): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Basic validation
        if (coordinate.latitude !in -90.0..90.0) {
            errors.add("Latitude must be between -90 and 90 degrees")
        }
        if (coordinate.longitude !in -180.0..180.0) {
            errors.add("Longitude must be between -180 and 180 degrees")
        }
        if (coordinate.name.isBlank()) {
            errors.add("Coordinate name cannot be empty")
        }

        // Quality warnings
        coordinate.horizontalAccuracyM?.let { accuracy ->
            if (accuracy > 10.0) {
                warnings.add("Low horizontal accuracy: ${accuracy}m")
            }
        }
        coordinate.hdop?.let { hdop ->
            if (hdop > 5.0) {
                warnings.add("Poor HDOP: $hdop")
            }
        }
        coordinate.satsUsed?.let { sats ->
            if (sats < 4) {
                warnings.add("Low satellite count: $sats")
            }
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    /** Returns the coordinates that fail validation or carry quality warnings. */
    fun coordinatesWithIssues(coordinates: List<Coordinate>): List<Coordinate> =
        coordinates.filter { coordinate ->
            val validation = validate(coordinate)
            !validation.isValid || validation.warnings.isNotEmpty()
        }

    /** Groups coordinates that lie within [toleranceMeters] of each other (groups of size > 1). */
    fun findDuplicates(coordinates: List<Coordinate>, toleranceMeters: Double = 1.0): List<List<Coordinate>> {
        val duplicateGroups = mutableListOf<List<Coordinate>>()
        val processed = mutableSetOf<String>()

        for (coord1 in coordinates) {
            if (coord1.id in processed) continue

            val group = mutableListOf(coord1)
            processed.add(coord1.id)

            for (coord2 in coordinates) {
                if (coord2.id in processed) continue

                val distance = distanceMeters(
                    coord1.latitude, coord1.longitude,
                    coord2.latitude, coord2.longitude
                )

                if (distance <= toleranceMeters) {
                    group.add(coord2)
                    processed.add(coord2.id)
                }
            }

            if (group.size > 1) {
                duplicateGroups.add(group)
            }
        }

        return duplicateGroups
    }

    /** Haversine great-circle distance in meters. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
