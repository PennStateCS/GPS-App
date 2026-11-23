package app.surrealar.gnss.nmea.parse

import app.surrealar.di.GnssModule
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.nmea.sentence.GGA
import org.junit.Assert.*
import org.junit.Test

/**
 * A TCP drop mid-sentence leaves readLine() with a partial line and no "*CS". It must never
 * publish a fix — in particular not with a missing W read as East.
 */
class NmeaTruncatedLineTest {

    private val truncated = "\$GNGGA,172424.80,4118.4448800,N,07601"
    private val full =
        "\$GNGGA,172424.80,4118.4448800,N,07601.0300517,W,4,22,1.0,395.539,M,-33.145,M,1.8,1116*6F"

    @Test fun `live registry requires a checksum`() {
        val live = GnssModule.provideNmeaRegistry()
        assertNull(live.parse(truncated))
        assertNotNull("checksummed Emlid line still parses", live.parse(full))
    }

    @Test fun `live registry parses the Emlid fixtures exactly as a lenient one does`() {
        val live = GnssModule.provideNmeaRegistry()
        // Same parser set as the live registry (standard + every profile's extra parsers), lenient checksums.
        val lenient = DefaultNmeaRegistry.create(
            verifyChecksum = false,
            extraParsers = app.surrealar.settings.model.ExternalReceiverProfile.allExtraParsers(),
        )
        for (res in listOf("/nmea/reach_nmea_capture.txt", "/nmea/reach_nmea_rs2_capture.txt")) {
            val lines = javaClass.getResourceAsStream(res)!!.bufferedReader().readLines()
            val strictCount = lines.count { live.parse(it) != null }
            val lenientCount = lines.count { lenient.parse(it) != null }
            assertTrue("$res parses something", strictCount > 100)
            assertEquals("$res: requiring checksums drops no real line", lenientCount, strictCount)
        }
    }

    @Test fun `live registry parses the SM-X940 sample sentences`() {
        val live = GnssModule.provideNmeaRegistry()
        // Samsung SM-X940 internal GNSS payloads (from GsvParserTest / NmeaToDecimalDegreesTest), sent
        // by Android with their checksum — which the live registry now requires.
        val payloads = listOf(
            "GLGSV,3,1,9,88,57,024,28.3,65,41,106,23.5,79,39,289,,81,36,298,19.8,1",
            "GNGGA,172424.00,4118.4448,N,7601.3509,W,1,07,1.2,399.3,M,-33.0,M,,",
            "GNACCURACY,10.5,0.0,0.2,12.4",
        )
        for (p in payloads) {
            assertNotNull("SM-X940 line parses: $p", live.parse(withChecksum(p)))
            assertNull("…but not without its checksum: $p", live.parse("$$p"))
        }
    }

    private fun withChecksum(payload: String): String {
        val cs = payload.fold(0) { acc, c -> acc xor c.code } and 0xFF
        return "$" + payload + "*" + "%02X".format(cs)
    }

    // "missing hemisphere is not read as positive" moved to geokit-gnss's NmeaCoordinateUtilsTest
    // (NmeaCoordinateUtils is internal to the module).

    @Test fun `even a lenient registry gives no position for the truncated GGA`() {
        val gga = DefaultNmeaRegistry.create(verifyChecksum = false).parse(truncated) as? GGA
        assertNull("no longitude without its hemisphere", gga?.lon)

        val fixes = mutableListOf<Fix>()
        val fuser = NmeaFuser(Provider.EXTERNAL_TCP, DefaultNmeaRegistry.create(verifyChecksum = false),
            onFix = { fixes.add(it) }, onGsv = {})
        fuser.accept(truncated); fuser.flush()
        assertTrue("no fix from a truncated line", fixes.isEmpty())
    }
}
