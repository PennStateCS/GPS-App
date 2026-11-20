package app.surrealar.ui.openinar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos

class ArVsGnssTest {

    private val lat = 41.3477
    private val lon = -76.0226
    private val mPerDegLon = 111_132.0 * cos(Math.toRadians(lat))

    @Test fun `ARCore 0_4 m east of the fix reads 0_4 m at 90 degrees`() {
        val c = ArVsGnss.compute(lat, lon + 0.4 / mPerDegLon, 390.0, lat, lon, 390.6, antennaAboveCameraM = 0.6,
            arHAccM = 0.8, arYawAccDeg = 3.0, farthestModelM = 20.0, speedMps = 0.0)!!
        assertEquals(0.4, c.horizontalM, 1e-3)
        assertEquals(90.0, c.bearingDeg, 0.1)
        assertEquals(0.0, c.verticalM!!, 1e-9)          // 390.6 antenna − 0.6 offset = 390.0 camera
        assertEquals("GOOD", c.grade)
        assertEquals(0.8 + 20.0 * kotlin.math.tan(Math.toRadians(3.0)), c.expectedErrAtFarthestM!!, 1e-9)
    }

    @Test fun `grades by horizontal offset`() {
        fun grade(m: Double) = ArVsGnss.compute(lat + m / 111_132.0, lon, null, lat, lon, null, 0.0,
            null, null, null, null)!!.grade
        assertEquals("GOOD", grade(0.4))
        assertEquals("FAIR", grade(1.5))
        assertEquals("POOR", grade(3.0))
    }

    @Test fun `missing heights or accuracies leave those fields empty`() {
        val c = ArVsGnss.compute(lat, lon, null, lat, lon, 390.0, 0.0, Double.NaN, 5.0, 20.0, null)!!
        assertNull(c.verticalM)
        assertNull(c.arHAccM)
        assertNull(c.expectedErrAtFarthestM)
    }

    @Test fun `non-finite positions give no comparison`() {
        assertNull(ArVsGnss.compute(Double.NaN, lon, null, lat, lon, null, 0.0, null, null, null, null))
    }
}
