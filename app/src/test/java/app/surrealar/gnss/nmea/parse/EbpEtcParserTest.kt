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
    fun `valid EBP sentence parses base position`() {
        val ebp = registry.parse(cs("GNEBP,123519,4807.038,N,01131.000,E,545.4")) as? EBP
        assertNotNull("EBP should parse", ebp)
        assertEquals("GN", ebp?.talker)
        assertEquals(48.1173, ebp?.baseLat ?: 0.0, 0.0001)
        assertEquals(11.516667, ebp?.baseLon ?: 0.0, 0.0001)
        assertEquals(545.4, ebp?.baseAltM ?: 0.0, 0.0001)
    }

    @Test
    fun `valid ETC sentence parses tilt fields`() {
        val etc = registry.parse(cs("GNETC,123519,1,2.5,87.0,0")) as? ETC
        assertNotNull("ETC should parse", etc)
        assertEquals("1", etc?.tiltStatusRaw)
        assertEquals(2.5, etc?.tiltAngleDeg ?: 0.0, 1e-9)
        assertEquals(87.0, etc?.headingDeg ?: 0.0, 1e-9)
        assertEquals("0", etc?.warningRaw)
        assertTrue("tilt status '1' should read as active", etc?.tiltActive == true)
    }

    @Test
    fun `EBP with missing fields parses tolerantly with nulls`() {
        val ebp = registry.parse(cs("GNEBP,123519,,,,,")) as? EBP
        assertNotNull(ebp)
        assertNull(ebp?.baseLat)
        assertNull(ebp?.baseLon)
        assertNull(ebp?.baseAltM)
    }

    @Test
    fun `ETC with blank fields parses tolerantly with nulls`() {
        val etc = registry.parse(cs("GNETC,123519,,,,")) as? ETC
        assertNotNull(etc)
        assertNull(etc?.tiltAngleDeg)
        assertNull(etc?.headingDeg)
        assertFalse("blank status is not active", etc?.tiltActive == true)
    }

    @Test
    fun `bad checksum is rejected like other sentences`() {
        val good = cs("GNEBP,123519,4807.038,N,01131.000,E,545.4")
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

        fuser.accept("\$GNEBP,123519,4807.038,N,01131.000,E,545.4")
        fuser.accept("\$GNETC,123519,1,2.5,87.0,0")

        assertEquals("EBP/ETC must NOT emit additional fixes", 1, fixes.size)
        val stats = fuser.nmeaCustomStats
        assertTrue("EBP should be marked seen", stats.ebpSeen)
        assertTrue("ETC should be marked seen", stats.etcSeen)
        assertEquals(1, stats.ebpCount)
        assertEquals(1, stats.etcCount)
        assertEquals(2.5, stats.latestEtc?.tiltAngleDeg ?: 0.0, 1e-9)
    }
}
