package com.example.surveyingapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.surveyingapp.domain.model.CorrectionSource
import com.example.surveyingapp.domain.model.Provider
import com.example.surveyingapp.domain.model.RtkStatus

/**
 * Each row represents ONE captured point (when user taps +).
 * altitude = ellipsoidal height (from RS2+/fused). Use altitudeMsl if you compute MSL.
 */
@Entity(
    tableName = "coordinates",
    indices = [
        Index("timestamp"),                      // common query
        Index("provider"),                       // quick filter by source
        Index(value = ["latitude","longitude"])  // lightweight spatial filter
    ]
)
data class CoordinateEntity(
    @PrimaryKey val id: String,            // Unique ID (e.g., UUID)
    val name: String,                      // Friendly label
    val latitude: Double,                  // WGS84 lat
    val longitude: Double,                 // WGS84 lon

    // IMPORTANT: Ellipsoidal height (as output by RS2+/NMEA GGA). Keep existing field.
    val altitude: Double,                  // ellipsoidal meters

    val timestamp: Long,                   // epoch millis
    val icon: String,                      // UI icon name
    val color: Int,                        // ARGB

    // --- Provenance & quality (now type-safe enums) ---
    val provider: Provider = Provider.INTERNAL,     // INTERNAL | RS2_BT | RS2_TCP | OTHER
    val rtkStatus: RtkStatus? = null,               // FIX | FLOAT | DGPS | SINGLE | INVALID
    val satsUsed: Int? = null,
    val hdop: Double? = null,
    val horizontalAccuracyM: Double? = null,
    val verticalAccuracyM: Double? = null,
    val correctionSource: CorrectionSource? = null, // NTRIP | LORA | BASE_TCP | UNKNOWN
    val correctionAgeS: Double? = null,             // seconds

    // --- Heights / CRS ---
    val altitudeMsl: Double? = null,       // orthometric (if computed)
    val geoidSeparationM: Double? = null,  // ellipsoidal - MSL
    val crsEpsg: Int? = 4326,              // typically 4326

    // Optional projected snapshot (if you display/export it)
    val easting: Double? = null,
    val northing: Double? = null,
    val utmZone: String? = null,           // e.g., "17T"

    // --- Survey/Audit ---
    val note: String? = null,
    val averagedSamples: Int? = null,
    val averageDurationMs: Long? = null,
    val stdLatM: Double? = null,           // from GST if present
    val stdLonM: Double? = null,
    val stdAltM: Double? = null,

    // --- Device/App provenance (optional) ---
    val sourceDevice: String? = null,      // e.g., RS2+ serial/model
    val appVersion: String? = null         // for export/audit
)
