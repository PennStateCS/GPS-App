package com.example.surveyingapp.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests that UtmConverter zone numbers are always clamped to the valid range 1..60,
 * at boundary longitude values and for a representative normal coordinate.
 */
class UtmZoneTest {

    @Test
    fun `latLonToUtm lon -180 produces zone 1`() {
        val result = UtmConverter.latLonToUtm(0.0, -180.0)
        assertEquals(1, result.zone)
    }

    @Test
    fun `latLonToUtm lon 180 produces zone 60`() {
        val result = UtmConverter.latLonToUtm(0.0, 180.0)
        assertEquals(60, result.zone)
    }

    @Test
    fun `latLonToUtm lon 179_999999 produces zone 60`() {
        val result = UtmConverter.latLonToUtm(0.0, 179.999999)
        assertEquals(60, result.zone)
    }

    @Test
    fun `latLonToUtm normal coordinate produces expected zone and zone string`() {
        // New York City is in UTM zone 18N
        val result = UtmConverter.latLonToUtm(40.7128, -74.0060)
        assertEquals(18, result.zone)
        assertEquals('N', result.hemisphere)
        assertEquals("18N", result.utmZone)
    }

    @Test
    fun `latLonToUtm typical mid-zone longitude`() {
        // Longitude 9.0 is well inside zone 32
        val result = UtmConverter.latLonToUtm(48.0, 9.0)
        assertEquals(32, result.zone)
    }
}
