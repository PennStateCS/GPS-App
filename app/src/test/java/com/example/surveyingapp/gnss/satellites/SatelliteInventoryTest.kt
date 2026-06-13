package com.example.surveyingapp.gnss.satellites

import com.example.surveyingapp.gnss.bus.adapters.GsvEntry
import com.example.surveyingapp.gnss.bus.adapters.GsvMessage
import com.example.surveyingapp.gnss.model.Constellation
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class SatelliteInventoryTest {

    private lateinit var inventory: SatelliteInventory
    private var currentTimeSeconds = 1000.0

    @Before
    fun setup() {
        currentTimeSeconds = 1000.0
        inventory = SatelliteInventory(
            halfLifeSeconds = 10.0,
            evictionSeconds = 30.0,
            nowSeconds = { currentTimeSeconds }
        )
    }

    @Test
    fun `reset clears all satellite state`() {
        // Add some satellites
        val gpsMsg = GsvMessage("GP", listOf(
            GsvEntry(svid = 5, elevationDeg = 45, azimuthDeg = 120, snrDbHz = 42.0, usedInFix = true),
            GsvEntry(svid = 9, elevationDeg = 30, azimuthDeg = 200, snrDbHz = 38.0, usedInFix = true)
        ))

        val snapshot1 = inventory.consume(gpsMsg)
        assertEquals(2, snapshot1.satellites.size, "Should have 2 GPS satellites")

        // Reset inventory
        inventory.reset()

        // Add different constellation
        val glonassMsg = GsvMessage("GL", listOf(
            GsvEntry(svid = 74, elevationDeg = 50, azimuthDeg = 180, snrDbHz = 40.0, usedInFix = true)
        ))

        val snapshot2 = inventory.consume(glonassMsg)
        assertEquals(1, snapshot2.satellites.size, "After reset, should only have new GLONASS satellite")
        assertEquals(Constellation.GLONASS, snapshot2.satellites[0].constellation, "Should be GLONASS")
    }

    @Test
    fun `constellation-aware tracking prevents SVID collisions`() {
        // GPS satellite 5
        val gpsMsg = GsvMessage("GP", listOf(
            GsvEntry(svid = 5, elevationDeg = 45, azimuthDeg = 120, snrDbHz = 42.0, usedInFix = true)
        ))
        inventory.consume(gpsMsg)

        // GLONASS satellite 5 (different constellation, same SVID)
        val glonassMsg = GsvMessage("GL", listOf(
            GsvEntry(svid = 5, elevationDeg = 30, azimuthDeg = 200, snrDbHz = 38.0, usedInFix = false)
        ))
        val snapshot = inventory.consume(glonassMsg)

        // Should have both satellites tracked separately
        assertEquals(2, snapshot.satellites.size, "GPS and GLONASS SVID 5 should be distinct")

        val constellations = snapshot.satellites.map { it.constellation }.toSet()
        assertTrue(constellations.contains(Constellation.GPS), "Should have GPS")
        assertTrue(constellations.contains(Constellation.GLONASS), "Should have GLONASS")
    }

    @Test
    fun `eviction removes stale satellites`() {
        // Add satellite at time 1000
        val msg1 = GsvMessage("GP", listOf(
            GsvEntry(svid = 5, elevationDeg = 45, azimuthDeg = 120, snrDbHz = 42.0, usedInFix = true)
        ))
        val snapshot1 = inventory.consume(msg1)
        assertEquals(1, snapshot1.satellites.size)

        // Advance time beyond eviction threshold (30 seconds + 1)
        currentTimeSeconds += 31.0

        // Add different satellite
        val msg2 = GsvMessage("GP", listOf(
            GsvEntry(svid = 9, elevationDeg = 30, azimuthDeg = 200, snrDbHz = 38.0, usedInFix = true)
        ))
        val snapshot2 = inventory.consume(msg2)

        // Old satellite should be evicted
        assertEquals(1, snapshot2.satellites.size, "Stale satellite should be evicted")
        assertEquals(9, snapshot2.satellites[0].svid, "Only new satellite should remain")
    }

    @Test
    fun `SNR smoothing with exponential moving average`() {
        // First measurement
        val msg1 = GsvMessage("GP", listOf(
            GsvEntry(svid = 5, elevationDeg = 45, azimuthDeg = 120, snrDbHz = 40.0, usedInFix = true)
        ))
        val snapshot1 = inventory.consume(msg1)
        assertEquals(40.0, snapshot1.satellites[0].cn0DbHz, 0.01, "Initial SNR should be exact")

        // Advance time by half-life (10 seconds) for predictable smoothing
        currentTimeSeconds += 10.0

        // Second measurement with different SNR
        val msg2 = GsvMessage("GP", listOf(
            GsvEntry(svid = 5, elevationDeg = 45, azimuthDeg = 120, snrDbHz = 30.0, usedInFix = true)
        ))
        val snapshot2 = inventory.consume(msg2)

        // Should be smoothed (between 30 and 40, closer to 30 after one half-life)
        val smoothedSnr = snapshot2.satellites[0].cn0DbHz!!
        assertTrue(smoothedSnr > 30.0 && smoothedSnr < 40.0, "SNR should be smoothed: $smoothedSnr")
    }

    @Test
    fun `talker constellation mapping handles all GNSS systems`() {
        val testCases = listOf(
            "GP" to Constellation.GPS,
            "GL" to Constellation.GLONASS,
            "GA" to Constellation.GALILEO,
            "GB" to Constellation.BEIDOU,
            "BD" to Constellation.BEIDOU,
            "GQ" to Constellation.QZSS,
            "GI" to Constellation.IRNSS,
            "GN" to Constellation.GPS  // Combined GNSS treats as GPS
        )

        testCases.forEach { (talker, expectedConstellation) ->
            inventory.reset()
            val msg = GsvMessage(talker, listOf(
                GsvEntry(svid = 1, elevationDeg = 45, azimuthDeg = 120, snrDbHz = 40.0, usedInFix = true)
            ))
            val snapshot = inventory.consume(msg)

            assertEquals(expectedConstellation, snapshot.satellites[0].constellation,
                "Talker $talker should map to $expectedConstellation")
        }
    }
}

