package com.example.surveyingapp.domain.coordinates

import com.example.surveyingapp.domain.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateStatsCalculatorTest {

    private fun coord(
        id: String = "1",
        lat: Double = 41.0,
        lon: Double = -75.0,
        timestamp: Long = 0L,
        provider: String = "fused",
        rtkStatus: String? = null,
        horizontalAccuracyM: Double? = null,
        verticalAccuracyM: Double? = null,
        hdop: Double? = null
    ) = Coordinate(
        id = id, name = "P$id", latitude = lat, longitude = lon, altitude = 0.0,
        timestamp = timestamp, icon = "", color = 0,
        provider = provider, rtkStatus = rtkStatus,
        horizontalAccuracyM = horizontalAccuracyM, verticalAccuracyM = verticalAccuracyM, hdop = hdop
    )

    @Test
    fun emptyList_hasNullBoundingBox_andZeroCount() {
        val stats = CoordinateStatsCalculator.coordinateStats(emptyList())
        assertEquals(0, stats.totalCount)
        assertNull(stats.boundingBox)
        assertNull(stats.dateRange)
        assertNull(stats.averageAccuracy)
        assertNull(CoordinateStatsCalculator.boundingBox(emptyList()))
    }

    @Test
    fun coordinateStats_countsAndAveragesAndDateRange() {
        val list = listOf(
            coord(id = "1", timestamp = 100L, provider = "internal", horizontalAccuracyM = 2.0),
            coord(id = "2", timestamp = 300L, provider = "external", rtkStatus = "FIX", horizontalAccuracyM = 4.0),
            coord(id = "3", timestamp = 200L, provider = "internal", horizontalAccuracyM = null)
        )
        val stats = CoordinateStatsCalculator.coordinateStats(list)

        assertEquals(3, stats.totalCount)
        assertEquals(mapOf("internal" to 2, "external" to 1), stats.countByProvider)
        assertEquals(mapOf("Unknown" to 2, "FIX" to 1), stats.countByRtkStatus)
        assertEquals(3.0, stats.averageAccuracy!!, 1e-9) // (2+4)/2, null skipped
        assertEquals(100L to 300L, stats.dateRange)
    }

    @Test
    fun boundingBox_spansMinMax() {
        val list = listOf(
            coord(id = "1", lat = 41.0, lon = -75.0),
            coord(id = "2", lat = 42.0, lon = -74.0),
            coord(id = "3", lat = 40.5, lon = -76.0)
        )
        val bbox = CoordinateStatsCalculator.boundingBox(list)!!
        assertEquals(40.5, bbox.minLat, 1e-9)
        assertEquals(42.0, bbox.maxLat, 1e-9)
        assertEquals(-76.0, bbox.minLon, 1e-9)
        assertEquals(-74.0, bbox.maxLon, 1e-9)
        assertEquals(41.25, bbox.getCenterLat(), 1e-9)
    }

    @Test
    fun accuracyStats_meanBestWorst_andDistributionBuckets() {
        val list = listOf(
            coord(id = "1", horizontalAccuracyM = 0.5, verticalAccuracyM = 1.0, hdop = 1.0),  // 0-1m
            coord(id = "2", horizontalAccuracyM = 3.0, verticalAccuracyM = 2.0, hdop = 2.0),  // 1-5m
            coord(id = "3", horizontalAccuracyM = 12.0, hdop = 3.0)                            // 10m+
        )
        val a = CoordinateStatsCalculator.accuracyStats(list)

        assertEquals((0.5 + 3.0 + 12.0) / 3.0, a.meanHorizontalAccuracy!!, 1e-9)
        assertEquals(0.5, a.bestAccuracy!!, 1e-9)
        assertEquals(12.0, a.worstAccuracy!!, 1e-9)
        assertEquals(1.5, a.meanVerticalAccuracy!!, 1e-9) // (1+2)/2, third is null
        assertEquals(2.0, a.meanHdop!!, 1e-9)
        assertEquals(mapOf("0-1m" to 1, "1-5m" to 1, "10m+" to 1), a.accuracyDistribution)
    }

    @Test
    fun providerAndRtkStatistics_group() {
        val list = listOf(
            coord(id = "1", provider = "internal", rtkStatus = null),
            coord(id = "2", provider = "internal", rtkStatus = "FIX"),
            coord(id = "3", provider = "external", rtkStatus = "FIX")
        )
        assertEquals(mapOf("internal" to 2, "external" to 1), CoordinateStatsCalculator.providerStatistics(list))
        assertEquals(mapOf("Unknown" to 1, "FIX" to 2), CoordinateStatsCalculator.rtkStatusStatistics(list))
    }
}
