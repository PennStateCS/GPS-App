package com.example.surveyingapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Data class representing a coordinate point in the surveying app.
 * This is the main data model that stores location information.
 *
 * @Entity tells Room database this is a table called "coordinates"
 * data class automatically generates equals(), hashCode(), toString(), and copy() methods
 */
@Entity(tableName = "coordinates")
data class Coordinate(
    @PrimaryKey val id: String,        // Unique identifier for each coordinate point
    val name: String,                  // User-friendly name for the point (e.g., "Corner of Building A")
    val latitude: Double,              // GPS latitude coordinate (north/south position)
    val longitude: Double,             // GPS longitude coordinate (east/west position)
    val altitude: Double,              // Elevation above sea level in meters
    val timestamp: Long,               // When this coordinate was recorded (milliseconds since 1970)
    val icon: String,                  // Name of the icon to display for this point
    val color: Int                     // Color value (ARGB format) for displaying this point
)
