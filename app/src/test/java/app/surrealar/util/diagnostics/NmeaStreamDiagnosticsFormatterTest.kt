package app.surrealar.util.diagnostics

import app.surrealar.gnss.bus.adapters.NmeaFuser
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.TimestampSource
import app.surrealar.gnss.nmea.sentence.ETC
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaStreamDiagnosticsFormatterTest {

    private fun timing(
        gga: Int = 0, rmc: Int = 0, zda: Int = 0, gst: Int = 0, fixes: Int = 0,
        src: TimestampSource? = null, rtk: RtkStatus? = null, sats: Int? = null, satsVis: Int? = null,
        alt: Double? = null, geoid: Double? = null, hacc: Double? = null,
        accSrc: NmeaFuser.AccuracySource = NmeaFuser.AccuracySource.UNKNOWN,
        gsa: Map<String, Int> = emptyMap(), gsv: Map<String, Int> = emptyMap(),
    ) = NmeaFuser.FuserTimingStats(gga, rmc, zda, gst, fixes, src, rtk, sats, satsVis, alt, geoid, hacc, accSrc, gsa, gsv)

    private fun custom(ebp: Int = 0, etc: Int = 0, latestEtc: ETC? = null, imu: Boolean = false) =
        NmeaFuser.NmeaCustomStats(ebp > 0, etc > 0, ebp, etc, latestEtc, imu)

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
    fun `last fix summary shows parsed accuracy, source, sats visible, and per-constellation counts`() {
        val withAcc = fmt(
            timing(
                gga = 5, fixes = 5, rtk = RtkStatus.FIX, sats = 22, satsVis = 30,
                alt = 395.539, geoid = -33.145, hacc = 0.010,
                accSrc = NmeaFuser.AccuracySource.RECEIVER_GST,
                gsa = mapOf("GP" to 5, "GL" to 5), gsv = mapOf("GP" to 10, "GA" to 10),
            ), custom(),
        )
        assertTrue(withAcc.contains("RTK status            : FIX"))
        assertTrue(withAcc.contains("Satellites used       : 22"))
        assertTrue(withAcc.contains("Satellites visible    : 30"))
        assertTrue(withAcc.contains("Horizontal accuracy   : ±0.010 m"))
        assertTrue(withAcc.contains("Accuracy source       : receiver_gst"))
        assertTrue(withAcc.contains("GSA by constellation  : GP=5 GL=5"))
        assertTrue(withAcc.contains("GSV by constellation  : GA=10 GP=10") || withAcc.contains("GSV by constellation  : GP=10 GA=10"))

        // Missing accuracy renders as "unknown" with an unknown source, never 0.0.
        val noAcc = fmt(timing(gga = 5, fixes = 5, rtk = RtkStatus.FIX), custom())
        assertTrue(noAcc.contains("Horizontal accuracy   : unknown"))
        assertTrue(noAcc.contains("Accuracy source       : unknown"))
        assertFalse(noAcc.contains("±0.000 m"))
    }

    @Test
    fun `EBP and ETC present show seen counts and raw orientation (no invented heading)`() {
        val etc = ETC(talker = "GN", timeRaw = "120000", dataFields = listOf("30", "00", "268.660", "116.146"))
        val s = fmt(timing(gga = 50, fixes = 50), custom(ebp = 12, etc = 12, latestEtc = etc, imu = true))
        assertTrue(s.contains("EBP seen              : yes (12)"))
        assertTrue(s.contains("ETC seen              : yes (12)"))
        assertTrue(s.contains("IMU/orientation seen  : yes"))
        assertTrue(s.contains("orientation/IMU present"))
        assertTrue("explicit not-mapped note", s.contains("NOT mapped to AR heading"))
        // We must NOT claim a heading — the layout is undocumented.
        assertFalse("no invented heading label", s.contains("heading="))
    }

    @Test
    fun `output contains no raw NMEA and no coordinate values`() {
        val etc = ETC("GN", "120000", listOf("30", "00"))
        val s = fmt(timing(gga = 50, rmc = 10, zda = 10, fixes = 50, src = TimestampSource.NMEA_ZDA),
            custom(ebp = 5, etc = 5, latestEtc = etc))
        assertFalse("no raw NMEA sentence prefix", s.contains("\$G"))
        assertFalse("no latitude label", s.contains("lat="))
        assertFalse("no longitude label", s.contains("lon="))
        assertFalse("no 4807.038-style raw lat", s.contains("4807.038"))
    }
}
