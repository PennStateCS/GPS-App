package app.surrealar.receiver.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stabilization item 9: the connection test counts only valid NMEA (shape + verified checksum + parsed by geokit's
 * live registry), accepts receivers that have no position yet, and tells malformed `$` text apart from RTCM/binary.
 */
class NmeaProbeClassifierTest {

    private companion object {
        // Checksums computed for these exact payloads.
        const val GGA_FIX = "\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*59"
        const val GGA_NO_FIX = "\$GNGGA,123519,,,,,0,00,,,M,,M,,*75"
        const val RMC_VOID = "\$GNRMC,123519,V,,,,,,,230394,,,N*4F"
        const val RMC_ACTIVE = "\$GNRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*74"
        const val GSV = "\$GPGSV,1,1,04,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45*7A"
        /** Valid shape and checksum, but no sentence the live pipeline can read. */
        const val UNKNOWN_TYPE = "\$HELLO,world*0C"
    }

    private fun run(vararg lines: String): TcpProbeResult {
        val c = NmeaProbeClassifier()
        for (l in lines) c.offer(l)?.let { return it }
        return c.verdict()
    }

    @Test fun `a valid GGA with a fix is NMEA with a position and ends the sniff at once`() {
        val c = NmeaProbeClassifier()
        assertEquals(TcpProbeResult.RECEIVING_NMEA, c.offer(GGA_FIX))
        assertEquals(TcpProbeResult.RECEIVING_NMEA, run(RMC_ACTIVE))
    }

    @Test fun `valid NMEA without a fix is reachable with no position yet`() {
        assertEquals(TcpProbeResult.RECEIVING_NMEA_NO_POSITION, run(GGA_NO_FIX))
        assertEquals(TcpProbeResult.RECEIVING_NMEA_NO_POSITION, run(RMC_VOID))
        // An explicit "no position" GGA/RMC ends the sniff early.
        assertEquals(TcpProbeResult.RECEIVING_NMEA_NO_POSITION, NmeaProbeClassifier().offer(GGA_NO_FIX))
    }

    @Test fun `other supported sentences alone are valid NMEA without a position`() {
        val c = NmeaProbeClassifier()
        assertNull("GSV says nothing about the position: keep reading", c.offer(GSV))
        assertEquals(TcpProbeResult.RECEIVING_NMEA_NO_POSITION, c.verdict())
        // ... and a position that follows still wins.
        assertEquals(TcpProbeResult.RECEIVING_NMEA, run(GSV, GSV, GGA_FIX))
    }

    @Test fun `arbitrary dollar text and bad checksums are not NMEA`() {
        assertEquals(TcpProbeResult.INVALID_NMEA, run("\$hello world", "\$\$\$", "$"))
        // The textbook GPGGA checksum (*47) on a GNGGA line: the checksum does not verify.
        assertEquals(TcpProbeResult.INVALID_NMEA,
            run("\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"))
        // No checksum, a one-digit checksum, a non-hex checksum, trailing junk, no fields.
        assertEquals(TcpProbeResult.INVALID_NMEA, run(
            "\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,",
            "\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*5",
            "\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*ZZ",
            "$GGA_FIX junk",
            "\$GNGGA*00",
        ))
        assertEquals("a type the live pipeline cannot read", TcpProbeResult.INVALID_NMEA, run(UNKNOWN_TYPE))
    }

    @Test fun `a truncated sentence is not NMEA`() {
        assertEquals(TcpProbeResult.INVALID_NMEA, run(GGA_FIX.dropLast(10)))
    }

    @Test fun `mixed garbage with one valid sentence is NMEA`() {
        assertEquals(TcpProbeResult.RECEIVING_NMEA, run("\$garbage*00", "\u0001\u0002binary", "\$GNGGA,bad*11", GGA_FIX))
        assertEquals(TcpProbeResult.RECEIVING_NMEA_NO_POSITION, run("\$garbage", "binary", GSV, "\$more garbage"))
    }

    @Test fun `binary or RTCM without dollar lines and an empty stream keep their results`() {
        assertEquals(TcpProbeResult.RECEIVING_RTCM_OR_BIN, run("Ó\u0000\u0013>Ð", "xyz"))
        assertEquals(TcpProbeResult.CONNECTED_NO_DATA, run())
        assertEquals(TcpProbeResult.CONNECTED_NO_DATA, run("", "   "))
    }

    @Test fun `reachable covers NMEA with and without a position only`() {
        assertTrue(TcpProbeResult.RECEIVING_NMEA.reachable)
        assertTrue(TcpProbeResult.RECEIVING_NMEA_NO_POSITION.reachable)
        for (r in listOf(TcpProbeResult.INVALID_NMEA, TcpProbeResult.RECEIVING_RTCM_OR_BIN,
                TcpProbeResult.CONNECTED_NO_DATA, TcpProbeResult.CONNECT_FAILED)) assertFalse(r.name, r.reachable)
    }

    @Test fun `malformed NMEA has its own actionable message`() {
        val malformed = ConnectError.NotNmea(9001, malformedNmea = true)
        assertTrue(malformed.message, malformed.message.contains("not valid NMEA"))
        assertTrue(malformed.message, malformed.message.contains("port"))
        assertEquals("Port 9001 sends data that is not NMEA (RTCM or binary). Choose the NMEA output port.",
            ConnectError.NotNmea(9001).message)
    }
}
