package com.example.surveyingapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val dateAdded: Long,
    val description: String? = null,

    // Thumbnail fields (nullable for backward compatibility)
    val thumbnailFileName: String? = null,
    val thumbnailFilePath: String? = null,

    // Embedded geographic origin extracted from georeferenced GLBs at import time.
    // Persisted because reprojection recenters the geometry, erasing the in-file signal —
    // these let the coordinate-linking flow still offer "Use Model Location".
    val embeddedLatitude: Double? = null,
    val embeddedLongitude: Double? = null,
    val embeddedAltitudeM: Double? = null
)
