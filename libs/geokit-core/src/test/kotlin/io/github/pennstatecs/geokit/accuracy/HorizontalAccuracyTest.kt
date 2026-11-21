package io.github.pennstatecs.geokit.accuracy

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

class HorizontalAccuracyTest {

    @Test fun `factor is the 68 percent radius of a circular 2-D normal`() {
        assertEquals(sqrt(-2.0 * ln(1.0 - 0.6827)), HorizontalAccuracy.R68_PER_SIGMA, 0.001)
    }

    @Test fun `Emlid GST 0_010 by 0_010 gives 0_0152`() {
        assertEquals(0.0152, HorizontalAccuracy.r68(0.010, 0.010), 0.0001)
    }

    @Test fun `elongated ellipse uses the RMS of the axes`() {
        assertEquals(1.515 * sqrt((0.03 * 0.03 + 0.01 * 0.01) / 2.0), HorizontalAccuracy.r68(0.03, 0.01), 1e-12)
    }

    @Test fun `horizontal RMS (HDOP x UERE) needs only the RMS to 68 percent step`() {
        assertEquals(1.515 / sqrt(2.0), HorizontalAccuracy.R68_PER_HORIZONTAL_RMS, 1e-12)
        assertEquals(1.071, HorizontalAccuracy.R68_PER_HORIZONTAL_RMS, 0.001)
        assertEquals(2.68, HorizontalAccuracy.r68FromHorizontalRms(1.0 * 2.5), 0.005)   // Emlid first fix
        assertEquals(5.14, HorizontalAccuracy.r68FromHorizontalRms(1.2 * 4.0), 0.005)   // SM-X940
    }
}
