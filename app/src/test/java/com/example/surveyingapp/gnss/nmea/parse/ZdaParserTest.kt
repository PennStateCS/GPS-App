package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.ZDA
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ZdaParserTest {

    private lateinit var parser: ZdaParser
    private lateinit var registry: NmeaRegistry

    @Before
    fun setUp() {
        parser = ZdaParser()
        registry = NmeaRegistry(mapOf("ZDA" to parser))
    }

    @Test
    fun `parse golden ZDA sentence with all fields`() {
        // Golden ZDA sentence with full date and time information
        val sentence = "\$GPZDA,123456.78,09,09,2025,00,00*6B"

        val result = registry.parse(sentence) as? ZDA

        assertNotNull("Should parse valid ZDA sentence", result)
        assertEquals("123456.78", result?.timeRaw)
        assertEquals(9, result?.day)
        assertEquals(9, result?.month)
        assertEquals(2025, result?.year)
        assertNotNull("Epoch millis should be computed", result?.epochMillis)

        // Verify computed epoch time (12:34:56.78 on 2025-09-09 UTC)
        val expectedDate = LocalDate.of(2025, 9, 9)
        val expectedTime = LocalTime.of(12, 34, 56, 780_000_000) // 780ms in nanos
        val expectedEpoch = ZonedDateTime.of(expectedDate, expectedTime, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expectedEpoch, result?.epochMillis)
    }

    @Test
    fun `parse golden ZDA sentence without fractional seconds`() {
        // Golden ZDA sentence with whole seconds only
        val sentence = "\$GPZDA,235959,31,12,2024,00,00*4C"

        val result = registry.parse(sentence) as? ZDA

        assertNotNull("Should parse ZDA without fractional seconds", result)
        assertEquals("235959", result?.timeRaw)
        assertEquals(31, result?.day)
        assertEquals(12, result?.month)
        assertEquals(2024, result?.year)

        // Verify last second of year 2024
        val expectedDate = LocalDate.of(2024, 12, 31)
        val expectedTime = LocalTime.of(23, 59, 59)
        val expectedEpoch = ZonedDateTime.of(expectedDate, expectedTime, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expectedEpoch, result?.epochMillis)
    }

    @Test
    fun `parse golden GLONASS ZDA sentence`() {
        // Golden GLONASS ZDA sentence
        val sentence = "\$GLZDA,061006.00,12,07,2023,00,00*7C"

        val result = registry.parse(sentence) as? ZDA

        assertNotNull("Should parse GLONASS ZDA", result)
        assertEquals("061006.00", result?.timeRaw)
        assertEquals(12, result?.day)
        assertEquals(7, result?.month)
        assertEquals(2023, result?.year)
    }

    @Test
    fun `parse golden Galileo ZDA sentence`() {
        // Golden Galileo ZDA sentence
        val sentence = "\$GAZDA,120030.50,01,01,2025,00,00*77"

        val result = registry.parse(sentence) as? ZDA

        assertNotNull("Should parse Galileo ZDA", result)
        assertEquals("120030.50", result?.timeRaw)
        assertEquals(1, result?.day)
        assertEquals(1, result?.month)
        assertEquals(2025, result?.year)

        // Verify New Year's Day
        val expectedDate = LocalDate.of(2025, 1, 1)
        val expectedTime = LocalTime.of(12, 0, 30, 500_000_000) // 500ms in nanos
        val expectedEpoch = ZonedDateTime.of(expectedDate, expectedTime, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expectedEpoch, result?.epochMillis)
    }

    @Test
    fun `parse ZDA with leap year date`() {
        // ZDA sentence on leap year day (Feb 29)
        val sentence = "\$GPZDA,120000.00,29,02,2024,00,00*68"

        val result = registry.parse(sentence) as? ZDA

        assertNotNull("Should parse leap year date", result)
        assertEquals(29, result?.day)
        assertEquals(2, result?.month)
        assertEquals(2024, result?.year)

        // Verify leap day
        val expectedDate = LocalDate.of(2024, 2, 29)
        val expectedTime = LocalTime.of(12, 0, 0)
        val expectedEpoch = ZonedDateTime.of(expectedDate, expectedTime, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expectedEpoch, result?.epochMillis)
    }

    @Test
    fun `parse ZDA with timezone offset`() {
        // ZDA sentence with timezone information (+05:30)
        val sentence = "\$GPZDA,143022.00,15,08,2025,05,30*6F"

        val result = registry.parse(sentence) as? ZDA

        assertNotNull("Should parse ZDA with timezone", result)
        assertEquals("143022.00", result?.timeRaw)
        assertEquals(15, result?.day)
        assertEquals(8, result?.month)
        assertEquals(2025, result?.year)
        // Note: The epoch calculation should still be in UTC, but the timezone info is preserved
    }

    @Test
    fun `parse ZDA with midnight time`() {
        // ZDA sentence at exactly midnight
        val sentence = "\$GPZDA,000000.00,01,06,2025,00,00*64"

        val result = registry.parse(sentence) as? ZDA

        assertNotNull("Should parse midnight ZDA", result)
        assertEquals("000000.00", result?.timeRaw)
        assertEquals(1, result?.day)
        assertEquals(6, result?.month)
        assertEquals(2025, result?.year)

        // Verify midnight
        val expectedDate = LocalDate.of(2025, 6, 1)
        val expectedTime = LocalTime.of(0, 0, 0)
        val expectedEpoch = ZonedDateTime.of(expectedDate, expectedTime, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expectedEpoch, result?.epochMillis)
    }

    @Test
    fun `parse ZDA with missing date fields returns null`() {
        // ZDA with incomplete date information
        val sentence = "\$GPZDA,120000.00,,,2025,00,00*60"

        val result = registry.parse(sentence) as? ZDA

        assertNotNull("Should parse but with null epoch", result)
        assertEquals("120000.00", result?.timeRaw)
        assertNull("Day should be null", result?.day)
        assertNull("Month should be null", result?.month)
        assertNull("Epoch should be null with missing date", result?.epochMillis)
    }

    @Test
    fun `parse sparse ZDA sentence yields no epoch`() {
        // Structurally valid talker+tag but missing date fields: parsed tolerantly with null epoch.
        val result = registry.parse("\$GPZDA,120000") as? ZDA

        assertNotNull("Sparse ZDA still parses (best-effort)", result)
        assertNull("Epoch should be null without a date", result?.epochMillis)
    }

    @Test
    fun `parse ZDA sentence without checksum`() {
        val sentence = "\$GPZDA,123456.78,09,09,2025,00,00"

        val result = registry.parse(sentence) as? ZDA

        assertNotNull("Should parse sentence without checksum", result)
        assertEquals(9, result?.day)
        assertEquals(9, result?.month)
        assertEquals(2025, result?.year)
    }
}
