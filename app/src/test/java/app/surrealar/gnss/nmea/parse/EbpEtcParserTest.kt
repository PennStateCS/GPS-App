package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.bus.adapters.NmeaFuser
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.nmea.sentence.EBP
import app.surrealar.gnss.nmea.sentence.ETC
import app.surrealar.gnss.nmea.sentence.GGA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EbpEtcParserTest {

    // Checksum-validating registry (production behavior). EBP/ETC are registered in DefaultNmeaRegistry.
    private val registry = DefaultNmeaRegistry.create(verifyChecksum = true)

    /** Appends the correct NMEA XOR checksum to a payload (chars between '$' and '*'). */
    private fun cs(payload: String): String {
        var x = 0
        for (c in payload) x = x xor c.code
        return "\$" + payload + "*" + "%02X".format(x and 0xFF)
    }

    @Test
    fun `valid EBP sentence parses base position (real RS4 format, no time field)`() {
        val ebp = registry.parse(cs("GNEBP,4807.038,N,01131.000,E,545.4,M")) as? EBP
        assertNotNull("EBP should parse", ebp)
        assertEquals("GN", ebp?.talker)
        assertEquals(48.1173, ebp?.baseLat ?: 0.0, 0.0001)
        assertEquals(11.516667, ebp?.baseLon ?: 0.0, 0.0001)
        assertEquals(545.4, ebp?.baseAltM ?: 0.0, 0.0001)
    }

    @Test
    fun `ETC timestamp-only has no orientation data`() {
        val etc = registry.parse(cs("GNETC,172424.80,,,,,,,,")) as? ETC
        assertNotNull("ETC should parse", etc)
        assertEquals("172424.80", etc?.timeRaw)
        assertFalse("all-blank data fields → no orientation", etc?.hasOrientationData ?: true)
    }

    @Test
    fun `ETC with IMU payload is captured raw (no field semantics assumed)`() {
        val etc = registry.parse(cs("GNETC,173025.40,30,00,268.660,116.146,17.651,6.280,6.280,6.991")) as? ETC
        assertNotNull("ETC should parse", etc)
        assertEquals("173025.40", etc?.timeRaw)
        assertTrue("orientation data present", etc?.hasOrientationData ?: false)
        // Raw fields carried verbatim; NOT interpreted as heading/tilt.
        assertEquals(listOf("30", "00", "268.660", "116.146", "17.651", "6.280", "6.280", "6.991"), etc?.dataFields)
    }

    @Test
    fun `EBP with missing fields parses tolerantly with nulls`() {
        val ebp = registry.parse(cs("GNEBP,,,,,")) as? EBP
        assertNotNull(ebp)
        assertNull(ebp?.baseLat)
        assertNull(ebp?.baseLon)
        assertNull(ebp?.baseAltM)
    }

    @Test
    fun `bad checksum is rejected like other sentences`() {
        val good = cs("GNEBP,4807.038,N,01131.000,E,545.4,M")
        val corrupted = good.dropLast(2) + "00" // wrong checksum
        assertNull("EBP with wrong checksum should be rejected", registry.parse(corrupted))
    }

    @Test
    fun `unknown RS4 sentence remains non-fatal`() {
        // A vendor sentence with no registered parser returns null and never throws.
        assertNull(registry.parse(cs("GNXYZ,1,2,3")))
    }

    @Test
    fun `registering EBP and ETC does not disturb standard GGA`() {
        val gga = registry.parse(
            "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        ) as? GGA
        assertNotNull("GGA must still parse", gga)
        assertEquals(48.1173, gga?.lat ?: 0.0, 0.0001)
    }

    @Test
    fun `fuser sees EBP and ETC without affecting fix generation`() {
        val fixes = mutableListOf<Fix>()
        val fuser = NmeaFuser(
            provider = Provider.RS2_EXTERNAL,
            registry = DefaultNmeaRegistry.create(verifyChecksum = false),
            onFix = { fixes.add(it) },
            onGsv = { }
        )

        fuser.accept("\$GPGGA,123519.00,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,2.0,0001")
        assertEquals("GGA should emit exactly one fix", 1, fixes.size)

        // EBP at a DIFFERENT position than the rover — must never become the fix.
        fuser.accept("\$GNEBP,4118.399,N,07601.036,W,374.0,M")
        fuser.accept("\$GNETC,173025.40,30,00,268.660,116.146,17.651,6.280,6.280,6.991")

        assertEquals("EBP/ETC must NOT emit additional fixes", 1, fixes.size)
        assertEquals("rover fix latitude must be unaffected by EBP", 48.1173, fixes[0].latDeg, 0.001)
        val stats = fuser.nmeaCustomStats
        assertTrue("EBP should be marked seen", stats.ebpSeen)
        assertTrue("ETC should be marked seen", stats.etcSeen)
        assertTrue("IMU/orientation should be flagged", stats.imuOrientationSeen)
        assertEquals(1, stats.ebpCount)
        assertEquals(1, stats.etcCount)
    }
}
