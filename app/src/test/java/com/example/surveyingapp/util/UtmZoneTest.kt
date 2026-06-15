package com.example.surveyingapp.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests that UTM zone numbers are always clamped to the valid range 1..60,
 * specifically at the boundary longitude values -180.0 and 180.0.
 */
class UtmZoneTest {

    // ── UtmConverter.latLonToUtm ────────────────────────────────────────────

    @Test
    fun `UtmConverter lon -180 produces zone 1`() {
        val result = UtmConverter.latLonToUtm(0.0, -180.0)
        assertEquals(1, result.zone)
    }

    @Test
    fun `UtmConverter lon 179_999999 produces zone 60`() {
        val result = UtmConverter.latLonToUtm(0.0, 179.999999)
        assertEquals(60, result.zone)
    }

    @Test
    fun `UtmConverter lon 180 produces zone 60 not 61`() {
        val result = UtmConverter.latLonToUtm(0.0, 180.0)
        assertEquals(60, result.zone)
    }

    @Test
    fun `UtmConverter typical mid-zone longitude`() {
        // Longitude 9.0 is well inside zone 32
        val result = UtmConverter.latLonToUtm(48.0, 9.0)
        assertEquals(32, result.zone)
    }

    // ── GeoProjection.wgs84ToUtm ────────────────────────────────────────────

    @Test
    fun `GeoProjection lon -180 produces zone 1`() {
        val result = GeoProjection.wgs84ToUtm(0.0, -180.0)
        assertEquals(1, result.zone)
    }

    @Test
    fun `GeoProjection lon 179_999999 produces zone 60`() {
        val result = GeoProjection.wgs84ToUtm(0.0, 179.999999)
        assertEquals(60, result.zone)
    }

    @Test
    fun `GeoProjection lon 180 produces zone 60 not 61`() {
        val result = GeoProjection.wgs84ToUtm(0.0, 180.0)
        assertEquals(60, result.zone)
    }

    @Test
    fun `GeoProjection typical mid-zone longitude`() {
        // Longitude 9.0 is well inside zone 32
        val result = GeoProjection.wgs84ToUtm(48.0, 9.0)
        assertEquals(32, result.zone)
    }
}

