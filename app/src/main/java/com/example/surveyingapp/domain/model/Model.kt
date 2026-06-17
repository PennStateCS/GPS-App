package com.example.surveyingapp.domain.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Model(
    val id: String,
    val name: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val dateAdded: Long,
    val description: String? = null,

    // File metadata
    val fileType: FileType,
    val mimeType: String? = null,
    val checksum: String? = null,
    val lastModified: Long? = null,

    // Thumbnail for model preview
    val thumbnailFileName: String? = null,
    val thumbnailFilePath: String? = null,

    // Embedded geographic origin (from georeferenced GLBs, captured at import)
    val embeddedLatitude: Double? = null,
    val embeddedLongitude: Double? = null,
    val embeddedAltitudeM: Double? = null,

    // Survey-specific metadata
    val projectId: String? = null,
    val surveyDate: Long? = null,
    val coordinateCount: Int? = null,
    val boundingBox: BoundingBox? = null,

    // File status and validation
    val isValid: Boolean = true,
    val validationErrors: List<String> = emptyList(),
    val isImported: Boolean = false,
    val importDate: Long? = null,

    // File tags and categorization
    val tags: Set<String> = emptySet(),
    val category: FileCategory = FileCategory.OTHER,

    // Access control
    val isReadOnly: Boolean = false,
    val createdBy: String? = null,
    val lastAccessedDate: Long? = null
) {
    fun getFormattedSize(): String {
        val kb = fileSize / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.1f KB", kb)
            else -> "$fileSize bytes"
        }
    }

    fun getFormattedDateAdded(): String {
        val instant = Instant.ofEpochMilli(dateAdded)
        val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        return dateTime.format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm"))
    }

    fun getFileExtension(): String {
        return fileName.substringAfterLast('.', "")
    }

    /** Thumbnail helpers **/
    fun hasThumbnail(): Boolean = !thumbnailFilePath.isNullOrBlank() || !thumbnailFileName.isNullOrBlank()

    fun getThumbnailExtension(): String? =
        thumbnailFileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }

    fun isRecentlyAdded(hoursThreshold: Long = 24): Boolean {
        val now = System.currentTimeMillis()
        return (now - dateAdded) < (hoursThreshold * 60 * 60 * 1000)
    }

    fun hasValidationIssues(): Boolean = validationErrors.isNotEmpty()

    fun isLargeFile(sizeMB: Double = 100.0): Boolean {
        return (fileSize / (1024.0 * 1024.0)) > sizeMB
    }
}

enum class FileType {
    COORDINATE_DATA,    // CSV, KML, GPX coordinate files
    NMEA_LOG,          // NMEA sentence logs
    SURVEY_PROJECT,    // Survey project files
    CAD_DRAWING,       // DWG, DXF files
    IMAGE,             // Photos, orthoimages
    POINT_CLOUD,        // LAS, LAZ files
    MESH_MODEL,        // 3D mesh models
    MAP_TILE,          // Map tiles, rasters
    REPORT,            // PDF reports, documents
    DATABASE,          // SQLite, database exports
    CONFIGURATION,     // Config files, settings
    OTHER              // Unspecified file type
}

enum class FileCategory {
    FIELD_DATA,        // Data collected in the field
    PROCESSED_DATA,    // Post-processed results
    REFERENCE_DATA,    // Base maps, control points
    DELIVERABLES,      // Final outputs, reports
    ARCHIVE,           // Historical data
    TEMP,              // Temporary files
    OTHER              // Miscellaneous
}

data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    fun getCenterLat(): Double = (minLat + maxLat) / 2.0
    fun getCenterLon(): Double = (minLon + maxLon) / 2.0
    fun getWidth(): Double = maxLon - minLon
    fun getHeight(): Double = maxLat - minLat
}
