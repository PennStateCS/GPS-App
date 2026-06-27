package app.surrealar.domain.coordinates

import app.surrealar.domain.model.Coordinate

// Supporting data classes for coordinate statistics (moved out of the repository contract).
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

/**
 * Pure (no Android, no DAO) statistics over a list of coordinates.
 *
 * Extracted from `CoordinateRepository` so the repository stays focused on data access. New code
 * needing coordinate statistics should call these functions with a coordinate list fetched via the
 * repository, rather than adding statistics methods back to the repository. Behavior is preserved
 * verbatim from the previous repository implementation.
 */
object CoordinateStatsCalculator {

    fun coordinateStats(coordinates: List<Coordinate>): CoordinateStats {
        val providerCounts = coordinates.groupingBy { it.provider }.eachCount()
        val rtkStatusCounts = coordinates.groupingBy { it.rtkStatus ?: "Unknown" }.eachCount()

        val accuracies = coordinates.mapNotNull { it.horizontalAccuracyM }
        val avgAccuracy = if (accuracies.isNotEmpty()) accuracies.average() else null

        val timestamps = coordinates.map { it.timestamp }
        val dateRange = if (timestamps.isNotEmpty()) {
            timestamps.minOrNull()!! to timestamps.maxOrNull()!!
        } else null

        return CoordinateStats(
            totalCount = coordinates.size,
            countByProvider = providerCounts,
            countByRtkStatus = rtkStatusCounts,
            averageAccuracy = avgAccuracy,
            dateRange = dateRange,
            boundingBox = boundingBox(coordinates)
        )
    }

    fun accuracyStats(coordinates: List<Coordinate>): AccuracyStats {
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

    fun providerStatistics(coordinates: List<Coordinate>): Map<String, Int> =
        coordinates.groupingBy { it.provider }.eachCount()

    fun rtkStatusStatistics(coordinates: List<Coordinate>): Map<String, Int> =
        coordinates.groupingBy { it.rtkStatus ?: "Unknown" }.eachCount()

    fun boundingBox(coordinates: List<Coordinate>): BoundingBox? {
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
}
