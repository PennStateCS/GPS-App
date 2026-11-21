package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.GGA
import org.junit.Test
import org.junit.Assert.*

class NmeaRegistryChecksumTest {

    private val parsers = mapOf("GGA" to GgaParser())

    @Test
    fun `verifyChecksum true accepts valid checksum`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = false)
        val result = registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47")
        assertNotNull("Should accept sentence with valid checksum", result)
    }

    @Test
    fun `verifyChecksum true rejects invalid checksum`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = false)
        val result = registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*99")
        assertNull("Should reject sentence with invalid checksum", result)
    }

    @Test
    fun `verifyChecksum true requireChecksum false accepts missing checksum`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = false)
        val result = registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")
        assertNotNull("Should accept sentence without checksum when not required", result)
    }

    @Test
    fun `verifyChecksum true requireChecksum true rejects missing checksum`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = true)
        val result = registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")
        assertNull("Should reject sentence without checksum when required", result)
    }

    @Test
    fun `verifyChecksum false accepts any sentence regardless of checksum`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = false, requireChecksum = false)
        assertNotNull("Should accept even with wrong checksum",
            registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*99"))
        assertNotNull("Should accept without checksum",
            registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
    }

    @Test
    fun `production mode - strict validation rejects all invalid sentences`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = true)
        assertNotNull("Valid checksum should be accepted",
            registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"))
        assertNull("Invalid checksum should be rejected",
            registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*99"))
        assertNull("Missing checksum should be rejected",
            registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
    }

    @Test
    fun `dev mode - lenient validation accepts test data without checksums`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = false)
        assertNotNull("Valid checksum should be accepted",
            registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"))
        assertNull("Invalid checksum should still be rejected",
            registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*99"))
        assertNotNull("Missing checksum should be accepted for test data",
            registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
    }

    @Test
    fun `checksum calculation is correct`() {
        val registry = NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = false)
        val result = registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47")
        assertNotNull("Checksum validation should pass for known-good sentence", result)

        val gga = result as GGA
        assertEquals("Latitude should be parsed correctly", 48.117300, gga.lat ?: -1.0, 0.000001)
        assertEquals("Longitude should be parsed correctly", 11.516667, gga.lon ?: -1.0, 0.000001)
    }
}

