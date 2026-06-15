package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.GGA
import org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

class GgaParserTest {

    private lateinit var parser: GgaParser
    private lateinit var registry: NmeaRegistry

    @Before
    fun setUp() {
        parser = GgaParser()
        registry = NmeaRegistry(mapOf("GGA" to parser))
    }

    @Test
    fun `parse golden GGA sentence with all fields`() {
        // Golden GGA sentence with fix and all fields
        val sentence = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"

        val result = registry.parse(sentence) as? GGA

        assertNotNull("Should parse valid GGA sentence", result)
        assertEquals("GP", result?.talker)
        assertEquals("123519", result?.timeRaw)
        assertEquals(48.1173, result?.lat ?: 0.0, 0.0001) // 48°07.038' N
        assertEquals(11.51666667, result?.lon ?: 0.0, 0.0001) // 11°31.000' E
        assertEquals(1, result?.fixQuality)
        assertEquals(8, result?.satsUsed)
        assertEquals(0.9, result?.hdop ?: 0.0, 0.0001)
        assertEquals(545.4, result?.altMsl ?: 0.0, 0.0001)
        assertEquals(46.9, result?.geoidSeparation ?: 0.0, 0.0001)
        assertNull(result?.diffAge)
        assertNull(result?.stationId)
    }

    @Test
    fun `parse golden GGA sentence with no fix`() {
        // Golden GGA sentence with no fix (quality 0)
        val sentence = "\$GPGGA,235317.00,4003.90395,N,10512.57934,W,0,03,2.36,1320.0,M,-16.27,M,,*6C"

        val result = registry.parse(sentence) as? GGA

        assertNotNull("Should parse GGA with no fix", result)
        assertEquals("GP", result?.talker)
        assertEquals("235317.00", result?.timeRaw)
        assertEquals(40.06506583, result?.lat ?: 0.0, 0.0001) // 40°03.90395' N
        assertEquals(-105.2096556, result?.lon ?: 0.0, 0.0001) // 105°12.57934' W
        assertEquals(0, result?.fixQuality) // No fix
        assertEquals(3, result?.satsUsed)
        assertEquals(2.36, result?.hdop ?: 0.0, 0.0001)
    }

    @Test
    fun `parse golden GGA sentence with RTK fixed`() {
        // Golden GGA sentence with RTK fixed solution (quality 4)
        val sentence = "\$GPGGA,134658.00,5106.9792,N,11402.3003,W,4,09,2.3,1048.47,M,-16.27,M,08,AAAA*5A"

        val result = registry.parse(sentence) as? GGA

        assertNotNull("Should parse RTK fixed GGA", result)
        assertEquals("GP", result?.talker)
        assertEquals("134658.00", result?.timeRaw)
        assertEquals(51.1163200, result?.lat ?: 0.0, 0.0001) // 51°06.9792' N
        assertEquals(-114.0383838, result?.lon ?: 0.0, 0.0001) // 114°02.3003' W
        assertEquals(4, result?.fixQuality) // RTK fixed
        assertEquals(9, result?.satsUsed)
        assertEquals(2.3, result?.hdop ?: 0.0, 0.0001)
        assertEquals(1048.47, result?.altMsl ?: 0.0, 0.0001)
        assertEquals(-16.27, result?.geoidSeparation ?: 0.0, 0.0001)
        assertEquals(8.0, result?.diffAge ?: 0.0, 0.0001)
        assertEquals("AAAA", result?.stationId)
    }

    @Test
    fun `parse golden GGA sentence from GLONASS`() {
        // Golden GLONASS GGA sentence
        val sentence = "\$GLGGA,092750.000,5321.6802,N,00630.3372,W,1,8,1.03,61.7,M,55.2,M,,*76"

        val result = registry.parse(sentence) as? GGA

        assertNotNull("Should parse GLONASS GGA", result)
        assertEquals("GL", result?.talker)
        assertEquals("092750.000", result?.timeRaw)
        assertEquals(53.36133667, result?.lat ?: 0.0, 0.0001) // 53°21.6802' N
        assertEquals(-6.505620, result?.lon ?: 0.0, 0.0001) // 6°30.3372' W
        assertEquals(1, result?.fixQuality)
        assertEquals(8, result?.satsUsed)
    }

    @Test
    fun `parse GGA with empty coordinates returns null coordinates`() {
        // GGA with missing position data
        val sentence = "\$GPGGA,235947.000,,,,,0,00,,,,,,,*79"

        val result = registry.parse(sentence) as? GGA

        assertNotNull("Should parse GGA with empty coordinates", result)
        assertEquals("GP", result?.talker)
        assertEquals("235947.000", result?.timeRaw)
        assertNull("Latitude should be null", result?.lat)
        assertNull("Longitude should be null", result?.lon)
        assertEquals(0, result?.fixQuality)
        assertEquals(0, result?.satsUsed)
    }

    @Test
    fun `parse malformed GGA sentence returns null`() {
        val result = registry.parse("\$GPGGA,123456")
        assertNull("Should return null for malformed sentence", result)
    }

    @Test
    fun `parse sentence without checksum`() {
        val sentence = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"

        val result = registry.parse(sentence) as? GGA

        assertNotNull("Should parse sentence without checksum", result)
        assertEquals("GP", result?.talker)
        assertEquals(48.1173, result?.lat ?: 0.0, 0.0001)
    }
}
