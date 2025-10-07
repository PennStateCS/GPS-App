package com.example.surveyingapp.gnss.nmea

import com.example.surveyingapp.gnss.parser.NmeaParser
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive tests for NMEA parsing functionality covering specific scenarios:
 * 1) ZDA sets TimestampSource.NMEA_ZDA
 * 2) GGA quality=5 maps to RtkStatus.FLOAT
 * 3) GSA with 12 PRNs -> satsUsed=12
 * 4) Multi-part GSV totalling 18 -> satsVisible=18
 * 5) GSA with mixed/zero PRNs filters correctly
 *
 * Legacy FixAccumulator / FixSnapshot pipeline removed; tests focus on parser only.
 */
class NmeaComprehensiveTest {

    private lateinit var parser: NmeaParser

    @Before
    fun setUp() { parser = NmeaParser() }

    @Test
    fun `when ZDA sentence is parsed, expect TimestampSource_NMEA_ZDA`() {
        val zdaSentence = "\$GPZDA,123456.78,09,09,2025,00,00*5A"
        val result = parser.parseDetailed(zdaSentence)
        assertTrue(result is NmeaParser.ParseResult.Success)
        val zda = (result as NmeaParser.ParseResult.Success).let { it.sentence as NmeaParser.NmeaSentence.ZdaSentence }.zda
        assertEquals(TimestampSource.NMEA_ZDA, zda.timestampSource)
        assertEquals("123456.78", zda.timeRaw)
        assertEquals(9, zda.day); assertEquals(9, zda.month); assertEquals(2025, zda.year)
    }

    @Test
    fun `when GGA with quality=4 is parsed, expect RtkStatus_FIX`() {
        val ggaSentence = "\$GPGGA,123456.78,3751.65,S,14507.36,E,4,08,0.9,545.4,M,46.9,M,,*47"
        val result = parser.parseDetailed(ggaSentence)
        assertTrue(result is NmeaParser.ParseResult.Success)
        val gga = ((result as NmeaParser.ParseResult.Success).sentence as NmeaParser.NmeaSentence.GgaSentence).gga
        assertEquals(4, gga.quality)
        assertEquals(RtkStatus.FIX, NmeaParser.mapGgaQualityToRtkStatus(gga.quality))
    }

    @Test
    fun `when GGA with quality=5 is parsed, expect RtkStatus_FLOAT`() {
        val ggaSentence = "\$GPGGA,123456.78,3751.65,S,14507.36,E,5,08,0.9,545.4,M,46.9,M,,*46"
        val result = parser.parseDetailed(ggaSentence)
        assertTrue(result is NmeaParser.ParseResult.Success)
        val gga = ((result as NmeaParser.ParseResult.Success).sentence as NmeaParser.NmeaSentence.GgaSentence).gga
        assertEquals(5, gga.quality)
        assertEquals(RtkStatus.FLOAT, NmeaParser.mapGgaQualityToRtkStatus(gga.quality))
    }

    @Test
    fun `when GSA with 12 PRNs is parsed, expect satsUsed=12`() {
        val gsaSentence = "\$GPGSA,A,3,01,02,03,04,05,06,07,08,09,10,11,12,1.0,0.9,0.8*36"
        val result = parser.parseDetailed(gsaSentence)
        assertTrue(result is NmeaParser.ParseResult.Success)
        val gsa = ((result as NmeaParser.ParseResult.Success).sentence as NmeaParser.NmeaSentence.GsaSentence).gsa
        val used = NmeaParser.countSatsUsed(gsa.usedSvids)
        assertEquals(12, used)
        assertEquals(listOf(1,2,3,4,5,6,7,8,9,10,11,12), gsa.usedSvids)
    }

    @Test
    fun `when multi-part GSV totalling 18 sats is parsed, expect satsVisible=18`() {
        val sentences = listOf(
            "\$GPGSV,5,1,18,01,40,083,46,02,17,308,41,03,07,344,39,04,22,228,45*75",
            "\$GPGSV,5,2,18,05,33,125,42,06,55,267,44,07,28,199,38,08,11,045,40*71",
            "\$GPGSV,5,3,18,09,65,315,47,10,44,178,43,11,29,089,41,12,18,156,37*7C",
            "\$GPGSV,5,4,18,13,52,234,45,14,38,092,39,15,25,305,42,16,61,147,46*74",
            "\$GPGSV,5,5,18,17,77,021,48,18,34,278,43,,,,,,,,*71"
        )
        val gsv = sentences.map { parser.parseDetailed(it) }.map {
            assertTrue(it is NmeaParser.ParseResult.Success)
            ((it as NmeaParser.ParseResult.Success).sentence as NmeaParser.NmeaSentence.GsvSentence).gsv
        }
        gsv.forEach { assertEquals(18, it.totalInView) }
        val all = gsv.flatMap { it.satellites }
        assertEquals(18, NmeaParser.countSatsVisible(all))
        assertEquals(18, all.size)
        assertEquals((1..18).toList(), all.map { it.prn }.sorted())
    }

    @Test
    fun `when GSA with mixed PRNs including zeros is parsed, expect correct satsUsed count`() {
        val gsaSentence = "\$GPGSA,A,3,01,02,03,04,05,06,,,,,,,1.0,0.9,0.8*3E"
        val result = parser.parseDetailed(gsaSentence)
        assertTrue(result is NmeaParser.ParseResult.Success)
        val gsa = ((result as NmeaParser.ParseResult.Success).sentence as NmeaParser.NmeaSentence.GsaSentence).gsa
        assertEquals(6, NmeaParser.countSatsUsed(gsa.usedSvids))
    }
}
