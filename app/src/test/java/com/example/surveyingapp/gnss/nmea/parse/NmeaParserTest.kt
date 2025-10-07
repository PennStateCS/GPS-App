package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.model.TimestampSource
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
    fun `when valid ZDA sentence is parsed, expect timestampSource is NMEA_ZDA`() {
        // Valid ZDA sentence: $GPZDA,123456.78,09,09,2025,00,00*5A
        val zdaSentence = "\$GPZDA,123456.78,09,09,2025,00,00*5A"

        val result = parser.parseDetailed(zdaSentence)

        assertTrue("Should successfully parse valid ZDA sentence", result is NmeaParser.ParseResult.Success)
        val success = result as NmeaParser.ParseResult.Success
        assertTrue("Should be a ZDA sentence", success.sentence is NmeaParser.NmeaSentence.ZdaSentence)

        val zdaSentenceResult = success.sentence as NmeaParser.NmeaSentence.ZdaSentence
        val zda = zdaSentenceResult.zda

        assertNotNull("TimeRaw should not be null", zda.timeRaw)
        assertEquals("TimeRaw should match", "123456.78", zda.timeRaw)
        assertEquals("Day should be 9", 9, zda.day)
        assertEquals("Month should be 9", 9, zda.month)
        assertEquals("Year should be 2025", 2025, zda.year)

        // Most important: verify timestampSource is NMEA_ZDA
        assertEquals(
            "ZDA sentence should have timestampSource = TimestampSource.NMEA_ZDA",
            TimestampSource.NMEA_ZDA,
            zda.timestampSource
        )
    }

    @Test
    fun `when valid GGA sentence with time is parsed, expect timestampSource is GNSS_PROVIDER`() {
        // Valid GGA sentence with time
        val ggaSentence = "\$GPGGA,123456.78,3751.65,S,14507.36,E,1,08,0.9,545.4,M,46.9,M,,*47"

        val result = parser.parseDetailed(ggaSentence)

        assertTrue("Should successfully parse valid GGA sentence", result is NmeaParser.ParseResult.Success)
        val success = result as NmeaParser.ParseResult.Success
        assertTrue("Should be a GGA sentence", success.sentence is NmeaParser.NmeaSentence.GgaSentence)

        val ggaSentenceResult = success.sentence as NmeaParser.NmeaSentence.GgaSentence
        val gga = ggaSentenceResult.gga

        assertNotNull("TimeRaw should not be null", gga.timeRaw)
        assertEquals("TimeRaw should match", "123456.78", gga.timeRaw)

        // Verify timestampSource is GNSS_PROVIDER when time is present
        assertEquals(
            "GGA sentence with time should have timestampSource = TimestampSource.GNSS_PROVIDER",
            TimestampSource.GNSS_PROVIDER,
            gga.timestampSource
        )
    }

    @Test
    fun `when valid RMC sentence with time and date is parsed, expect timestampSource is GNSS_PROVIDER`() {
        // Valid RMC sentence with time and date
        val rmcSentence = "\$GPRMC,123456.78,A,3751.65,S,14507.36,E,000.0,360.0,090925,011.3,E*62"

        val result = parser.parseDetailed(rmcSentence)

        assertTrue("Should successfully parse valid RMC sentence", result is NmeaParser.ParseResult.Success)
        val success = result as NmeaParser.ParseResult.Success
        assertTrue("Should be an RMC sentence", success.sentence is NmeaParser.NmeaSentence.RmcSentence)

        val rmcSentenceResult = success.sentence as NmeaParser.NmeaSentence.RmcSentence
        val rmc = rmcSentenceResult.rmc

        assertNotNull("TimeRaw should not be null", rmc.timeRaw)
        assertNotNull("Date should not be null", rmc.date)
        assertEquals("TimeRaw should match", "123456.78", rmc.timeRaw)
        assertEquals("Date should match", "090925", rmc.date)

        // Verify timestampSource is GNSS_PROVIDER when both time and date are present
        assertEquals(
            "RMC sentence with time and date should have timestampSource = TimestampSource.GNSS_PROVIDER",
            TimestampSource.GNSS_PROVIDER,
            rmc.timestampSource
        )
    }

    @Test
    fun `when valid GST sentence with time is parsed, expect timestampSource is GNSS_PROVIDER`() {
        // Valid GST sentence with time
        val gstSentence = "\$GPGST,123456.78,1.2,0.95,0.66,123.45,0.52,0.88,1.1*42"

        val result = parser.parseDetailed(gstSentence)

        assertTrue("Should successfully parse valid GST sentence", result is NmeaParser.ParseResult.Success)
        val success = result as NmeaParser.ParseResult.Success
        assertTrue("Should be a GST sentence", success.sentence is NmeaParser.NmeaSentence.GstSentence)

        val gstSentenceResult = success.sentence as NmeaParser.NmeaSentence.GstSentence
        val gst = gstSentenceResult.gst

        assertNotNull("TimeRaw should not be null", gst.timeRaw)
        assertEquals("TimeRaw should match", "123456.78", gst.timeRaw)

        // Verify timestampSource is GNSS_PROVIDER when time is present
        assertEquals(
            "GST sentence with time should have timestampSource = TimestampSource.GNSS_PROVIDER",
            TimestampSource.GNSS_PROVIDER,
            gst.timestampSource
        )
    }

    @Test
    fun `when GSV sentence is parsed, expect timestampSource is null`() {
        // Valid GSV sentence (doesn't contain timing information)
        val gsvSentence = "\$GPGSV,2,1,08,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45*75"

        val result = parser.parseDetailed(gsvSentence)

        assertTrue("Should successfully parse valid GSV sentence", result is NmeaParser.ParseResult.Success)
        val success = result as NmeaParser.ParseResult.Success
        assertTrue("Should be a GSV sentence", success.sentence is NmeaParser.NmeaSentence.GsvSentence)

        val gsvSentenceResult = success.sentence as NmeaParser.NmeaSentence.GsvSentence
        val gsv = gsvSentenceResult.gsv

        // Verify timestampSource is null for GSV (no timing info)
        assertEquals(
            "GSV sentence should have timestampSource = null (no timing info)",
            null,
            gsv.timestampSource
        )
    }
}
