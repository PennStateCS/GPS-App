package com.example.surveyingapp.domain.coordinates

import com.example.surveyingapp.domain.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateValidatorTest {

    private fun coord(
        id: String = "1",
        name: String = "P1",
        lat: Double = 41.0,
        lon: Double = -75.0,
        horizontalAccuracyM: Double? = null,
        hdop: Double? = null,
        satsUsed: Int? = null
    ) = Coordinate(
        id = id, name = name, latitude = lat, longitude = lon, altitude = 0.0,
        timestamp = 0L, icon = "", color = 0,
        horizontalAccuracyM = horizontalAccuracyM, hdop = hdop, satsUsed = satsUsed
    )

    @Test
    fun validCoordinate_isValid_noErrors() {
        val r = CoordinateValidator.validate(coord())
        assertTrue(r.isValid)
        assertTrue(r.errors.isEmpty())
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun latitudeOutOfRange_isRejected() {
        val r = CoordinateValidator.validate(coord(lat = 100.0))
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("Latitude") })
    }

    @Test
    fun longitudeOutOfRange_isRejected() {
        val r = CoordinateValidator.validate(coord(lon = -200.0))
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("Longitude") })
    }

    @Test
    fun blankName_isRejected() {
        val r = CoordinateValidator.validate(coord(name = "  "))
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("name") })
    }

    @Test
    fun qualityWarnings_emittedWhenThresholdsExceeded() {
        val r = CoordinateValidator.validate(coord(horizontalAccuracyM = 12.0, hdop = 6.0, satsUsed = 3))
        assertTrue(r.isValid)             // warnings do not invalidate
        assertEquals(3, r.warnings.size)
    }

    @Test
    fun nullQualityFields_produceNoWarnings() {
        // Preserve null behavior: missing accuracy/hdop/sats must not warn.
        val r = CoordinateValidator.validate(coord(horizontalAccuracyM = null, hdop = null, satsUsed = null))
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun goodQualityFields_produceNoWarnings() {
        val r = CoordinateValidator.validate(coord(horizontalAccuracyM = 5.0, hdop = 1.0, satsUsed = 12))
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun nullIsland_zeroZero_isRejected() {
        val r = CoordinateValidator.validate(coord(lat = 0.0, lon = 0.0))
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("0,0") })
    }

    @Test
    fun naN_isRejected() {
        val r = CoordinateValidator.validate(coord(lat = Double.NaN, lon = Double.NaN))
        assertFalse(r.isValid)
    }

    @Test
    fun isValidLatLon_gate() {
        assertTrue(CoordinateValidator.isValidLatLon(41.0, -75.0))
        assertFalse(CoordinateValidator.isValidLatLon(0.0, 0.0))
        assertFalse(CoordinateValidator.isValidLatLon(Double.NaN, 1.0))
        assertFalse(CoordinateValidator.isValidLatLon(1.0, Double.POSITIVE_INFINITY))
        assertFalse(CoordinateValidator.isValidLatLon(91.0, 0.0))
        assertFalse(CoordinateValidator.isValidLatLon(0.0, 181.0))
    }

    @Test
    fun distanceMeters_samepoint_isZero() {
        assertEquals(0.0, CoordinateValidator.distanceMeters(41.0, -75.0, 41.0, -75.0), 1e-6)
    }

    @Test
    fun findDuplicates_groupsNearbyPoints_andIgnoresFarOnes() {
        val a = coord(id = "a", lat = 41.0000000, lon = -75.0)
        val b = coord(id = "b", lat = 41.0000001, lon = -75.0) // ~1 cm away
        val far = coord(id = "c", lat = 42.0, lon = -75.0)      // ~111 km away

        val groups = CoordinateValidator.findDuplicates(listOf(a, b, far), toleranceMeters = 1.0)

        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b"), groups[0].map { it.id }.toSet())
    }
}
