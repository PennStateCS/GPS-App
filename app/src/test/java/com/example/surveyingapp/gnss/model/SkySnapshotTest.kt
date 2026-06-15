package com.example.surveyingapp.gnss.model

import org.junit.Test
import org.junit.Assert.*

class SkySnapshotTest {

    @Test
    fun testSkySnapshotWithSingleSatellite() {
        val satellite = SatInfo(
            constellation = Constellation.GPS,
            svid = 5,
            elevationDeg = 45.5,
            azimuthDeg = 123.0,
            cn0DbHz = 41.0,
            usedInFix = true
        )

        val skySnapshot = SkySnapshot(satellites = listOf(satellite))

        assertTrue("geometry should not be empty", skySnapshot.satellites.isNotEmpty())
        assertEquals("GPS visible count should be 1", 1, skySnapshot.visibleByConstellation[Constellation.GPS] ?: -1)
        assertEquals("GPS used count should be 1", 1, skySnapshot.usedByConstellation[Constellation.GPS] ?: -1)
    }
}
