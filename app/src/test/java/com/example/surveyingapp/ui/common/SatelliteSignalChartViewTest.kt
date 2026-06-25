package com.example.surveyingapp.ui.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.surveyingapp.gnss.model.Constellation
import com.example.surveyingapp.gnss.model.SkyGeometry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SatelliteSignalChartViewTest {

    @Test
    fun testConstellationFilterAndGeometryDataset() {
        // Create the view in a test context
        val context = ApplicationProvider.getApplicationContext<Context>()
        val chartView = SatelliteSignalChartView(context)

        // Set constellation filter to GPS
        chartView.setConstellationFilter(Constellation.GPS)

        // Create 3 satellites: two GPS (snr 40, 30; used true/false) and one GLONASS (snr 35)
        val satellites = listOf(
            SkyGeometry(
                svid = 1,
                constellation = Constellation.GPS,
                azDeg = 45.0,
                elDeg = 30.0,
                snrDbHz = 40.0,
                usedInFix = true
            ),
            SkyGeometry(
                svid = 2,
                constellation = Constellation.GPS,
                azDeg = 90.0,
                elDeg = 45.0,
                snrDbHz = 30.0,
                usedInFix = false
            ),
            SkyGeometry(
                svid = 65,
                constellation = Constellation.GLONASS,
                azDeg = 180.0,
                elDeg = 60.0,
                snrDbHz = 35.0,
                usedInFix = true
            )
        )

        // Call setGeometry with the satellites
        chartView.setGeometry(satellites)

        // Get the internal dataset using the @VisibleForTesting getter
        val dataset = chartView.getDataset()
        val usedPrns = chartView.getUsedSatellitePrns()

        // Assert the internal dataset has 2 bars after filter (only GPS satellites)
        assertEquals("Dataset should have 2 GPS satellites after filter", 2, dataset.size)

        // Verify the dataset contains only GPS satellites
        assertTrue("All satellites in dataset should be GPS",
                  dataset.all { it.constellation == Constellation.GPS })

        // Assert the first bar is marked 'used' when usedInFix=true
        val firstSatellite = dataset.find { it.svid == 1 }
        assertNotNull("First satellite (svid=1) should be in dataset", firstSatellite)
        assertTrue("First satellite should be marked as used", usedPrns.contains(1))

        // Verify the second satellite is not marked as used
        assertFalse("Second satellite should not be marked as used", usedPrns.contains(2))

        // Verify GLONASS satellite is not in the filtered dataset
        assertFalse("GLONASS satellite should not be in GPS-filtered dataset",
                   dataset.any { it.svid == 65 })
    }
}
