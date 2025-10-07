package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.GSA
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GsaParserTest {

    private lateinit var parser: GsaParser
    private lateinit var registry: NmeaRegistry

    @Before
    fun setUp() {
        parser = GsaParser()
        registry = NmeaRegistry(mapOf("GSA" to parser))
    }

    @Test
    fun `parse golden GSA sentence with 3D fix`() {
        // Golden GSA sentence with 3D fix and full satellite constellation
        val sentence = "\$GPGSA,A,3,01,02,03,04,05,06,07,08,09,10,11,12,1.0,0.6,0.8*36"

        val result = registry.parse(sentence) as? GSA

        assertNotNull("Should parse valid GSA sentence", result)
        assertEquals(3, result?.fixMode) // 3D fix
        assertEquals(12, result?.usedSvids?.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), result?.usedSvids)
    }

    @Test
    fun `parse golden GSA sentence with 2D fix`() {
        // Golden GSA sentence with 2D fix and partial satellites
        val sentence = "\$GPGSA,A,2,01,02,03,04,,,,,,,,,2.5,1.5,2.0*3A"

        val result = registry.parse(sentence) as? GSA

        assertNotNull("Should parse GSA with 2D fix", result)
        assertEquals(2, result?.fixMode) // 2D fix
        assertEquals(4, result?.usedSvids?.size)
        assertEquals(listOf(1, 2, 3, 4), result?.usedSvids)
    }

    @Test
    fun `parse golden GSA sentence with no fix`() {
        // Golden GSA sentence with no fix
        val sentence = "\$GPGSA,A,1,,,,,,,,,,,,,,,*1E"

        val result = registry.parse(sentence) as? GSA

        assertNotNull("Should parse GSA with no fix", result)
        assertEquals(1, result?.fixMode) // No fix
        assertTrue("Should have empty satellite list", result?.usedSvids?.isEmpty() == true)
    }

    @Test
    fun `parse golden GLONASS GSA sentence`() {
        // Golden GLONASS GSA sentence
        val sentence = "\$GLGSA,A,3,65,66,67,68,69,70,71,72,,,,,1.8,1.0,1.5*22"

        val result = registry.parse(sentence) as? GSA

        assertNotNull("Should parse GLONASS GSA", result)
        assertEquals(3, result?.fixMode)
        assertEquals(8, result?.usedSvids?.size)
        assertEquals(listOf(65, 66, 67, 68, 69, 70, 71, 72), result?.usedSvids)
    }

    @Test
    fun `parse golden Galileo GSA sentence`() {
        // Golden Galileo GSA sentence
        val sentence = "\$GAGSA,A,3,211,212,213,214,215,216,217,218,219,220,,,0.9,0.5,0.7*0F"

        val result = registry.parse(sentence) as? GSA

        assertNotNull("Should parse Galileo GSA", result)
        assertEquals(3, result?.fixMode)
        assertEquals(10, result?.usedSvids?.size)
        assertEquals(listOf(211, 212, 213, 214, 215, 216, 217, 218, 219, 220), result?.usedSvids)
    }

    @Test
    fun `parse GSA with mixed satellite availability`() {
        // GSA with some satellites used, some empty slots
        val sentence = "\$GPGSA,A,3,07,02,26,27,09,04,15,,,,,,,1.6,1.0,1.2*3B"

        val result = registry.parse(sentence) as? GSA

        assertNotNull("Should parse GSA with mixed satellites", result)
        assertEquals(3, result?.fixMode)
        assertEquals(7, result?.usedSvids?.size)
        assertEquals(listOf(7, 2, 26, 27, 9, 4, 15), result?.usedSvids)
    }

    @Test
    fun `parse GSA with manual mode`() {
        // GSA with manual mode selection
        val sentence = "\$GPGSA,M,3,01,02,03,04,05,06,07,08,09,10,11,12,1.2,0.7,0.9*35"

        val result = registry.parse(sentence) as? GSA

        assertNotNull("Should parse GSA with manual mode", result)
        assertEquals(3, result?.fixMode)
        assertEquals(12, result?.usedSvids?.size)
    }

    @Test
    fun `parse malformed GSA sentence returns null`() {
        val malformedSentence = "\$GPGSA,A,3"

        val result = registry.parse(malformedSentence)

        assertNull("Should return null for malformed sentence", result)
    }

    @Test
    fun `parse GSA sentence without checksum`() {
        val sentence = "\$GPGSA,A,3,01,02,03,04,05,06,07,08,09,10,11,12,1.0,0.6,0.8"

        val result = registry.parse(sentence) as? GSA

        assertNotNull("Should parse sentence without checksum", result)
        assertEquals(3, result?.fixMode)
    }
}
