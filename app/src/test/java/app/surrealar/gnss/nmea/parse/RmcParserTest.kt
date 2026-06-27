package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.RMC
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RmcParserTest {

    private lateinit var parser: RmcParser
    private lateinit var registry: NmeaRegistry

    @Before
    fun setUp() {
        parser = RmcParser()
        registry = NmeaRegistry(mapOf("RMC" to parser))
    }

    @Test
    fun `parse golden RMC sentence with all fields`() {
        // Golden RMC sentence with active status and all fields
        val sentence = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"

        val result = registry.parse(sentence) as? RMC

        assertNotNull("Should parse valid RMC sentence", result)
        assertEquals("GP", result?.talker)
        assertEquals("123519", result?.timeRaw)
        assertEquals("230394", result?.dateRaw)
        assertEquals(48.1173, result?.lat ?: 0.0, 0.0001) // 48°07.038' N
        assertEquals(11.51666667, result?.lon ?: 0.0, 0.0001) // 11°31.000' E
        assertEquals(22.4, result?.speedKnots ?: 0.0, 0.0001)
        assertEquals(84.4, result?.courseDeg ?: 0.0, 0.0001)
        assertNotNull("Should have parsed time", result?.time)
        assertNotNull("Should have parsed date", result?.date)
        assertNotNull("Should have epoch millis", result?.epochMillis)
    }

    @Test
    fun `parse golden RMC sentence with void status`() {
        // Golden RMC sentence with void status (no fix)
        val sentence = "\$GPRMC,225446,V,,,,,0.00,0.00,060180,,,N*5F"

        val result = registry.parse(sentence) as? RMC

        assertNotNull("Should parse RMC with void status", result)
        assertEquals("GP", result?.talker)
        assertEquals("225446", result?.timeRaw)
        assertEquals("060180", result?.dateRaw)
        assertNull("Latitude should be null", result?.lat)
        assertNull("Longitude should be null", result?.lon)
        assertEquals(0.0, result?.speedKnots ?: 0.0, 0.0001)
        assertEquals(0.0, result?.courseDeg ?: 0.0, 0.0001)
    }

    @Test
    fun `parse golden RMC sentence from GLONASS`() {
        // Golden GLONASS RMC sentence
        val sentence = "\$GLRMC,083559.00,A,4717.11437,N,00833.91522,E,0.004,77.52,091202,,,A*4B"

        val result = registry.parse(sentence) as? RMC

        assertNotNull("Should parse GLONASS RMC", result)
        assertEquals("GL", result?.talker)
        assertEquals("083559.00", result?.timeRaw)
        assertEquals("091202", result?.dateRaw)
        assertEquals(47.28523950, result?.lat ?: 0.0, 0.0001) // 47°17.11437' N
        assertEquals(8.565253667, result?.lon ?: 0.0, 0.0001) // 8°33.91522' E
        assertEquals(0.004, result?.speedKnots ?: 0.0, 0.0001)
        assertEquals(77.52, result?.courseDeg ?: 0.0, 0.0001)
    }

    @Test
    fun `parse golden RMC sentence with high speed`() {
        // Golden RMC sentence showing high speed navigation
        val sentence = "\$GPRMC,220516,A,5133.82,N,00042.24,W,173.8,231.8,130694,004.2,W*70"

        val result = registry.parse(sentence) as? RMC

        assertNotNull("Should parse high speed RMC", result)
        assertEquals("GP", result?.talker)
        assertEquals("220516", result?.timeRaw)
        assertEquals("130694", result?.dateRaw)
        assertEquals(51.56366667, result?.lat ?: 0.0, 0.0001) // 51°33.82' N
        assertEquals(-0.7040, result?.lon ?: 0.0, 0.0001) // 0°42.24' W
        assertEquals(173.8, result?.speedKnots ?: 0.0, 0.0001) // High speed
        assertEquals(231.8, result?.courseDeg ?: 0.0, 0.0001)
    }

    @Test
    fun `parse golden RMC sentence minimal format`() {
        // Golden RMC sentence with minimal required fields
        val sentence = "\$GPRMC,092204.999,A,4250.5589,S,14718.5084,E,0.00,89.68,211200,,*25"

        val result = registry.parse(sentence) as? RMC

        assertNotNull("Should parse minimal RMC", result)
        assertEquals("GP", result?.talker)
        assertEquals("092204.999", result?.timeRaw)
        assertEquals("211200", result?.dateRaw)
        assertEquals(-42.84264833, result?.lat ?: 0.0, 0.0001) // 42°50.5589' S
        assertEquals(147.3084733, result?.lon ?: 0.0, 0.0001) // 147°18.5084' E
        assertEquals(0.0, result?.speedKnots ?: 0.0, 0.0001)
        assertEquals(89.68, result?.courseDeg ?: 0.0, 0.0001)
    }

    @Test
    fun `parse RMC with empty coordinates when void`() {
        // RMC with void status and empty position
        val sentence = "\$GPRMC,235947.000,V,,,,,0.00,0.00,041019,,,N*4E"

        val result = registry.parse(sentence) as? RMC

        assertNotNull("Should parse void RMC with empty coordinates", result)
        assertEquals("GP", result?.talker)
        assertEquals("235947.000", result?.timeRaw)
        assertEquals("041019", result?.dateRaw)
        assertNull("Latitude should be null when void", result?.lat)
        assertNull("Longitude should be null when void", result?.lon)
    }

    @Test
    fun `parse sparse RMC sentence yields no position`() {
        // Structurally valid talker+tag but missing fields: parsed tolerantly with null position.
        val result = registry.parse("\$GPRMC,123456") as? RMC

        assertNotNull("Sparse RMC still parses (best-effort)", result)
        assertNull("Latitude should be null", result?.lat)
        assertNull("Longitude should be null", result?.lon)
    }

    @Test
    fun `parse RMC sentence without checksum`() {
        val sentence = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W"

        val result = registry.parse(sentence) as? RMC

        assertNotNull("Should parse sentence without checksum", result)
        assertEquals("GP", result?.talker)
        assertEquals(48.1173, result?.lat ?: 0.0, 0.0001)
    }
}
