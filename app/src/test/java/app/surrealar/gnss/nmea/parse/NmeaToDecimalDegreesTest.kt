package app.surrealar.gnss.nmea.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [NmeaCoordinateUtils.toDecimalDegrees] — the shared converter behind GGA/RMC/EBP parsing. */
class NmeaToDecimalDegreesTest {

    private fun lon(raw: String?, hem: String? = "W") = NmeaCoordinateUtils.toDecimalDegrees(raw, hem, isLatitude = false)
    private fun lat(raw: String?, hem: String? = "N") = NmeaCoordinateUtils.toDecimalDegrees(raw, hem, isLatitude = true)

    @Test fun `zero-padded longitude parses`() {
        assertEquals(-76.02251500, lon("07601.3509")!!, 1e-8)
    }

    @Test fun `longitude without leading zero parses the same (Samsung internal GNSS)`() {
        // Previously read as 760° 1.3509' → −760.0225.
        assertEquals(-76.02251500, lon("7601.3509")!!, 1e-8)
    }

    @Test fun `two-digit and one-digit degree longitudes parse`() {
        assertEquals(11.5166667, lon("1131.000", "E")!!, 1e-6)
        assertEquals(5.5, lon("530.0", "E")!!, 1e-9)
        assertEquals(-5.5, lon("00530.0")!!, 1e-9)
    }

    @Test fun `latitude parses padded and unpadded`() {
        assertEquals(41.3477735, lat("4120.86641")!!, 1e-6)
        assertEquals(5.5, lat("0530.0")!!, 1e-9)
        assertEquals(5.5, lat("530.0")!!, 1e-9)
        assertEquals(-48.1173, lat("4807.038", "S")!!, 1e-4)
    }

    @Test fun `no decimal point still splits minutes correctly`() {
        assertEquals(-76.0166667, lon("07601")!!, 1e-6)
    }

    @Test fun `null hemisphere keeps positive, unknown hemisphere rejects`() {
        assertEquals(76.0225150, lon("07601.3509", null)!!, 1e-6)
        assertNull(lon("07601.3509", "N"))
        assertNull(lat("4120.86641", "E"))
    }

    @Test fun `impossible values return null instead of a bogus coordinate`() {
        assertNull(lon("18100.0"))      // 181°
        assertNull(lat("9100.0"))       // 91°
        assertNull(lon("07660.0"))      // 60 minutes
        assertNull(lon("76001.3509"))   // 760°
        assertNull(lon("-7601.0"))
    }

    @Test fun `blank or malformed returns null`() {
        assertNull(lon(null))
        assertNull(lon(""))
        assertNull(lon("12"))
        assertNull(lon("abc.def"))
    }

    @Test fun `GGA with unpadded longitude yields valid fix coords`() {
        val gga = NmeaRegistry(mapOf("GGA" to GgaParser()))
            .parse(withChecksum("GPGGA,160141.00,4120.86641,N,7601.35093,W,1,07,1.2,399.3,M,-33.0,M,,"))
            as app.surrealar.gnss.nmea.sentence.GGA
        assertEquals(41.3477735, gga.lat!!, 1e-6)
        assertEquals(-76.0225155, gga.lon!!, 1e-6)
    }

    private fun withChecksum(body: String): String {
        val cs = body.fold(0) { acc, c -> acc xor c.code }
        return "\$$body*${"%02X".format(cs)}"
    }
}
