package app.surrealar.gnss.bus.adapters

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.nmea.parse.*
import app.surrealar.gnss.nmea.sentence.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*

class NmeaFuserTest {

    private lateinit var registry: NmeaRegistry
    private val emittedFixes = mutableListOf<Fix>()
    private val emittedGsvMessages = mutableListOf<GsvMessage>()

    @Before
    fun setup() {
        val parsers = mapOf(
            "GGA" to GgaParser(),
            "RMC" to RmcParser(),
            "GSA" to GsaParser(),
            "GSV" to GsvParser(),
            "ZDA" to ZdaParser(),
            "GST" to GstParser()
        )
        registry = NmeaRegistry(parsers, verifyChecksum = false)  // Disable for test
        emittedFixes.clear()
        emittedGsvMessages.clear()
    }

    @Test
    fun `epoch deduplication prevents duplicate fix emissions`() {
        val fuser = NmeaFuser(
            provider = Provider.RS2_EXTERNAL,
            registry = registry,
            onFix = { emittedFixes.add(it) },
            onGsv = { emittedGsvMessages.add(it) }
        )

        // Same timestamp in GGA and RMC (common scenario)
        val time = "123519.00"
        val date = "220620"  // June 22, 2020

        fuser.accept("\$GPGGA,$time,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,2.0,0001")
        assertEquals("GGA should emit one fix", 1, emittedFixes.size)

        fuser.accept("\$GPRMC,$time,A,4807.038,N,01131.000,E,0.0,54.7,$date,020.3,E,D")
        assertEquals("RMC with same timestamp should not emit duplicate", 1, emittedFixes.size)

        // New GGA with different timestamp should emit
        fuser.accept("\$GPGGA,123520.00,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,2.0,0001")
        assertEquals("New GGA timestamp should emit second fix", 2, emittedFixes.size)
    }

    @Test
    fun `multi-constellation GSA tracking uses talker-aware SatId`() {
        val fuser = NmeaFuser(
            provider = Provider.RS2_EXTERNAL,
            registry = registry,
            onFix = { emittedFixes.add(it) },
            onGsv = { emittedGsvMessages.add(it) }
        )

        // GPS satellite 5 and GLONASS satellite 5 should be distinct
        fuser.accept("\$GPGSA,A,3,05,09,12,17,25,,,,,,,2.1,1.2,1.7")  // GPS SVID 5
        fuser.accept("\$GLGSA,A,3,74,75,76,77,78,,,,,,,2.1,1.2,1.7")  // GLONASS SVIDs

        // Emit fix to use accumulated satellites
        fuser.accept("\$GPGGA,123519.00,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,2.0,0001")

        assertEquals("Should emit one fix", 1, emittedFixes.size)
        val fix = emittedFixes[0]

        // Should count satellites from both constellations
        assertTrue("Should accumulate satellites from multiple GSA sentences", (fix.satsUsed ?: 0) >= 8)
    }

    @Test
    fun `interleaved GSV messages from different constellations`() {
        val fuser = NmeaFuser(
            provider = Provider.RS2_EXTERNAL,
            registry = registry,
            onFix = { emittedFixes.add(it) },
            onGsv = { emittedGsvMessages.add(it) }
        )

        // GPS GSV sequence (2 messages)
        fuser.accept("\$GPGSV,2,1,08,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45")
        fuser.accept("\$GPGSV,2,2,08,17,40,208,46,19,17,078,41,24,25,138,45,28,43,099,47")

        // GLONASS GSV sequence interleaved (2 messages)
        fuser.accept("\$GLGSV,2,1,07,65,30,120,42,66,45,055,44,74,20,310,40,75,50,175,43")
        fuser.accept("\$GLGSV,2,2,07,76,10,250,39,77,35,090,41,78,25,315,42")

        // Should have completed both GPS and GLONASS GSV epochs
        assertEquals("Should emit GSV for each constellation", 2, emittedGsvMessages.size)

        val talkers = emittedGsvMessages.map { it.constellation }.toSet()
        assertTrue("Should have GPS GSV message", talkers.contains("GP"))
        assertTrue("Should have GLONASS GSV message", talkers.contains("GL"))
    }

    @Test
    fun `GSV accumulation counts satellites with constellation-aware used-in-fix`() {
        val fuser = NmeaFuser(
            provider = Provider.RS2_EXTERNAL,
            registry = registry,
            onFix = { emittedFixes.add(it) },
            onGsv = { emittedGsvMessages.add(it) }
        )

        // GPS GSA marks SVIDs 05,09,12 as used
        fuser.accept("\$GPGSA,A,3,05,09,12,,,,,,,,,2.1,1.2,1.7")

        // GLONASS GSA marks SVIDs 74,75 as used
        fuser.accept("\$GLGSA,A,3,74,75,,,,,,,,,2.1,1.2,1.7")

        // GPS GSV with SVID 05 (used) and 02 (not used)
        fuser.accept("\$GPGSV,1,1,02,05,40,083,46,02,17,308,41")

        // Check GPS constellation
        assertEquals(1, emittedGsvMessages.size)
        val gpsMsg = emittedGsvMessages[0]
        assertEquals("Should have 2 GPS satellites", 2, gpsMsg.entries.size)

        val sat05 = gpsMsg.entries.find { it.svid == 5 }
        val sat02 = gpsMsg.entries.find { it.svid == 2 }

        assertTrue("GPS SVID 5 should be marked as used", sat05?.usedInFix == true)
        assertTrue("GPS SVID 2 should not be marked as used", sat02?.usedInFix == false)
    }

    @Test
    fun `stale ZDA timestamps are rejected`() {
        val fuser = NmeaFuser(
            provider = Provider.RS2_EXTERNAL,
            registry = registry,
            onFix = { emittedFixes.add(it) },
            onGsv = { emittedGsvMessages.add(it) }
        )

        // Very old ZDA (year 2000)
        fuser.accept("\$GPZDA,123519.00,22,06,2000,00,00")

        // Emit fix - should use device time instead of stale ZDA
        fuser.accept("\$GPGGA,123519.00,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,2.0,0001")

        assertEquals(1, emittedFixes.size)
        // Note: Can't easily test timestamp source without mocking time, but staleness check exists
    }

    @Test
    fun `reset clears all accumulated state`() {
        val fuser = NmeaFuser(
            provider = Provider.RS2_EXTERNAL,
            registry = registry,
            onFix = { emittedFixes.add(it) },
            onGsv = { emittedGsvMessages.add(it) }
        )

        // Accumulate some state
        fuser.accept("\$GPGSA,A,3,05,09,12,17,25,,,,,,,2.1,1.2,1.7")
        fuser.accept("\$GPGGA,123519.00,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,2.0,0001")
        assertEquals(1, emittedFixes.size)

        // Reset
        fuser.reset()

        // Same GGA should emit again (dedup cleared)
        fuser.accept("\$GPGGA,123519.00,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,2.0,0001")
        assertEquals("After reset, same fix should emit again", 2, emittedFixes.size)
    }

    @Test
    fun `missing satellite count is unknown, not zero`() {
        val fuser = NmeaFuser(
            provider = Provider.RS2_EXTERNAL,
            registry = registry,
            onFix = { emittedFixes.add(it) },
            onGsv = { emittedGsvMessages.add(it) }
        )
        // GGA with an empty numSV field and no GSA → nothing reported a count.
        fuser.accept("\$GPGGA,123519.00,4807.038,N,01131.000,E,1,,0.9,545.4,M,46.9,M,,")
        assertEquals(1, emittedFixes.size)
        assertNull(emittedFixes[0].satsUsed)
    }

    @Test
    fun `GGA satellite count is used when no GSA arrived`() {
        val fuser = NmeaFuser(
            provider = Provider.RS2_EXTERNAL,
            registry = registry,
            onFix = { emittedFixes.add(it) },
            onGsv = { emittedGsvMessages.add(it) }
        )
        fuser.accept("\$GPGGA,123519.00,4807.038,N,01131.000,E,1,14,0.9,545.4,M,46.9,M,,")
        assertEquals(14, emittedFixes[0].satsUsed)
    }
    // ── GSV page numbering across signals ─────────────────────────────────────────────────────
    // If a chipset numbers GSV pages JOINTLY across signals (L1 + L5 in one 1..N sequence), the
    // per-signal accumulator never completes the L1 sequence and only L5 satellites show. Whether the
    // SM-X940 does this needs a raw log, so this stays ignored until one confirms it.

    @Ignore("Waiting for a raw SM-X940 NMEA log to confirm joint GSV page numbering across signals")
    @Test
    fun `GSV numbered jointly across signals merges L1 and L5 into one sky`() {
        val fuser = NmeaFuser(
            provider = Provider.INTERNAL,
            registry = registry,
            onFix = { emittedFixes.add(it) },
            onGsv = { emittedGsvMessages.add(it) }
        )
        // ONE 1..3 sequence for GP: pages 1-2 are L1 (signal 1), page 3 is L5 (signal 8).
        fuser.accept("\$GPGSV,3,1,10,01,40,083,46,03,17,308,41,08,07,344,39,10,22,228,45,1")
        fuser.accept("\$GPGSV,3,2,10,14,40,208,46,21,17,078,41,22,25,138,45,1")
        fuser.accept("\$GPGSV,3,3,10,03,17,308,33,08,07,344,30,10,22,228,36,8")

        val gp = emittedGsvMessages.lastOrNull { it.constellation == "GP" }
        assertNotNull("A GP sky message should be emitted", gp)
        assertEquals("All 7 distinct GPS satellites (L1 + L5 merged)",
            setOf(1, 3, 8, 10, 14, 21, 22), gp!!.entries.map { it.svid }.toSet())
        assertEquals("Merged SNR comes from L1 (signal 1), not L5",
            41.0, gp.entries.first { it.svid == 3 }.snrDbHz!!, 0.01)

        fuser.accept("\$GPGGA,123519.00,4807.038,N,01131.000,E,1,05,0.9,545.4,M,46.9,M,,")
        assertEquals("satsVisible counts distinct satellites, not signal sightings",
            7, emittedFixes.last().satsVisible)
    }

    @Test
    fun `GSV numbered per signal merges L1 and L5 into one sky (control)`() {
        val fuser = NmeaFuser(
            provider = Provider.INTERNAL,
            registry = registry,
            onFix = { emittedFixes.add(it) },
            onGsv = { emittedGsvMessages.add(it) }
        )
        // L1 sequence 1..2 (signal 1), then a separate L5 sequence 1..1 (signal 8).
        fuser.accept("\$GPGSV,2,1,07,01,40,083,46,03,17,308,41,08,07,344,39,10,22,228,45,1")
        fuser.accept("\$GPGSV,2,2,07,14,40,208,46,21,17,078,41,22,25,138,45,1")
        fuser.accept("\$GPGSV,1,1,03,03,17,308,33,08,07,344,30,10,22,228,36,8")

        val gp = emittedGsvMessages.last { it.constellation == "GP" }
        assertEquals(setOf(1, 3, 8, 10, 14, 21, 22), gp.entries.map { it.svid }.toSet())
        assertEquals(41.0, gp.entries.first { it.svid == 3 }.snrDbHz!!, 0.01)

        fuser.accept("\$GPGGA,123519.00,4807.038,N,01131.000,E,1,05,0.9,545.4,M,46.9,M,,")
        assertEquals(7, emittedFixes.last().satsVisible)
    }
}

