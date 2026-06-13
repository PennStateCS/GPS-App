package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.GGA
import org.junit.Test
import org.junit.Assert.*

class NmeaRegistryChecksumTest {

    private val parsers = mapOf("GGA" to GgaParser())

    @Test
    fun `verifyChecksum true accepts valid checksum`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = false)
        val validGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"

        val result = registry.parse(validGga)
        assertNotNull(result, "Should accept sentence with valid checksum")
    }

    @Test
    fun `verifyChecksum true rejects invalid checksum`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = false)
        val invalidGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*99"  // Wrong checksum

        val result = registry.parse(invalidGga)
        assertNull(result, "Should reject sentence with invalid checksum")
    }

    @Test
    fun `verifyChecksum true requireChecksum false accepts missing checksum`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = false)
        val noChecksumGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"

        val result = registry.parse(noChecksumGga)
        assertNotNull(result, "Should accept sentence without checksum when not required")
    }

    @Test
    fun `verifyChecksum true requireChecksum true rejects missing checksum`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = true)
        val noChecksumGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"

        val result = registry.parse(noChecksumGga)
        assertNull(result, "Should reject sentence without checksum when required")
    }

    @Test
    fun `verifyChecksum false accepts any sentence regardless of checksum`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = false, requireChecksum = false)

        // Invalid checksum
        val invalidGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*99"
        assertNotNull(registry.parse(invalidGga), "Should accept even with wrong checksum")

        // No checksum
        val noChecksumGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        assertNotNull(registry.parse(noChecksumGga), "Should accept without checksum")
    }

    @Test
    fun `production mode - strict validation rejects all invalid sentences`() {
        // Production mode: verify and require checksums
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = true)

        val validGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        assertNotNull(registry.parse(validGga), "Valid checksum should be accepted")

        val invalidGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*99"
        assertNull(registry.parse(invalidGga), "Invalid checksum should be rejected")

        val noChecksumGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        assertNull(registry.parse(noChecksumGga), "Missing checksum should be rejected")
    }

    @Test
    fun `dev mode - lenient validation accepts test data without checksums`() {
        // Dev/replay mode: verify checksums when present but don't require them
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = false)

        val validGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        assertNotNull(registry.parse(validGga), "Valid checksum should be accepted")

        val invalidGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*99"
        assertNull(registry.parse(invalidGga), "Invalid checksum should still be rejected")

        val noChecksumGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        assertNotNull(registry.parse(noChecksumGga), "Missing checksum should be accepted for test data")
    }

    @Test
    fun `checksum calculation is correct`() {
        // Verify checksum computation matches NMEA 0183 standard
        // Known valid sentence: $GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47
        // Checksum 0x47 for payload "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"

        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = false)
        val sentence = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"

        val result = registry.parse(sentence)
        assertNotNull(result, "Checksum validation should pass for known-good sentence")

        val gga = result as GGA
        assertEquals(40.117300, gga.lat, 0.000001, "Latitude should be parsed correctly")
        assertEquals(11.516667, gga.lon, 0.000001, "Longitude should be parsed correctly")
    }
}

