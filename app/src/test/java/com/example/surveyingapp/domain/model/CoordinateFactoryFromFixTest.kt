package com.example.surveyingapp.domain.model

import com.example.surveyingapp.gnss.accumulator.FixSnapshot
import com.example.surveyingapp.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for [CoordinateFactory.fromFix] (FixSnapshot → Coordinate). */
class CoordinateFactoryFromFixTest {

    private fun snap(
        lat: Double? = 48.1173, lon: Double? = 11.5167, altEllipsoidal: Double? = 592.3,
        rtkStatus: String? = "RTK_FIXED", stationId: String? = "0123"
    ) = FixSnapshot(
        timestampMillis = 1_700_000_000_000L, timestampSource = TimestampSource.NMEA_ZDA,
        lat = lat, lon = lon, altMsl = 545.4, geoidSeparation = 46.9, altEllipsoidal = altEllipsoidal,
        speedMps = 0.5, courseDeg = 84.4, satsUsed = 8, hdop = 1.3, vDop = 1.7, pDop = 2.1,
        satellitesInView = 14, horizontalAccuracyM = 0.02, verticalAccuracyM = 0.03,
        correctionAgeS = 1.0, correctionStationId = stationId, multipathIndex = 0.1,
        rtkStatus = rtkStatus, stdLatM = 0.01, stdLonM = 0.012, stdAltM = 0.02
    )

    @Test
    fun mapsAllSurveyFields() {
        val c = CoordinateFactory.fromFix(
            id = "c1", name = "Pt", fix = snap(), icon = "ic_pin", color = 0, provider = "rs2-tcp",
            sourceDevice = "RS2+", appVersion = "1.0"
        )
        assertEquals("c1", c.id)
        assertEquals("rs2-tcp", c.provider)
        assertEquals(48.1173, c.latitude, 1e-9)
        assertEquals(11.5167, c.longitude, 1e-9)
        assertEquals(592.3, c.altitude, 1e-9)
        assertEquals(545.4, c.altitudeMsl!!, 1e-9)
        assertEquals(46.9, c.geoidSeparationM!!, 1e-9)
        assertEquals("RTK_FIXED", c.rtkStatus)
        assertEquals(8, c.satsUsed)
        assertEquals(14, c.satsVisible)
        assertEquals(1.3, c.hdop!!, 1e-9)
        assertEquals(1.7, c.vDop!!, 1e-9)
        assertEquals(2.1, c.pDop!!, 1e-9)
        assertEquals(0.02, c.horizontalAccuracyM!!, 1e-9)
        assertEquals(0.03, c.verticalAccuracyM!!, 1e-9)
        assertEquals(1.0, c.correctionAgeS!!, 1e-9)
        assertEquals("0123", c.correctionStationId)
        assertEquals("Base Station 0123", c.correctionSource)   // derived from a 4-char station id
        assertEquals(0.5, c.speedMps!!, 1e-9)
        assertEquals(84.4, c.courseDeg!!, 1e-9)
        assertEquals(0.01, c.stdLatM!!, 1e-9)
        assertEquals(0.012, c.stdLonM!!, 1e-9)
        assertEquals(0.02, c.stdAltM!!, 1e-9)
        assertEquals(1_700_000_000_000L, c.timestamp)
        assertEquals("NMEA_ZDA", c.timestampSource)
        assertEquals(4326, c.crsEpsg)
    }

    @Test
    fun computesUtmForValidPosition() {
        val c = CoordinateFactory.fromFix("c1", "Pt", snap(), "ic_pin", 0)
        assertNotNull(c.easting)
        assertNotNull(c.northing)
        assertNotNull(c.utmZone)
    }

    @Test
    fun nullLatLon_producesNaN_andNoUtm() {
        val c = CoordinateFactory.fromFix("c1", "Pt", snap(lat = null, lon = null), "ic_pin", 0)
        assertTrue(c.latitude.isNaN())
        assertTrue(c.longitude.isNaN())
        assertNull(c.easting)
        assertNull(c.northing)
        assertNull(c.utmZone)
    }

    @Test
    fun nullEllipsoidalAltitude_isNaN() {
        val c = CoordinateFactory.fromFix("c1", "Pt", snap(altEllipsoidal = null), "ic_pin", 0)
        assertTrue(c.altitude.isNaN())
    }
}
