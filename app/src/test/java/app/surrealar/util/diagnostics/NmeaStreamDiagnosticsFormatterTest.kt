package app.surrealar.util.diagnostics

import app.surrealar.gnss.bus.adapters.NmeaFuser
import app.surrealar.gnss.model.TimestampSource
import app.surrealar.gnss.nmea.sentence.ETC
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaStreamDiagnosticsFormatterTest {

    private fun timing(gga: Int = 0, rmc: Int = 0, zda: Int = 0, fixes: Int = 0, src: TimestampSource? = null) =
        NmeaFuser.FuserTimingStats(gga, rmc, zda, fixes, src)

    private fun custom(ebp: Int = 0, etc: Int = 0, latestEtc: ETC? = null) =
        NmeaFuser.NmeaCustomStats(ebp > 0, etc > 0, ebp, etc, latestEtc)

    private fun fmt(timing: NmeaFuser.FuserTimingStats, custom: NmeaFuser.NmeaCustomStats) =
        NmeaStreamDiagnosticsFormatter.format("Emlid Reach RS4", "TCP", "192.168.42.1", 9001, timing, custom)

    @Test
    fun `no data yet shows no first NMEA and absent EBP ETC as normal`() {
        val s = fmt(timing(), custom())
        assertTrue(s.contains("First NMEA received   : no"))
        assertTrue(s.contains("First fix emitted     : no"))
        assertTrue(s.contains("EBP seen              : no (normal"))
        assertTrue(s.contains("ETC seen              : no (normal"))
        assertTrue("profile/host/port included", s.contains("Emlid Reach RS4") && s.contains("192.168.42.1") && s.contains("9001"))
    }

    @Test
    fun `5Hz GGA and 5Hz fixes show equal counts`() {
        val s = fmt(timing(gga = 50, rmc = 10, zda = 10, fixes = 50, src = TimestampSource.NMEA_ZDA), custom())
        assertTrue(s.contains("GGA                   : 50"))
        assertTrue(s.contains("Emitted fixes         : 50"))
        assertTrue(s.contains("First fix emitted     : yes"))
        assertTrue(s.contains("Last fix date source  : GGA time + ZDA date"))
    }

    @Test
    fun `5Hz GGA but 1Hz fixes is visible as a count mismatch`() {
        val s = fmt(timing(gga = 50, rmc = 10, zda = 10, fixes = 10), custom())
        assertTrue(s.contains("GGA                   : 50"))
        assertTrue(s.contains("Emitted fixes         : 10"))
    }

    @Test
    fun `EBP and ETC present show seen counts and latest tilt`() {
        val etc = ETC(talker = "GN", timeRaw = "120000", tiltStatusRaw = "1",
            tiltAngleDeg = 2.5, headingDeg = 87.0, warningRaw = "0")
        val s = fmt(timing(gga = 50, fixes = 50), custom(ebp = 12, etc = 12, latestEtc = etc))
        assertTrue(s.contains("EBP seen              : yes (12)"))
        assertTrue(s.contains("ETC seen              : yes (12)"))
        assertTrue(s.contains("Latest ETC"))
        assertTrue(s.contains("tilt=2.5") && s.contains("heading=87.0"))
    }

    @Test
    fun `output contains no raw NMEA and no coordinate values`() {
        val etc = ETC("GN", "120000", "1", 2.5, 87.0, "0")
        val s = fmt(timing(gga = 50, rmc = 10, zda = 10, fixes = 50, src = TimestampSource.NMEA_ZDA),
            custom(ebp = 5, etc = 5, latestEtc = etc))
        assertFalse("no raw NMEA sentence prefix", s.contains("\$G"))
        assertFalse("no latitude label", s.contains("lat="))
        assertFalse("no longitude label", s.contains("lon="))
        // No base/live coordinate-looking decimals (lat/lon) are emitted by this formatter.
        assertFalse("no 4807.038-style raw lat", s.contains("4807.038"))
    }
}
