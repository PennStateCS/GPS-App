package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.GGA
import com.example.surveyingapp.gnss.nmea.sentence.GST
import com.example.surveyingapp.gnss.nmea.sentence.GSV
import com.example.surveyingapp.gnss.nmea.sentence.RMC
import com.example.surveyingapp.gnss.nmea.sentence.ZDA
import com.example.surveyingapp.gnss.parser.NmeaParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NmeaParserTest {

    private lateinit var parser: NmeaParser

    @Before
    fun setUp() {
        parser = NmeaParser()
    }

    @Test
    fun `when valid ZDA sentence is parsed, result is Success and fields are correct`() {
        val zdaSentence = "\$GPZDA,123456.78,09,09,2025,00,00*6B"
        val result = parser.parse(zdaSentence)

        assertTrue("Should successfully parse valid ZDA sentence", result is NmeaParser.ParseResult.Success)
        val sentence = (result as NmeaParser.ParseResult.Success).sentence
        assertTrue("Should be a ZDA sentence", sentence is ZDA)

        val zda = sentence as ZDA
        assertNotNull("TimeRaw should not be null", zda.timeRaw)
        assertEquals("TimeRaw should match", "123456.78", zda.timeRaw)
        assertEquals("Day should be 9", 9, zda.day)
        assertEquals("Month should be 9", 9, zda.month)
        assertEquals("Year should be 2025", 2025, zda.year)
    }

    @Test
    fun `when valid GGA sentence is parsed, result is Success and time fields are correct`() {
        val ggaSentence = "\$GPGGA,123456.78,3751.65,S,14507.36,E,1,08,0.9,545.4,M,46.9,M,,*72"
        val result = parser.parse(ggaSentence)

        assertTrue("Should successfully parse valid GGA sentence", result is NmeaParser.ParseResult.Success)
        val sentence = (result as NmeaParser.ParseResult.Success).sentence
        assertTrue("Should be a GGA sentence", sentence is GGA)

        val gga = sentence as GGA
        assertNotNull("TimeRaw should not be null", gga.timeRaw)
        assertEquals("TimeRaw should match", "123456.78", gga.timeRaw)
    }

    @Test
    fun `when valid RMC sentence is parsed, result is Success and time and date are correct`() {
        val rmcSentence = "\$GPRMC,123456.78,A,3751.65,S,14507.36,E,000.0,360.0,090925,011.3,E*4D"
        val result = parser.parse(rmcSentence)

        assertTrue("Should successfully parse valid RMC sentence", result is NmeaParser.ParseResult.Success)
        val sentence = (result as NmeaParser.ParseResult.Success).sentence
        assertTrue("Should be an RMC sentence", sentence is RMC)

        val rmc = sentence as RMC
        assertNotNull("TimeRaw should not be null", rmc.timeRaw)
        assertNotNull("Date should not be null", rmc.date)
        assertEquals("TimeRaw should match", "123456.78", rmc.timeRaw)
        // Raw NMEA date is ddmmyy; `date` is the parsed LocalDate (090925 -> 2025-09-09).
        assertEquals("Raw date should match", "090925", rmc.dateRaw)
        assertEquals("Parsed date should match", "2025-09-09", rmc.date.toString())
    }

    @Test
    fun `when valid GST sentence is parsed, result is Success and time is correct`() {
        val gstSentence = "\$GPGST,123456.78,1.2,0.95,0.66,123.45,0.52,0.88,1.1*66"
        val result = parser.parse(gstSentence)

        assertTrue("Should successfully parse valid GST sentence", result is NmeaParser.ParseResult.Success)
        val sentence = (result as NmeaParser.ParseResult.Success).sentence
        assertTrue("Should be a GST sentence", sentence is GST)

        val gst = sentence as GST
        assertNotNull("TimeRaw should not be null", gst.timeRaw)
        assertEquals("TimeRaw should match", "123456.78", gst.timeRaw)
    }

    @Test
    fun `when GSV sentence is parsed, result is Success`() {
        val gsvSentence = "\$GPGSV,2,1,08,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45*75"
        val result = parser.parse(gsvSentence)

        assertTrue("Should successfully parse valid GSV sentence", result is NmeaParser.ParseResult.Success)
        val sentence = (result as NmeaParser.ParseResult.Success).sentence
        assertTrue("Should be a GSV sentence", sentence is GSV)
    }
}
