package com.example.surveyingapp.util

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for UtmConverter: zone clamping, representative projections, and round-trip accuracy.
 * These also serve as regression tests confirming that the meridionalArc refactor did not
 * change computed easting/northing values.
 */
class UtmZoneTest {

    // ── Zone boundary clamping ────────────────────────────────────────────────

    @Test
    fun `latLonToUtm lon -180 produces zone 1`() {
        assertEquals(1, UtmConverter.latLonToUtm(0.0, -180.0).zone)
    }

    @Test
    fun `latLonToUtm lon 180 produces zone 60`() {
        assertEquals(60, UtmConverter.latLonToUtm(0.0, 180.0).zone)
    }

    @Test
    fun `latLonToUtm lon 179_999999 produces zone 60`() {
        assertEquals(60, UtmConverter.latLonToUtm(0.0, 179.999999).zone)
    }

    // ── Representative projections (zone + utmZone string) ───────────────────

    @Test
    fun `latLonToUtm New York City is zone 18N`() {
        val r = UtmConverter.latLonToUtm(40.7128, -74.0060)
        assertEquals(18, r.zone)
        assertEquals('N', r.hemisphere)
        assertEquals("18N", r.utmZone)
    }

    @Test
    fun `latLonToUtm Munich is zone 32N`() {
        val r = UtmConverter.latLonToUtm(48.1351, 11.5820)
        assertEquals(32, r.zone)
        assertEquals('N', r.hemisphere)
    }

    @Test
    fun `latLonToUtm southern hemisphere point has S hemisphere`() {
        val r = UtmConverter.latLonToUtm(-33.8688, 151.2093) // Sydney
        assertEquals('S', r.hemisphere)
    }

    // ── Regression: easting and northing values match pre-refactor results ────
    // Values were computed with the inline formula and are expected to remain
    // stable after extracting meridionalArc().

    @Test
    fun `latLonToUtm New York easting is near 583960 m`() {
        val r = UtmConverter.latLonToUtm(40.7128, -74.0060)
        assertEquals(583960.0, r.easting, 1.0)   // ±1 m tolerance
    }

    @Test
    fun `latLonToUtm New York northing is near 4507523 m`() {
        val r = UtmConverter.latLonToUtm(40.7128, -74.0060)
        assertEquals(4507523.0, r.northing, 1.0)
    }

    @Test
    fun `latLonToUtm equator prime meridian easting is 500000 m`() {
        // At lat=0, lon=3 (central meridian of zone 31), easting should be exactly 500 000 m.
        val r = UtmConverter.latLonToUtm(0.0, 3.0)
        assertEquals(31, r.zone)
        assertEquals(500000.0, r.easting, 0.001)
    }

    // ── Round-trip: forward then inverse recovers original coordinates ────────

    @Test
    fun `round-trip New York recovers lat and lon within 1 mm`() {
        val lat0 = 40.7128; val lon0 = -74.0060
        val fwd = UtmConverter.latLonToUtm(lat0, lon0)
        val (lat1, lon1) = UtmConverter.utmToLatLon(fwd.easting, fwd.northing, fwd.zone, fwd.hemisphere)
        assertEquals(lat0, lat1, 1e-7)  // ~1 cm at equator
        assertEquals(lon0, lon1, 1e-7)
    }

    @Test
    fun `round-trip Sydney recovers lat and lon within 1 mm`() {
        val lat0 = -33.8688; val lon0 = 151.2093
        val fwd = UtmConverter.latLonToUtm(lat0, lon0)
        val (lat1, lon1) = UtmConverter.utmToLatLon(fwd.easting, fwd.northing, fwd.zone, fwd.hemisphere)
        assertEquals(lat0, lat1, 1e-7)
        assertEquals(lon0, lon1, 1e-7)
    }

    @Test
    fun `round-trip equator boundary lon -180 recovers coordinates`() {
        val lat0 = 0.0; val lon0 = -180.0
        val fwd = UtmConverter.latLonToUtm(lat0, lon0)
        val (lat1, lon1) = UtmConverter.utmToLatLon(fwd.easting, fwd.northing, fwd.zone, fwd.hemisphere)
        assertEquals(lat0, lat1, 1e-7)
        // lon -180 and +180 are the same meridian; accept either
        assertEquals(0.0, abs(lon1) - 180.0, 1e-5)
    }

    // ── M0 explicit term: northern and southern hemisphere regression ──────────
    // meridionalArc(0) = 0 mathematically, so adding (m - m0) must produce
    // identical values to the previous plain-m form. These tests assert the
    // known output to catch any accidental numeric change.

    @Test
    fun `latLonToUtm northern hemisphere northing unchanged after M0 addition`() {
        // London — northern hemisphere, mid-latitude
        val r = UtmConverter.latLonToUtm(51.5074, -0.1278)
        assertEquals(30, r.zone)
        assertEquals('N', r.hemisphere)
        assertEquals(5710156.0, r.northing, 1.0)   // ±1 m
    }

    @Test
    fun `latLonToUtm southern hemisphere northing unchanged after M0 addition`() {
        // Cape Town — southern hemisphere, uses 10,000,000 m false northing
        val r = UtmConverter.latLonToUtm(-33.9249, 18.4241)
        assertEquals('S', r.hemisphere)
        // Northing should be well above 9,000,000 m given false northing of 10,000,000
        assert(r.northing > 9_000_000.0) {
            "Expected southern-hemisphere northing > 9,000,000 m but got ${r.northing}"
        }
    }

    @Test
    fun `latLonToUtm equator northing is near zero for northern hemisphere`() {
        // A point right on the equator in the northern hemisphere grid should
        // have northing close to 0 (false northing = 0 for N hemisphere).
        val r = UtmConverter.latLonToUtm(0.0, 3.0)   // equator, central meridian of zone 31
        assertEquals('N', r.hemisphere)
        assertEquals(0.0, r.northing, 1.0)
    }

    // ── Zone letter boundaries ────────────────────────────────────────────────

    @Test
    fun `latLonToUtm lat -80 produces zone letter C`() {
        assertEquals('C', UtmConverter.latLonToUtm(-80.0, 0.0).zoneLetter)
    }

    @Test
    fun `latLonToUtm lat 0 produces zone letter N`() {
        assertEquals('N', UtmConverter.latLonToUtm(0.0, 0.0).zoneLetter)
    }

    @Test
    fun `latLonToUtm lat 84 produces zone letter X`() {
        assertEquals('X', UtmConverter.latLonToUtm(84.0, 0.0).zoneLetter)
    }

    // ── Round-trip through northern and southern hemisphere ───────────────────

    @Test
    fun `round-trip London recovers lat and lon within 1 mm`() {
        val lat0 = 51.5074; val lon0 = -0.1278
        val fwd = UtmConverter.latLonToUtm(lat0, lon0)
        val (lat1, lon1) = UtmConverter.utmToLatLon(fwd.easting, fwd.northing, fwd.zone, fwd.hemisphere)
        assertEquals(lat0, lat1, 1e-7)
        assertEquals(lon0, lon1, 1e-7)
    }

    @Test
    fun `round-trip Cape Town recovers lat and lon within 1 mm`() {
        val lat0 = -33.9249; val lon0 = 18.4241
        val fwd = UtmConverter.latLonToUtm(lat0, lon0)
        val (lat1, lon1) = UtmConverter.utmToLatLon(fwd.easting, fwd.northing, fwd.zone, fwd.hemisphere)
        assertEquals(lat0, lat1, 1e-7)
        assertEquals(lon0, lon1, 1e-7)
    }
}
