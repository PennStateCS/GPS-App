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

    /** Dr. Bolkas's form, r68 = 1.071 × sqrt(σlat² + σlon²), with the exact factor 1.515/√2. */
    private fun bolkasR68(sigmaLat: Double, sigmaLon: Double) =
        HorizontalAccuracy.R68_PER_HORIZONTAL_RMS * sqrt(sigmaLat * sigmaLat + sigmaLon * sigmaLon)

    @Test fun `review sample 68 percent radius is 0_0107 in both forms`() {
        assertEquals(0.0107, HorizontalAccuracy.r68(0.008, 0.006), 0.00005)
        assertEquals(0.0107, bolkasR68(0.008, 0.006), 0.00005)
        // With the review's rounded 1.071 the result agrees to well under a tenth of a millimetre.
        assertEquals(HorizontalAccuracy.r68(0.008, 0.006), 1.071 * sqrt(0.008 * 0.008 + 0.006 * 0.006), 1e-5)
    }

    @Test fun `RMS form and 1_071 times radial form are the same statistic`() {
        val cases = listOf(0.008 to 0.006, 0.010 to 0.010, 0.002 to 0.030, 0.0 to 0.015, 1.5 to 0.4, 0.0 to 0.0)
        for ((lat, lon) in cases) {
            assertEquals("σ=($lat,$lon)", HorizontalAccuracy.r68(lat, lon), bolkasR68(lat, lon), 1e-12)
            // Argument order does not matter (σlat/σlon or ellipse semi-axes).
            assertEquals(HorizontalAccuracy.r68(lat, lon), HorizontalAccuracy.r68(lon, lat), 0.0)
        }
    }

    @Test fun `DOP fallback quotes the same 68 percent statistic as GST`() {
        // A circular GST error with σ = 0.01 m per axis has radial RMS sqrt(2)·0.01; feeding that radial
        // value to the fallback path must give the GST path's radius.
        val sigma = 0.01
        assertEquals(HorizontalAccuracy.r68(sigma, sigma),
            HorizontalAccuracy.r68FromHorizontalRms(sqrt(2.0) * sigma), 1e-12)
    }
}
