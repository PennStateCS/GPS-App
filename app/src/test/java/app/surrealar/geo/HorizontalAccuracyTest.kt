package app.surrealar.geo

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
        assertEquals(1.515, HorizontalAccuracy.r68(1.0), 1e-12)
    }
}
