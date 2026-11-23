package app.surrealar.ui.openinar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArTrackingVisibilityTest {

    @Test
    fun modelsDrawnOnlyWhileCameraAndEarthTrack() {
        assertTrue(ArTrackingVisibility.drawGeospatialModels(cameraTracking = true, earthTracking = true))
        assertFalse(ArTrackingVisibility.drawGeospatialModels(cameraTracking = false, earthTracking = true))
        assertFalse(ArTrackingVisibility.drawGeospatialModels(cameraTracking = true, earthTracking = false))
        assertFalse(ArTrackingVisibility.drawGeospatialModels(cameraTracking = false, earthTracking = false))
    }
}
