package io.github.pennstatecs.geokit.gnss.nmea.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Direct tests for [GstParser]. GST supplies the receiver-reported horizontal/vertical accuracy
 * (std-dev lat/lon/alt) that drives capture accuracy, so field order matters.
 */
class GstParserTest {

    private val parser = GstParser()

    private fun fields(payload: String) = payload.split(",")

    @Test
    fun fullSentence_mapsEveryFieldInOrder() {
        val gst = parser.parse("GN", fields("GNGST,172814.00,0.006,0.023,0.020,273.6,0.011,0.012,0.025"))!!
        assertEquals("GN", gst.talker)
        assertEquals("172814.00", gst.timeRaw)
        assertEquals(0.006, gst.rmsStdDev!!, 1e-9)
        assertEquals(0.023, gst.stdDevMajor!!, 1e-9)
        assertEquals(0.020, gst.stdDevMinor!!, 1e-9)
        assertEquals(273.6, gst.orientationDeg!!, 1e-9)
        assertEquals(0.011, gst.stdDevLat!!, 1e-9)
        assertEquals(0.012, gst.stdDevLon!!, 1e-9)
        assertEquals(0.025, gst.stdDevAlt!!, 1e-9)
    }

    @Test
    fun emptyFields_becomeNull_notZero() {
        val gst = parser.parse("GP", fields("GPGST,172814.00,,,,,0.5,0.6,"))!!
        assertNull(gst.rmsStdDev)
        assertNull(gst.stdDevMajor)
        assertNull(gst.orientationDeg)
        assertEquals(0.5, gst.stdDevLat!!, 1e-9)
        assertEquals(0.6, gst.stdDevLon!!, 1e-9)
        assertNull(gst.stdDevAlt)
    }

    @Test
    fun truncatedSentence_isTolerated() {
        val gst = parser.parse("GP", fields("GPGST,172814.00,0.006"))
        assertNotNull(gst)
        assertEquals(0.006, gst!!.rmsStdDev!!, 1e-9)
        assertNull(gst.stdDevLat)
        assertNull(gst.stdDevAlt)
    }

    @Test
    fun garbageNumbers_becomeNull() {
        val gst = parser.parse("GP", fields("GPGST,,abc,1.0,x,,NaNish, 0.2 ,"))!!
        assertNull(gst.timeRaw)
        assertNull(gst.rmsStdDev)
        assertEquals(1.0, gst.stdDevMajor!!, 1e-9)
        assertNull(gst.stdDevMinor)
        assertNull(gst.stdDevLat)
        assertEquals(0.2, gst.stdDevLon!!, 1e-9)   // whitespace is trimmed
    }

    @Test
    fun noFields_returnsNull() = assertNull(parser.parse("GP", emptyList()))
}
