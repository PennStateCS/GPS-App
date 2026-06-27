package app.surrealar.gnss.nmea.parse

import org.junit.Test
import org.junit.Assert.*

/**
 * Comprehensive unit tests for NMEA coordinate parsing functions.
 * Tests various scenarios including northern/southern latitudes and eastern/western longitudes.
 */
class NmeaCoordinateUtilsTest {

    // === LATITUDE PARSING TESTS ===

    @Test
    fun `parseLatitude - northern hemisphere - valid coordinates`() {
        // Test case: 41°24.8963' N = 41.41493833... decimal degrees
        val result = NmeaCoordinateUtils.parseLatitude("4124.8963", "N")
        assertNotNull(result)
        assertEquals(41.41493833, result!!, 0.00000001)

        // Test case: 0°00.0000' N = 0.0 decimal degrees (equator)
        val equator = NmeaCoordinateUtils.parseLatitude("0000.0000", "N")
        assertNotNull(equator)
        assertEquals(0.0, equator!!, 0.00000001)

        // Test case: 89°59.9999' N = 89.99999833... decimal degrees (near north pole)
        val nearNorthPole = NmeaCoordinateUtils.parseLatitude("8959.9999", "N")
        assertNotNull(nearNorthPole)
        assertEquals(89.99999833, nearNorthPole!!, 0.00000001)
    }

    @Test
    fun `parseLatitude - southern hemisphere - valid coordinates`() {
        // Test case: 41°24.8963' S = -41.41493833... decimal degrees
        val result = NmeaCoordinateUtils.parseLatitude("4124.8963", "S")
        assertNotNull(result)
        assertEquals(-41.41493833, result!!, 0.00000001)

        // Test case: 0°00.0000' S = 0.0 decimal degrees (equator)
        val equator = NmeaCoordinateUtils.parseLatitude("0000.0000", "S")
        assertNotNull(equator)
        assertEquals(0.0, equator!!, 0.00000001)

        // Test case: 89°59.9999' S = -89.99999833... decimal degrees (near south pole)
        val nearSouthPole = NmeaCoordinateUtils.parseLatitude("8959.9999", "S")
        assertNotNull(nearSouthPole)
        assertEquals(-89.99999833, nearSouthPole!!, 0.00000001)
    }

    @Test
    fun `parseLatitude - edge cases and precision`() {
        // Test high precision
        val highPrecision = NmeaCoordinateUtils.parseLatitude("4030.123456", "N")
        assertNotNull(highPrecision)
        assertEquals(40.50205760, highPrecision!!, 0.00000001)

        // Test single digit degrees
        val singleDigit = NmeaCoordinateUtils.parseLatitude("0530.5000", "N")
        assertNotNull(singleDigit)
        assertEquals(5.508333333, singleDigit!!, 0.00000001)

        // Test 30 minutes exactly
        val thirtyMinutes = NmeaCoordinateUtils.parseLatitude("4030.0000", "S")
        assertNotNull(thirtyMinutes)
        assertEquals(-40.5, thirtyMinutes!!, 0.00000001)
    }

    // === LONGITUDE PARSING TESTS ===

    @Test
    fun `parseLongitude - eastern hemisphere - valid coordinates`() {
        // Test case: 81°51.6838' E = 81.86139666... decimal degrees
        val result = NmeaCoordinateUtils.parseLongitude("08151.6838", "E")
        assertNotNull(result)
        assertEquals(81.86139666, result!!, 0.00000001)

        // Test case: 0°00.0000' E = 0.0 decimal degrees (prime meridian)
        val primeMeridian = NmeaCoordinateUtils.parseLongitude("00000.0000", "E")
        assertNotNull(primeMeridian)
        assertEquals(0.0, primeMeridian!!, 0.00000001)

        // Test case: 179°59.9999' E = 179.99999833... decimal degrees (near date line)
        val nearDateLine = NmeaCoordinateUtils.parseLongitude("17959.9999", "E")
        assertNotNull(nearDateLine)
        assertEquals(179.99999833, nearDateLine!!, 0.00000001)
    }

    @Test
    fun `parseLongitude - western hemisphere - valid coordinates`() {
        // Test case: 81°51.6838' W = -81.86139666... decimal degrees
        val result = NmeaCoordinateUtils.parseLongitude("08151.6838", "W")
        assertNotNull(result)
        assertEquals(-81.86139666, result!!, 0.00000001)

        // Test case: 0°00.0000' W = 0.0 decimal degrees (prime meridian)
        val primeMeridian = NmeaCoordinateUtils.parseLongitude("00000.0000", "W")
        assertNotNull(primeMeridian)
        assertEquals(0.0, primeMeridian!!, 0.00000001)

        // Test case: 179°59.9999' W = -179.99999833... decimal degrees (near date line)
        val nearDateLine = NmeaCoordinateUtils.parseLongitude("17959.9999", "W")
        assertNotNull(nearDateLine)
        assertEquals(-179.99999833, nearDateLine!!, 0.00000001)
    }

    @Test
    fun `parseLongitude - edge cases and precision`() {
        // Test three-digit degrees
        val threeDigitDegrees = NmeaCoordinateUtils.parseLongitude("12030.5000", "E")
        assertNotNull(threeDigitDegrees)
        assertEquals(120.508333333, threeDigitDegrees!!, 0.00000001)

        // Test high precision
        val highPrecision = NmeaCoordinateUtils.parseLongitude("07415.987654", "W")
        assertNotNull(highPrecision)
        assertEquals(-74.26646090, highPrecision!!, 0.00000001)

        // Test single digit degrees
        val singleDigit = NmeaCoordinateUtils.parseLongitude("00945.0000", "E")
        assertNotNull(singleDigit)
        assertEquals(9.75, singleDigit!!, 0.00000001)
    }

    // === REAL-WORLD COORDINATE TESTS ===

    @Test
    fun `parseCoordinates - real world locations`() {
        // New York City: 40°42.46' N, 74°00.23' W
        val nycLat = NmeaCoordinateUtils.parseLatitude("4042.4600", "N")
        val nycLon = NmeaCoordinateUtils.parseLongitude("07400.2300", "W")
        assertNotNull(nycLat)
        assertNotNull(nycLon)
        assertEquals(40.707666666, nycLat!!, 0.00000001)
        assertEquals(-74.003833333, nycLon!!, 0.00000001)

        // Sydney, Australia: 33°52.08' S, 151°12.53' E
        val sydneyLat = NmeaCoordinateUtils.parseLatitude("3352.0800", "S")
        val sydneyLon = NmeaCoordinateUtils.parseLongitude("15112.5300", "E")
        assertNotNull(sydneyLat)
        assertNotNull(sydneyLon)
        assertEquals(-33.868, sydneyLat!!, 0.00000001)
        assertEquals(151.208833333, sydneyLon!!, 0.00000001)

        // Tokyo, Japan: 35°41.38' N, 139°41.30' E
        val tokyoLat = NmeaCoordinateUtils.parseLatitude("3541.3800", "N")
        val tokyoLon = NmeaCoordinateUtils.parseLongitude("13941.3000", "E")
        assertNotNull(tokyoLat)
        assertNotNull(tokyoLon)
        assertEquals(35.689666666, tokyoLat!!, 0.00000001)
        assertEquals(139.688333333, tokyoLon!!, 0.00000001)
    }

    // === ERROR HANDLING TESTS ===

    @Test
    fun `parseLatitude - invalid inputs return null`() {
        // Null inputs
        assertNull(NmeaCoordinateUtils.parseLatitude(null, "N"))
        assertNull(NmeaCoordinateUtils.parseLatitude("4124.8963", null))
        assertNull(NmeaCoordinateUtils.parseLatitude(null, null))

        // Empty strings
        assertNull(NmeaCoordinateUtils.parseLatitude("", "N"))
        assertNull(NmeaCoordinateUtils.parseLatitude("4124.8963", ""))

        // Invalid hemisphere indicators
        assertNull(NmeaCoordinateUtils.parseLatitude("4124.8963", "E"))
        assertNull(NmeaCoordinateUtils.parseLatitude("4124.8963", "W"))
        assertNull(NmeaCoordinateUtils.parseLatitude("4124.8963", "X"))

        // Invalid number formats
        assertNull(NmeaCoordinateUtils.parseLatitude("abc", "N"))
        assertNull(NmeaCoordinateUtils.parseLatitude("41.24.8963", "N"))
        assertNull(NmeaCoordinateUtils.parseLatitude("4124.89.63", "N"))

        // Invalid coordinate ranges (latitude > 90°)
        assertNull(NmeaCoordinateUtils.parseLatitude("9000.0000", "N"))
        assertNull(NmeaCoordinateUtils.parseLatitude("9130.0000", "N"))

        // Invalid minutes (>= 60)
        assertNull(NmeaCoordinateUtils.parseLatitude("4160.0000", "N"))
        assertNull(NmeaCoordinateUtils.parseLatitude("4165.5000", "N"))
    }

    @Test
    fun `parseLongitude - invalid inputs return null`() {
        // Null inputs
        assertNull(NmeaCoordinateUtils.parseLongitude(null, "E"))
        assertNull(NmeaCoordinateUtils.parseLongitude("08151.6838", null))
        assertNull(NmeaCoordinateUtils.parseLongitude(null, null))

        // Empty strings
        assertNull(NmeaCoordinateUtils.parseLongitude("", "E"))
        assertNull(NmeaCoordinateUtils.parseLongitude("08151.6838", ""))

        // Invalid hemisphere indicators
        assertNull(NmeaCoordinateUtils.parseLongitude("08151.6838", "N"))
        assertNull(NmeaCoordinateUtils.parseLongitude("08151.6838", "S"))
        assertNull(NmeaCoordinateUtils.parseLongitude("08151.6838", "X"))

        // Invalid number formats
        assertNull(NmeaCoordinateUtils.parseLongitude("abc", "E"))
        assertNull(NmeaCoordinateUtils.parseLongitude("081.51.6838", "E"))
        assertNull(NmeaCoordinateUtils.parseLongitude("08151.68.38", "E"))

        // Invalid coordinate ranges (longitude > 180°)
        assertNull(NmeaCoordinateUtils.parseLongitude("18000.0000", "E"))
        assertNull(NmeaCoordinateUtils.parseLongitude("18130.0000", "E"))

        // Invalid minutes (>= 60)
        assertNull(NmeaCoordinateUtils.parseLongitude("08160.0000", "E"))
        assertNull(NmeaCoordinateUtils.parseLongitude("08165.5000", "E"))
    }

    // === BOUNDARY CONDITION TESTS ===

    @Test
    fun `parseCoordinates - boundary conditions`() {
        // Test coordinates exactly at boundaries

        // Latitude boundaries
        val maxNorthLat = NmeaCoordinateUtils.parseLatitude("8959.9999", "N")
        assertNotNull(maxNorthLat)
        assertTrue(maxNorthLat!! < 90.0)

        val maxSouthLat = NmeaCoordinateUtils.parseLatitude("8959.9999", "S")
        assertNotNull(maxSouthLat)
        assertTrue(maxSouthLat!! > -90.0)

        // Longitude boundaries
        val maxEastLon = NmeaCoordinateUtils.parseLongitude("17959.9999", "E")
        assertNotNull(maxEastLon)
        assertTrue(maxEastLon!! < 180.0)

        val maxWestLon = NmeaCoordinateUtils.parseLongitude("17959.9999", "W")
        assertNotNull(maxWestLon)
        assertTrue(maxWestLon!! > -180.0)

        // Minutes at boundary (59.9999)
        val maxMinutes = NmeaCoordinateUtils.parseLatitude("4159.9999", "N")
        assertNotNull(maxMinutes)
        assertEquals(41.99999833, maxMinutes!!, 0.00000001)
    }
}
