package app.surrealar.gnss.accumulator

import app.surrealar.gnss.model.TimestampSource
import app.surrealar.gnss.nmea.sentence.GGA
import app.surrealar.gnss.nmea.sentence.GSA
import app.surrealar.gnss.nmea.sentence.GST
import app.surrealar.gnss.nmea.sentence.GSV
import app.surrealar.gnss.nmea.sentence.RMC
import app.surrealar.gnss.nmea.sentence.ZDA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure tests for the NMEA-sentence fusion in [FixAccumulator]. */
class FixAccumulatorTest {

    private fun gga(
        lat: Double? = 48.1173, lon: Double? = 11.5167, fixQuality: Int? = 4,
        satsUsed: Int? = 8, hdop: Double? = 1.3, altMsl: Double? = 545.4,
        geoid: Double? = 46.9, diffAge: Double? = 1.0, stationId: String? = "0123"
    ) = GGA("GP", "120000", lat, lon, fixQuality, satsUsed, hdop, altMsl, geoid, diffAge, stationId)

    @Test
    fun gga_setsPosition_derivesEllipsoidal_andMapsRtkStatus() {
        val acc = FixAccumulator()
        acc.accept(gga())
        val s = acc.state.value
        assertEquals(48.1173, s.lat!!, 1e-9)
        assertEquals(11.5167, s.lon!!, 1e-9)
        assertEquals(545.4, s.altMsl!!, 1e-9)
        assertEquals(46.9, s.geoidSeparation!!, 1e-9)
        assertEquals(545.4 + 46.9, s.altEllipsoidal!!, 1e-9)   // MSL + geoid separation
        assertEquals(8, s.satsUsed)
        assertEquals("RTK_FIXED", s.rtkStatus)                 // fixQuality 4
        assertEquals(1.0, s.correctionAgeS!!, 1e-9)
        assertEquals("0123", s.correctionStationId)
    }

    @Test
    fun gga_withoutGeoid_leavesEllipsoidalNull() {
        val acc = FixAccumulator()
        acc.accept(gga(geoid = null))
        assertNull(acc.state.value.altEllipsoidal)
    }

    @Test
    fun gga_fixQualityMapping() {
        fun rtkFor(q: Int?): String? {
            val acc = FixAccumulator(); acc.accept(gga(fixQuality = q)); return acc.state.value.rtkStatus
        }
        assertEquals("INVALID", rtkFor(0))
        assertEquals("SINGLE", rtkFor(1))
        assertEquals("DGPS", rtkFor(2))
        assertEquals("RTK_FIXED", rtkFor(4))
        assertEquals("RTK_FLOAT", rtkFor(5))
    }

    @Test
    fun rmc_convertsKnotsToMetersPerSecond_andSetsCourse() {
        val acc = FixAccumulator()
        acc.accept(RMC("GP", null, null, null, null, null, 48.0, 11.0, 10.0, 84.4))
        val s = acc.state.value
        assertEquals(10.0 * 0.514444, s.speedMps!!, 1e-6)
        assertEquals(84.4, s.courseDeg!!, 1e-9)
    }

    @Test
    fun gsa_unionsUsedSvids_withinEpoch() {
        val acc = FixAccumulator()
        acc.accept(GSA("GP", 3, listOf(1, 2, 3), 2.0, 1.3, 1.5))
        acc.accept(GSA("GL", 3, listOf(4, 5), null, null, null)) // same epoch → union
        val s = acc.state.value
        assertEquals(5, s.satsUsed)        // {1,2,3,4,5}
        assertEquals(1.3, s.hdop!!, 1e-9)
        assertEquals(1.5, s.vDop!!, 1e-9)
        assertEquals(2.0, s.pDop!!, 1e-9)
    }

    @Test
    fun gsv_setsSatellitesInView() {
        val acc = FixAccumulator()
        acc.accept(GSV("GP", 1, 1, 12, emptyList()))
        assertEquals(12, acc.state.value.satellitesInView)
    }

    @Test
    fun zda_usesReceiverTime_whenPresent_elseDevice() {
        val withEpoch = FixAccumulator()
        withEpoch.accept(ZDA("GP", "120000", 1, 1, 2024, 1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, withEpoch.state.value.timestampMillis)
        assertEquals(TimestampSource.NMEA_ZDA, withEpoch.state.value.timestampSource)

        val noEpoch = FixAccumulator()
        noEpoch.accept(ZDA("GP", null, null, null, null, null))
        assertEquals(TimestampSource.DEVICE, noEpoch.state.value.timestampSource)
    }

    @Test
    fun gst_derivesHorizontalAccuracy_andStdDevs() {
        val acc = FixAccumulator()
        acc.accept(GST("GP", "120000", 0.02, 0.02, 0.01, 0.0, 0.015, 0.012, 0.03))
        val s = acc.state.value
        // DRMS = sqrt((major^2 + minor^2) / 2)
        assertEquals(Math.sqrt((0.02 * 0.02 + 0.01 * 0.01) / 2.0), s.horizontalAccuracyM!!, 1e-9)
        assertEquals(0.03, s.verticalAccuracyM!!, 1e-9)
        assertEquals(0.015, s.stdLatM!!, 1e-9)
        assertEquals(0.012, s.stdLonM!!, 1e-9)
        assertEquals(0.03, s.stdAltM!!, 1e-9)
    }

    @Test
    fun fusion_preservesFieldsAcrossSentences() {
        val acc = FixAccumulator()
        acc.accept(gga())                                   // sets lat/lon
        acc.accept(RMC("GP", null, null, null, null, null, null, null, 5.0, 90.0))
        val s = acc.state.value
        assertEquals(48.1173, s.lat!!, 1e-9)                // GGA position retained after RMC
        assertEquals(5.0 * 0.514444, s.speedMps!!, 1e-6)
    }
}
