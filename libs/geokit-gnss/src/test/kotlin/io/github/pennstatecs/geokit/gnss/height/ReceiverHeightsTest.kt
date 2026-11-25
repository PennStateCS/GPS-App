package io.github.pennstatecs.geokit.gnss.height

import io.github.pennstatecs.geokit.gnss.accuracy.VerticalAccuracyStatus
import io.github.pennstatecs.geokit.gnss.accuracy.VerticalAccuracyValidity
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Receiver heights (docs/geodesy/rs4-nmea-heights.md). Lines are SYNTHETIC unless the test name says
 * CAPTURED (the existing Emlid capture fixtures). The fuser is unchanged: h = H + N from the same GGA.
 */
class ReceiverHeightsTest {

    private fun cs(body: String): String = "\$" + body + "*" + "%02X".format(body.fold(0) { a, c -> a xor c.code } and 0xFF)

    private fun gga(time: String, alt: String, sep: String) =
        cs("GNGGA,$time,4118.4448800,N,07601.0300517,W,4,22,1.0,$alt,M,$sep,M,1.0,1116")

    private fun gst(time: String, altSigma: String) = cs("GNGST,$time,1.000,,,,0.010,0.010,$altSigma")

    private fun fuse(lines: List<String>): List<Fix> {
        val fixes = mutableListOf<Fix>()
        val fuser = NmeaFuser(Provider.EXTERNAL_TCP, DefaultNmeaRegistry.create(verifyChecksum = true), { fixes += it }, { }, clock = { 1_000L })   // constant non-zero clock: GST hold active, never times out
        lines.forEach { fuser.accept(it) }
        fuser.flush()
        return fixes
    }

    @Test
    fun `CAPTURED normal GGA altitude plus separation reconstructs h once`() {
        // Real line from reach_nmea_capture.txt (RS4, before firmware 34.10).
        val f = fuse(listOf("\$GNGGA,172424.80,4118.4448800,N,07601.0300517,W,4,22,1.0,395.539,M,-33.145,M,1.8,1116*6F")).single()
        val a = ReceiverHeights.assess(f)
        assertEquals(EllipsoidalHeightStatus.RECONSTRUCTED, a.ellipsoidalStatus)
        assertEquals(395.539, a.receiverOrthometricM!!, 0.0)
        assertEquals(-33.145, a.receiverGeoidSeparationM!!, 0.0)
        assertEquals(395.539 + -33.145, a.ellipsoidalM!!, 1e-12)
        assertNull("NMEA never identifies the receiver geoid model", a.receiverGeoidModel)
        assertTrue(a.ellipsoidalTrusted)
    }

    @Test
    fun `CAPTURED every fixture fix reconstructs h with an unknown model`() {
        for (name in listOf("reach_nmea_capture", "reach_nmea_rs2_capture", "reach_nmea_withimu")) {
            val lines = javaClass.getResourceAsStream("/nmea/$name.txt")!!.bufferedReader().readLines()
            val fixes = fuse(lines)
            assertTrue(name, fixes.isNotEmpty())
            for (f in fixes) {
                val a = ReceiverHeights.assess(f)
                assertEquals(name, EllipsoidalHeightStatus.RECONSTRUCTED, a.ellipsoidalStatus)
                assertNull(a.receiverGeoidModel)
                // No double vertical correction: h − H is exactly the reported N.
                assertEquals(name, f.geoidSeparationM!!, f.altEllipsoidalM!! - f.altMslM!!, 1e-9)
            }
        }
    }

    @Test
    fun `SYNTHETIC orthometric streaming disabled vs enabled with a consistent separation gives the same h`() {
        // Assumption (not documented, needs a receiver test): with 34.10 orthometric streaming on, field 9 is
        // H of the selected geoid and field 11 its N, so H + N is unchanged.
        val off = fuse(listOf(gga("120000.00", "395.539", "-33.145"))).single()
        val on = fuse(listOf(gga("120000.00", "395.312", "-32.918"))).single()
        assertEquals(off.altEllipsoidalM!!, on.altEllipsoidalM!!, 1e-9)
        assertEquals(EllipsoidalHeightStatus.RECONSTRUCTED, ReceiverHeights.assess(on).ellipsoidalStatus)
        assertNull(ReceiverHeights.assess(on).receiverGeoidModel)
    }

    @Test
    fun `SYNTHETIC orthometric streaming with separation 0 is ambiguous, not trusted`() {
        val f = fuse(listOf(gga("120000.00", "395.539", "0.000"))).single()
        val a = ReceiverHeights.assess(f)
        assertEquals(EllipsoidalHeightStatus.SEPARATION_ZERO_AMBIGUOUS, a.ellipsoidalStatus)
        assertFalse(a.ellipsoidalTrusted)
        // The fuser is unchanged (h = H + 0); only the assessment marks it uncertain.
        assertEquals(395.539, f.altEllipsoidalM!!, 0.0)
    }

    @Test
    fun `SYNTHETIC missing separation leaves h unavailable`() {
        val f = fuse(listOf(gga("120000.00", "395.539", ""))).single()
        val a = ReceiverHeights.assess(f)
        assertEquals(EllipsoidalHeightStatus.NO_SEPARATION, a.ellipsoidalStatus)
        assertNull(a.ellipsoidalM)
        assertEquals(395.539, a.receiverOrthometricM!!, 0.0)
        assertFalse(a.ellipsoidalTrusted)
    }

    @Test
    fun `SYNTHETIC missing altitude`() {
        val f = fuse(listOf(gga("120000.00", "", "-33.145"))).single()
        assertEquals(EllipsoidalHeightStatus.NO_ALTITUDE, ReceiverHeights.assess(f).ellipsoidalStatus)
    }

    @Test
    fun `SYNTHETIC 999 m altitude accuracy is invalid and recovers when corrections resume`() {
        // 5 Hz GGA, 1 Hz GST: GST altitude sigma 0.016 → 999 (receiver-side geoid corrections interrupted) → 0.018.
        val lines = listOf(
            gga("120000.00", "395.539", "-33.145"), gst("120000.00", "0.016"),
            gga("120000.20", "395.540", "-33.145"),
            gga("120001.00", "395.541", "-33.145"), gst("120001.00", "999.000"),
            gga("120001.20", "395.542", "-33.145"),
            gga("120002.00", "395.543", "-33.145"), gst("120002.00", "0.018"),
            gga("120002.20", "395.544", "-33.145"),
        )
        val fixes = fuse(lines)
        assertEquals(6, fixes.size)
        val st = fixes.map { ReceiverHeights.assess(it).ellipsoidalStatus }
        assertEquals(
            listOf(
                EllipsoidalHeightStatus.RECONSTRUCTED, EllipsoidalHeightStatus.RECONSTRUCTED,
                EllipsoidalHeightStatus.VERTICAL_ACCURACY_INVALID, EllipsoidalHeightStatus.VERTICAL_ACCURACY_INVALID,
                EllipsoidalHeightStatus.RECONSTRUCTED, EllipsoidalHeightStatus.RECONSTRUCTED,
            ), st,
        )
        assertTrue(VerticalAccuracyValidity.isInvalid(fixes[2]))
        assertFalse(VerticalAccuracyValidity.isInvalid(fixes[4]))
    }

    @Test
    fun `vertical accuracy classification`() {
        assertEquals(VerticalAccuracyStatus.NOT_REPORTED, VerticalAccuracyValidity.classify(null as Double?))
        assertEquals(VerticalAccuracyStatus.VALID, VerticalAccuracyValidity.classify(0.012))
        assertEquals(VerticalAccuracyStatus.VALID, VerticalAccuracyValidity.classify(45.0))
        assertEquals(VerticalAccuracyStatus.INVALID, VerticalAccuracyValidity.classify(999.0))
        assertEquals(VerticalAccuracyStatus.INVALID, VerticalAccuracyValidity.classify(500.0))
        assertEquals(VerticalAccuracyStatus.INVALID, VerticalAccuracyValidity.classify(9999.0))
        assertEquals(VerticalAccuracyStatus.INVALID, VerticalAccuracyValidity.classify(Double.NaN))
        assertEquals(VerticalAccuracyStatus.INVALID, VerticalAccuracyValidity.classify(Double.POSITIVE_INFINITY))
        assertEquals(VerticalAccuracyStatus.INVALID, VerticalAccuracyValidity.classify(0.0))
        assertEquals(VerticalAccuracyStatus.INVALID, VerticalAccuracyValidity.classify(-1.0))
    }
}
