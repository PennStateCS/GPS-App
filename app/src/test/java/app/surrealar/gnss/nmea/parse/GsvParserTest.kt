package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.GSV
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GsvParserTest {

    private lateinit var parser: GsvParser
    private lateinit var registry: NmeaRegistry

    @Before
    fun setUp() {
        parser = GsvParser()
        registry = NmeaRegistry(mapOf("GSV" to parser))
    }

    @Test
    fun `parse golden GSV sentence message 1 of 3`() {
        // Golden GSV sentence - first message of a 3-part sequence
        val sentence = "\$GPGSV,3,1,12,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45*7F"

        val result = registry.parse(sentence) as? GSV

        assertNotNull("Should parse valid GSV sentence", result)
        assertEquals(3, result?.totalMessages)
        assertEquals(1, result?.messageNumber)
        assertEquals(12, result?.totalSatellites)
        assertEquals(4, result?.satellites?.size)

        val satellites = result?.satellites!!
        assertEquals(1, satellites[0].svid)
        assertEquals(40, satellites[0].elevationDeg)
        assertEquals(83, satellites[0].azimuthDeg)
        assertEquals(46, satellites[0].snrDb)

        assertEquals(2, satellites[1].svid)
        assertEquals(17, satellites[1].elevationDeg)
        assertEquals(308, satellites[1].azimuthDeg)
        assertEquals(41, satellites[1].snrDb)

        assertEquals(12, satellites[2].svid)
        assertEquals(7, satellites[2].elevationDeg)
        assertEquals(344, satellites[2].azimuthDeg)
        assertEquals(39, satellites[2].snrDb)

        assertEquals(14, satellites[3].svid)
        assertEquals(22, satellites[3].elevationDeg)
        assertEquals(228, satellites[3].azimuthDeg)
        assertEquals(45, satellites[3].snrDb)
    }

    @Test
    fun `parse golden GSV sentence message 2 of 3`() {
        // Golden GSV sentence - second message with 4 more satellites
        val sentence = "\$GPGSV,3,2,12,18,09,067,40,19,28,314,42,22,09,067,40,24,27,146,42*76"

        val result = registry.parse(sentence) as? GSV

        assertNotNull("Should parse GSV message 2", result)
        assertEquals(3, result?.totalMessages)
        assertEquals(2, result?.messageNumber)
        assertEquals(12, result?.totalSatellites)
        assertEquals(4, result?.satellites?.size)

        val satellites = result?.satellites!!
        assertEquals(18, satellites[0].svid)
        assertEquals(9, satellites[0].elevationDeg)
        assertEquals(67, satellites[0].azimuthDeg)
        assertEquals(40, satellites[0].snrDb)
    }

    @Test
    fun `parse golden GSV sentence final message with partial satellites`() {
        // Golden GSV sentence - final message with only 4 satellites (12 total, so 4+4+4)
        val sentence = "\$GPGSV,3,3,12,25,15,175,46,26,28,069,42,27,27,289,35,32,11,239,39*79"

        val result = registry.parse(sentence) as? GSV

        assertNotNull("Should parse final GSV message", result)
        assertEquals(3, result?.totalMessages)
        assertEquals(3, result?.messageNumber)
        assertEquals(12, result?.totalSatellites)
        assertEquals(4, result?.satellites?.size)
    }

    @Test
    fun `parse golden GLONASS GSV sentence`() {
        // Golden GLONASS GSV sentence
        val sentence = "\$GLGSV,3,1,09,65,79,042,33,66,65,315,43,67,09,090,38,68,74,180,33*68"

        val result = registry.parse(sentence) as? GSV

        assertNotNull("Should parse GLONASS GSV", result)
        assertEquals(3, result?.totalMessages)
        assertEquals(1, result?.messageNumber)
        assertEquals(9, result?.totalSatellites)
        assertEquals(4, result?.satellites?.size)

        val satellites = result?.satellites!!
        assertEquals(65, satellites[0].svid) // GLONASS satellite IDs start at 65
        assertEquals(79, satellites[0].elevationDeg)
        assertEquals(42, satellites[0].azimuthDeg)
        assertEquals(33, satellites[0].snrDb)
    }

    @Test
    fun `parse golden Galileo GSV sentence`() {
        // Golden Galileo GSV sentence
        val sentence = "\$GAGSV,2,1,07,01,45,132,42,02,17,312,40,03,07,344,39,07,22,228,45*69"

        val result = registry.parse(sentence) as? GSV

        assertNotNull("Should parse Galileo GSV", result)
        assertEquals(2, result?.totalMessages)
        assertEquals(1, result?.messageNumber)
        assertEquals(7, result?.totalSatellites)
        assertEquals(4, result?.satellites?.size)
    }

    @Test
    fun `parse GSV with satellites having no SNR`() {
        // GSV with some satellites not tracked (no SNR)
        val sentence = "\$GPGSV,2,1,08,01,40,083,,02,17,308,41,12,07,344,,14,22,228,45*7D"

        val result = registry.parse(sentence) as? GSV

        assertNotNull("Should parse GSV with missing SNR", result)
        assertEquals(4, result?.satellites?.size)

        val satellites = result?.satellites!!
        assertEquals(1, satellites[0].svid)
        assertNull("SNR should be null when not provided", satellites[0].snrDb)

        assertEquals(2, satellites[1].svid)
        assertEquals(41, satellites[1].snrDb)

        assertEquals(12, satellites[2].svid)
        assertNull("SNR should be null when not provided", satellites[2].snrDb)
    }

    @Test
    fun `parse GSV single message with 3 satellites`() {
        // Single GSV message with only 3 satellites
        val sentence = "\$GPGSV,1,1,03,23,13,238,45,24,25,047,42,32,11,239,39*46"

        val result = registry.parse(sentence) as? GSV

        assertNotNull("Should parse single GSV message", result)
        assertEquals(1, result?.totalMessages)
        assertEquals(1, result?.messageNumber)
        assertEquals(3, result?.totalSatellites)
        assertEquals(3, result?.satellites?.size)
    }

    @Test
    fun `parse GSV with high elevation satellites`() {
        // GSV with satellites at high elevation (near zenith)
        val sentence = "\$GPGSV,1,1,04,01,89,000,45,02,85,090,42,03,82,180,40,04,87,270,44*76"

        val result = registry.parse(sentence) as? GSV

        assertNotNull("Should parse high elevation GSV", result)
        assertEquals(4, result?.satellites?.size)

        val satellites = result?.satellites!!
        assertEquals(89, satellites[0].elevationDeg) // Near zenith
        assertEquals(0, satellites[0].azimuthDeg) // North

        assertEquals(85, satellites[1].elevationDeg)
        assertEquals(90, satellites[1].azimuthDeg) // East
    }

    @Test
    fun `parse malformed GSV sentence returns null`() {
        val malformedSentence = "\$GPGSV,3,1"

        val result = registry.parse(malformedSentence)

        assertNull("Should return null for malformed sentence", result)
    }

    @Test
    fun `parse GSV sentence without checksum`() {
        val sentence = "\$GPGSV,1,1,04,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45"

        val result = registry.parse(sentence) as? GSV

        assertNotNull("Should parse sentence without checksum", result)
        assertEquals(4, result?.satellites?.size)
    }
}
