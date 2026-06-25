package com.example.surveyingapp.domain.repository

import androidx.lifecycle.LiveData
import com.example.surveyingapp.domain.model.Coordinate
import kotlinx.coroutines.flow.Flow

interface CoordinateRepository {
    val allCoordinates: LiveData<List<Coordinate>>
    val allCoordinatesFlow: Flow<List<Coordinate>>
    val coordinateCountFlow: Flow<Int>

    // Basic CRUD operations
    suspend fun insert(coordinate: Coordinate)
    suspend fun insertAll(coordinates: List<Coordinate>)
    suspend fun update(coordinate: Coordinate)
    suspend fun deleteById(id: String)
    suspend fun deleteAll()

    // Query operations
    suspend fun getAllCoordinatesList(): List<Coordinate>
    suspend fun getById(id: String): Coordinate?
    suspend fun count(): Int
    suspend fun pruneOlderThan(cutoffEpochMs: Long)

    // Spatial queries
    suspend fun getCoordinatesInBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Coordinate>
    suspend fun getCoordinatesNear(lat: Double, lon: Double, radiusMeters: Double): List<Coordinate>
    suspend fun getCoordinatesByProject(projectId: String): List<Coordinate>

    // Filter and search operations
    suspend fun getCoordinatesByProvider(provider: String): List<Coordinate>
    suspend fun getCoordinatesByRtkStatus(rtkStatus: String): List<Coordinate>
    suspend fun getCoordinatesWithMinAccuracy(maxHorizontalAccuracyM: Double): List<Coordinate>
    suspend fun searchCoordinatesByName(nameQuery: String): List<Coordinate>
    suspend fun getCoordinatesByDateRange(startEpochMs: Long, endEpochMs: Long): List<Coordinate>

    // Statistics and analysis
    suspend fun getCoordinateStats(): CoordinateStats
    suspend fun getAccuracyStatistics(): AccuracyStats
    suspend fun getProviderStatistics(): Map<String, Int>
    suspend fun getRtkStatusStatistics(): Map<String, Int>

    // Batch operations
    suspend fun updateMultiple(coordinates: List<Coordinate>)
    suspend fun deleteMultiple(ids: List<String>)
    suspend fun deleteByProvider(provider: String)
    suspend fun deleteByDateRange(startEpochMs: Long, endEpochMs: Long)

    // Export and backup
    suspend fun exportToFormat(format: ExportFormat): String
    suspend fun getCoordinatesForExport(includeRawData: Boolean = false): List<Coordinate>

    // Validation and quality control
    suspend fun validateCoordinate(coordinate: Coordinate): ValidationResult
    suspend fun findDuplicateCoordinates(toleranceMeters: Double = 1.0): List<List<Coordinate>>
    suspend fun getCoordinatesWithIssues(): List<Coordinate>
}

// Supporting data classes for statistics
data class CoordinateStats(
    val totalCount: Int,
    val countByProvider: Map<String, Int>,
    val countByRtkStatus: Map<String, Int>,
    val averageAccuracy: Double?,
    val dateRange: Pair<Long, Long>?,
    val boundingBox: BoundingBox?
)

data class AccuracyStats(
    val meanHorizontalAccuracy: Double?,
    val meanVerticalAccuracy: Double?,
    val meanHdop: Double?,
    val bestAccuracy: Double?,
    val worstAccuracy: Double?,
    val accuracyDistribution: Map<String, Int> // e.g., "0-1m": 50, "1-5m": 30, etc.
)

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)

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
    fun getArea(): Double = getWidth() * getHeight()
    fun contains(lat: Double, lon: Double): Boolean =
        lat in minLat..maxLat && lon in minLon..maxLon
    fun intersects(other: BoundingBox): Boolean =
        !(other.maxLat < minLat || other.minLat > maxLat ||
          other.maxLon < minLon || other.minLon > maxLon)
    fun expand(marginDegrees: Double): BoundingBox =
        BoundingBox(
            minLat - marginDegrees,
            maxLat + marginDegrees,
            minLon - marginDegrees,
            maxLon + marginDegrees
        )
}

enum class ExportFormat {
    CSV,           // Comma-separated values
    KML,           // Google Earth format
    GPX,           // GPS Exchange format
    GEOJSON,       // Geographic JSON
    SHAPEFILE,     // Esri Shapefile
    DXF,           // AutoCAD Drawing Exchange Format
    EXCEL,         // Microsoft Excel format
    NMEA,          // Raw NMEA sentences
    SURVEY_XML     // Custom survey XML format
}

enum class CoordinateQuality {
    EXCELLENT,     // RTK Fixed, high accuracy
    GOOD,          // RTK Float or DGPS
    FAIR,          // Single point with good DOP
    POOR,          // Single point with poor DOP
    INVALID        // Invalid or no fix
}

enum class SortOrder {
    NAME_ASC,
    NAME_DESC,
    DATE_ASC,
    DATE_DESC,
    ACCURACY_BEST_FIRST,
    ACCURACY_WORST_FIRST,
    DISTANCE_FROM_POINT
}

// Query parameters for advanced coordinate searches
data class CoordinateQuery(
    val nameFilter: String? = null,
    val providerFilter: String? = null,
    val rtkStatusFilter: String? = null,
    val dateRange: Pair<Long, Long>? = null,
    val boundingBox: BoundingBox? = null,
    val maxHorizontalAccuracy: Double? = null,
    val minSatellites: Int? = null,
    val qualityFilter: CoordinateQuality? = null,
    val sortOrder: SortOrder = SortOrder.DATE_DESC,
    val limit: Int? = null,
    val offset: Int? = null
)

// Project management for grouping coordinates
data class SurveyProject(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdDate: Long,
    val coordinateCount: Int,
    val boundingBox: BoundingBox?,
    val isActive: Boolean = true
)
