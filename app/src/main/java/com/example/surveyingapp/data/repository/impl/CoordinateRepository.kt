package com.example.surveyingapp.data.repository.impl

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import com.example.surveyingapp.data.local.dao.CoordinateDao
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.domain.repository.CoordinateRepository
import com.example.surveyingapp.domain.repository.CoordinateStats
import com.example.surveyingapp.domain.repository.AccuracyStats
import com.example.surveyingapp.domain.repository.ValidationResult
import com.example.surveyingapp.domain.repository.ExportFormat
import com.example.surveyingapp.domain.repository.BoundingBox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.*

// Use the actual mapper package you shared:
import com.example.surveyingapp.data.repository.mapper.toDomain
import com.example.surveyingapp.data.repository.mapper.toEntity

class CoordinateRepositoryImpl(
    private val coordinateDao: CoordinateDao
) : CoordinateRepository {

    // LiveData stream (legacy/Views)
    override val allCoordinates: LiveData<List<Coordinate>> =
        coordinateDao.getAllCoordinates().map { rows -> rows.map { it.toDomain() } }

    // Flow stream (preferred for coroutines/Compose)
    override val allCoordinatesFlow: Flow<List<Coordinate>> =
        coordinateDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    // Basic CRUD operations
    override suspend fun insert(coordinate: Coordinate) {
        coordinateDao.insert(coordinate.toEntity())
    }

    override suspend fun insertAll(coordinates: List<Coordinate>) {
        coordinateDao.insertAll(coordinates.map { it.toEntity() })
    }

    override suspend fun update(coordinate: Coordinate) {
        coordinateDao.update(coordinate.toEntity())
    }

    override suspend fun deleteById(id: String) {
        coordinateDao.deleteById(id)
    }

    override suspend fun deleteAll() {
        coordinateDao.deleteAll()
    }

    // Query operations
    override suspend fun getAllCoordinatesList(): List<Coordinate> =
        coordinateDao.getAllCoordinatesList().map { it.toDomain() }

    override suspend fun getById(id: String): Coordinate? =
        coordinateDao.getById(id)?.toDomain()

    override suspend fun count(): Int = coordinateDao.count()

    override suspend fun pruneOlderThan(cutoffEpochMs: Long) {
        coordinateDao.deleteOlderThan(cutoffEpochMs)
    }

    // Spatial queries
    override suspend fun getCoordinatesInBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Coordinate> {
        return getAllCoordinatesList().filter { coord ->
            coord.latitude in minLat..maxLat && coord.longitude in minLon..maxLon
        }
    }

    override suspend fun getCoordinatesNear(lat: Double, lon: Double, radiusMeters: Double): List<Coordinate> {
        return getAllCoordinatesList().filter { coord ->
            calculateDistance(lat, lon, coord.latitude, coord.longitude) <= radiusMeters
        }
    }

    override suspend fun getCoordinatesByProject(projectId: String): List<Coordinate> {
        // TODO: Implement when project ID field is added to database schema
        return getAllCoordinatesList().filter { coordinate ->
            coordinate.note?.contains("Project: $projectId") == true
        }
    }

    // Filter and search operations
    override suspend fun getCoordinatesByProvider(provider: String): List<Coordinate> {
        return getAllCoordinatesList().filter { it.provider == provider }
    }

    override suspend fun getCoordinatesByRtkStatus(rtkStatus: String): List<Coordinate> {
        return getAllCoordinatesList().filter { it.rtkStatus == rtkStatus }
    }

    override suspend fun getCoordinatesWithMinAccuracy(maxHorizontalAccuracyM: Double): List<Coordinate> {
        return getAllCoordinatesList().filter { coordinate ->
            val accuracy = coordinate.horizontalAccuracyM
            accuracy != null && accuracy <= maxHorizontalAccuracyM
        }
    }

    override suspend fun searchCoordinatesByName(nameQuery: String): List<Coordinate> {
        return getAllCoordinatesList().filter {
            it.name.contains(nameQuery, ignoreCase = true)
        }
    }

    override suspend fun getCoordinatesByDateRange(startEpochMs: Long, endEpochMs: Long): List<Coordinate> {
        return getAllCoordinatesList().filter {
            it.timestamp in startEpochMs..endEpochMs
        }
    }

    // Statistics and analysis
    override suspend fun getCoordinateStats(): CoordinateStats {
        val coordinates = getAllCoordinatesList()
        val providerCounts = coordinates.groupingBy { it.provider }.eachCount()
        val rtkStatusCounts = coordinates.groupingBy { it.rtkStatus ?: "Unknown" }.eachCount()

        val accuracies = coordinates.mapNotNull { it.horizontalAccuracyM }
        val avgAccuracy = if (accuracies.isNotEmpty()) accuracies.average() else null

        val timestamps = coordinates.map { it.timestamp }
        val dateRange = if (timestamps.isNotEmpty()) {
            timestamps.minOrNull()!! to timestamps.maxOrNull()!!
        } else null

        val boundingBox = calculateBoundingBox(coordinates)

        return CoordinateStats(
            totalCount = coordinates.size,
            countByProvider = providerCounts,
            countByRtkStatus = rtkStatusCounts,
            averageAccuracy = avgAccuracy,
            dateRange = dateRange,
            boundingBox = boundingBox
        )
    }

    override suspend fun getAccuracyStatistics(): AccuracyStats {
        val coordinates = getAllCoordinatesList()
        val horizontalAccuracies = coordinates.mapNotNull { it.horizontalAccuracyM }
        val verticalAccuracies = coordinates.mapNotNull { it.verticalAccuracyM }
        val hdops = coordinates.mapNotNull { it.hdop }

        val distribution = mutableMapOf<String, Int>()
        horizontalAccuracies.forEach { accuracy ->
            val key = when {
                accuracy <= 1.0 -> "0-1m"
                accuracy <= 5.0 -> "1-5m"
                accuracy <= 10.0 -> "5-10m"
                else -> "10m+"
            }
            distribution[key] = (distribution[key] ?: 0) + 1
        }

        return AccuracyStats(
            meanHorizontalAccuracy = if (horizontalAccuracies.isNotEmpty()) horizontalAccuracies.average() else null,
            meanVerticalAccuracy = if (verticalAccuracies.isNotEmpty()) verticalAccuracies.average() else null,
            meanHdop = if (hdops.isNotEmpty()) hdops.average() else null,
            bestAccuracy = horizontalAccuracies.minOrNull(),
            worstAccuracy = horizontalAccuracies.maxOrNull(),
            accuracyDistribution = distribution
        )
    }

    override suspend fun getProviderStatistics(): Map<String, Int> {
        return getAllCoordinatesList().groupingBy { it.provider }.eachCount()
    }

    override suspend fun getRtkStatusStatistics(): Map<String, Int> {
        return getAllCoordinatesList().groupingBy { it.rtkStatus ?: "Unknown" }.eachCount()
    }

    // Batch operations
    override suspend fun updateMultiple(coordinates: List<Coordinate>) {
        coordinates.forEach { update(it) }
    }

    override suspend fun deleteMultiple(ids: List<String>) {
        ids.forEach { deleteById(it) }
    }

    override suspend fun deleteByProvider(provider: String) {
        val coordinatesToDelete = getCoordinatesByProvider(provider)
        coordinatesToDelete.forEach { deleteById(it.id) }
    }

    override suspend fun deleteByDateRange(startEpochMs: Long, endEpochMs: Long) {
        val coordinatesToDelete = getCoordinatesByDateRange(startEpochMs, endEpochMs)
        coordinatesToDelete.forEach { deleteById(it.id) }
    }

    // Export and backup
    override suspend fun exportToFormat(format: ExportFormat): String {
        val coordinates = getAllCoordinatesList()
        return when (format) {
            ExportFormat.CSV -> exportToCsv(coordinates)
            ExportFormat.KML -> exportToKml(coordinates)
            ExportFormat.GPX -> exportToGpx(coordinates)
            ExportFormat.GEOJSON -> exportToGeoJson(coordinates)
            else -> throw UnsupportedOperationException("Export format $format not yet implemented")
        }
    }

    override suspend fun getCoordinatesForExport(includeRawData: Boolean): List<Coordinate> {
        // For now, return all coordinates. In the future, this could filter based on includeRawData
        return getAllCoordinatesList()
    }

    // Validation and quality control
    override suspend fun validateCoordinate(coordinate: Coordinate): ValidationResult {
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

    override suspend fun findDuplicateCoordinates(toleranceMeters: Double): List<List<Coordinate>> {
        val coordinates = getAllCoordinatesList()
        val duplicateGroups = mutableListOf<List<Coordinate>>()
        val processed = mutableSetOf<String>()

        for (coord1 in coordinates) {
            if (coord1.id in processed) continue

            val group = mutableListOf(coord1)
            processed.add(coord1.id)

            for (coord2 in coordinates) {
                if (coord2.id in processed) continue

                val distance = calculateDistance(
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

    override suspend fun getCoordinatesWithIssues(): List<Coordinate> {
        return getAllCoordinatesList().filter { coordinate ->
            val validation = validateCoordinate(coordinate)
            !validation.isValid || validation.warnings.isNotEmpty()
        }
    }

    // Helper methods
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private fun calculateBoundingBox(coordinates: List<Coordinate>): BoundingBox? {
        if (coordinates.isEmpty()) return null

        val lats = coordinates.map { it.latitude }
        val lons = coordinates.map { it.longitude }

        return BoundingBox(
            minLat = lats.minOrNull()!!,
            maxLat = lats.maxOrNull()!!,
            minLon = lons.minOrNull()!!,
            maxLon = lons.maxOrNull()!!
        )
    }

    private fun exportToCsv(coordinates: List<Coordinate>): String {
        val header = "ID,Name,Latitude,Longitude,Altitude,Timestamp,Provider,RTK_Status,Accuracy"
        val rows = coordinates.map { coord ->
            "${coord.id},${coord.name},${coord.latitude},${coord.longitude},${coord.altitude},${coord.timestamp},${coord.provider},${coord.rtkStatus ?: ""},${coord.horizontalAccuracyM ?: ""}"
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    private fun exportToKml(coordinates: List<Coordinate>): String {
        val placmarks = coordinates.joinToString("\n") { coord ->
            """
            <Placemark>
                <name>${coord.name}</name>
                <Point>
                    <coordinates>${coord.longitude},${coord.latitude},${coord.altitude}</coordinates>
                </Point>
            </Placemark>
            """.trimIndent()
        }

        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
            <Document>
                <name>Survey Coordinates</name>
                $placmarks
            </Document>
        </kml>
        """.trimIndent()
    }

    private fun exportToGpx(coordinates: List<Coordinate>): String {
        val waypoints = coordinates.joinToString("\n") { coord ->
            """<wpt lat="${coord.latitude}" lon="${coord.longitude}">
                <name>${coord.name}</name>
                <ele>${coord.altitude}</ele>
            </wpt>"""
        }

        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="SurveyingApp">
            $waypoints
        </gpx>
        """.trimIndent()
    }

    private fun exportToGeoJson(coordinates: List<Coordinate>): String {
        val features = coordinates.joinToString(",\n") { coord ->
            """
            {
                "type": "Feature",
                "geometry": {
                    "type": "Point",
                    "coordinates": [${coord.longitude}, ${coord.latitude}, ${coord.altitude}]
                },
                "properties": {
                    "name": "${coord.name}",
                    "provider": "${coord.provider}",
                    "rtkStatus": "${coord.rtkStatus ?: ""}",
                    "accuracy": ${coord.horizontalAccuracyM ?: "null"}
                }
            }
            """.trimIndent()
        }

        return """
        {
            "type": "FeatureCollection",
            "features": [
                $features
            ]
        }
        """.trimIndent()
    }
}
