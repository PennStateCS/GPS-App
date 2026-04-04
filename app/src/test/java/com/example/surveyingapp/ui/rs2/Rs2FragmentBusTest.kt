package com.example.surveyingapp.ui.rs2

import com.example.surveyingapp.gnss.model.Constellation
import com.example.surveyingapp.gnss.model.SkyGeometry
import com.example.surveyingapp.gnss.model.SkySnapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class Rs2FragmentBusTest {

    @Test
    fun testSkySnapshotCounts() {
        // Geometry: 1 GPS used, 1 GLONASS not used
        val gpsGeometry = SkyGeometry(
            svid = 5,
            constellation = Constellation.GPS,
            azDeg = 123.0,
            elDeg = 45.5,
            snrDbHz = 41.0,
            usedInFix = true
        )
        val glonassGeometry = SkyGeometry(
            svid = 65,
            constellation = Constellation.GLONASS,
            azDeg = 180.0,
            elDeg = 30.0,
            snrDbHz = 35.0,
            usedInFix = false
        )

        val snapshot = SkySnapshot(
            visibleByConstellation = mapOf(
                "GPS" to 1,
                "GLONASS" to 1
            ),
            usedByConstellation = mapOf(
                "GPS" to 1,
                "GLONASS" to 0
            ),
            snrBySvid = mapOf(
                5 to 41.0,
                65 to 35.0
            ),
            geometry = listOf(gpsGeometry, glonassGeometry)
        )

        // Assertions
        assertEquals(2, snapshot.visibleByConstellation.values.sum())
        assertEquals(1, snapshot.usedByConstellation.values.sum())

        // Ensure geometry list integrity
        assertEquals(2, snapshot.geometry.size)
        assertTrue(snapshot.geometry.any { it.constellation == Constellation.GPS && it.usedInFix })
        assertTrue(snapshot.geometry.any { it.constellation == Constellation.GLONASS && !it.usedInFix })
    }
}
