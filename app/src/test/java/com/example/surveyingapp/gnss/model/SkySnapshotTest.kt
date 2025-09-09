package com.example.surveyingapp.gnss.model

import org.junit.Test
import org.junit.Assert.*

class SkySnapshotTest {

    @Test
    fun testSkySnapshotWithSingleGeometryItem() {
        // Create visible/used maps
        val visibleByConstellation = mapOf("GPS" to 1)
        val usedByConstellation = mapOf("GPS" to 1)
        val snrBySvid = mapOf(5 to 41.0)

        // Create a single SkyGeometry item
        val geometry = listOf(
            SkyGeometry(
                svid = 5,
                constellation = Constellation.GPS,
                azDeg = 123.0,
                elDeg = 45.5,
                snrDbHz = 41.0,
                usedInFix = true
            )
        )

        // Construct SkySnapshot
        val skySnapshot = SkySnapshot(
            visibleByConstellation = visibleByConstellation,
            usedByConstellation = usedByConstellation,
            snrBySvid = snrBySvid,
            geometry = geometry
        )

        // Assert conditions
        assertTrue("geometry should not be empty", skySnapshot.geometry.isNotEmpty())
        assertEquals("GPS visible count should be 1", 1, skySnapshot.visibleByConstellation["GPS"])
        assertEquals("GPS used count should be 1", 1, skySnapshot.usedByConstellation["GPS"])
    }
}
