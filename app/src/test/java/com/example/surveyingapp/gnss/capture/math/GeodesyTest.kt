package com.example.surveyingapp.gnss.capture.math

import org.junit.Assert.assertEquals
import org.junit.Test

class GeodesyTest {

    // Round-trip helper: LLA → ECEF → LLA and assert recovered values.
    private fun assertRoundTrip(
        latDeg: Double, lonDeg: Double, hM: Double,
        latTol: Double = 1e-9, lonTol: Double = 1e-9, hTol: Double = 1e-3
    ) {
        val (x, y, z) = Geodesy.llaToEcef(latDeg, lonDeg, hM)
        val (lat2, lon2, h2) = Geodesy.ecefToLla(x, y, z)
        assertEquals("latitude",  latDeg, lat2, latTol)
        assertEquals("longitude", lonDeg, lon2, lonTol)
        assertEquals("height",    hM,     h2,   hTol)
    }

    // ── Northern hemisphere (|lat| < 45°) — equatorial formula branch ────────

    @Test
    fun `round-trip New York northern hemisphere below 45deg`() {
        assertRoundTrip(40.7128, -74.0060, 10.0)
    }

    @Test
    fun `round-trip equator prime meridian`() {
        assertRoundTrip(0.0, 0.0, 0.0)
    }

    @Test
    fun `round-trip Tokyo below 45deg`() {
        assertRoundTrip(35.6895, 139.6917, 40.0)
    }

    // ── Northern hemisphere (|lat| ≥ 45°) — polar formula branch ─────────────

    @Test
    fun `round-trip Helsinki above 45deg`() {
        assertRoundTrip(60.1699, 24.9384, 26.0)
    }

    @Test
    fun `round-trip Tromsø high latitude`() {
        assertRoundTrip(69.6496, 18.9560, 10.0)
    }

    @Test
    fun `round-trip exactly 45 degrees latitude uses polar branch`() {
        // At exactly 45° the condition is abs(lat) < PI/4, so 45° uses the polar branch.
        assertRoundTrip(45.0, 0.0, 100.0)
    }

    // ── Southern hemisphere (|lat| < 45°) — equatorial formula branch ────────

    @Test
    fun `round-trip Rio de Janeiro southern hemisphere below 45deg`() {
        assertRoundTrip(-22.9068, -43.1729, 10.0)
    }

    // ── Southern hemisphere (|lat| ≥ 45°) — polar formula branch ─────────────

    @Test
    fun `round-trip Sydney southern hemisphere above 45deg`() {
        assertRoundTrip(-33.8688, 151.2093, 50.0)
    }

    @Test
    fun `round-trip Cape Town southern hemisphere above 45deg`() {
        assertRoundTrip(-33.9249, 18.4241, 12.0)
    }

    @Test
    fun `round-trip high southern latitude Ushuaia`() {
        assertRoundTrip(-54.8019, -68.3030, 14.0)
    }

    // ── Height recovery ───────────────────────────────────────────────────────

    @Test
    fun `height recovery at sea level northern hemisphere`() {
        assertRoundTrip(51.5074, -0.1278, 0.0, hTol = 1e-3)
    }

    @Test
    fun `height recovery at elevation northern hemisphere`() {
        assertRoundTrip(47.3769, 8.5417, 408.0, hTol = 1e-3)  // Zurich, ~408 m
    }

    @Test
    fun `height recovery at sea level southern hemisphere`() {
        assertRoundTrip(-33.8688, 151.2093, 0.0, hTol = 1e-3)
    }
}
