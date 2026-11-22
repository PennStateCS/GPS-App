package app.surrealar.ui.openinar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ArLightingTest {

    private fun sh(dc: Float) = FloatArray(27).also { it[0] = dc; it[1] = dc; it[2] = dc }

    private fun estimate(
        dir: FloatArray? = floatArrayOf(0f, 1f, 0f),
        rgb: FloatArray? = floatArrayOf(1f, 1f, 1f),
        ambient: FloatArray? = sh(2f),
        valid: Boolean = true,
    ) = ArLighting.Estimate(valid, dir, rgb, ambient)

    @Test fun `fallback always has ambient light so no face is black`() {
        val f = ArLighting.fallback()
        assertFalse(f.fromEstimate)
        assertTrue(f.ambientIntensity > 0f)
        for (c in 0 until 3) assertTrue(f.ambientSh[c] >= ArLighting.MIN_AMBIENT_DC)
        val d = f.sunDirection
        assertEquals(1f, sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-5f)
        assertTrue("sun shines downward", d[1] < 0f)
    }

    @Test fun `invalid or incomplete estimates keep the current lighting`() {
        assertNull(ArLighting.target(null))
        assertNull(ArLighting.target(estimate(valid = false)))
        assertNull(ArLighting.target(estimate(dir = null)))
        assertNull(ArLighting.target(estimate(ambient = FloatArray(9))))
        assertNull(ArLighting.target(estimate(rgb = floatArrayOf(Float.NaN, 1f, 1f))))
        assertNull(ArLighting.target(estimate(dir = floatArrayOf(0f, 0f, 0f))))
        val prev = ArLighting.fallback()
        assertSame(prev, ArLighting.next(estimate(valid = false), prev))
        assertFalse(ArLighting.next(null, null).fromEstimate)
    }

    @Test fun `ARCore direction toward the light becomes Filament's travel direction`() {
        val t = ArLighting.target(estimate(dir = floatArrayOf(0f, 2f, 0f)))!!
        assertArrayEquals(floatArrayOf(0f, -1f, 0f), t.sunDirection, 1e-6f)
        assertTrue(t.fromEstimate)
    }

    @Test fun `sun colour is normalised and intensity clamped`() {
        val warm = ArLighting.target(estimate(rgb = floatArrayOf(1.5f, 0.75f, 0.375f)))!!
        assertArrayEquals(floatArrayOf(1f, 0.5f, 0.25f), warm.sunColor, 1e-6f)
        assertEquals(1.5f * ArLighting.SUN_LUX_PER_UNIT, warm.sunLux, 1f)
        assertEquals(ArLighting.SUN_LUX_MAX, ArLighting.target(estimate(rgb = floatArrayOf(50f, 50f, 50f)))!!.sunLux, 0f)
        assertEquals(ArLighting.SUN_LUX_MIN, ArLighting.target(estimate(rgb = floatArrayOf(0f, 0f, 0f)))!!.sunLux, 0f)
    }

    @Test fun `dark ambient estimate is floored`() {
        val t = ArLighting.target(estimate(ambient = sh(0f)))!!
        for (c in 0 until 3) assertEquals(ArLighting.MIN_AMBIENT_DC, t.ambientSh[c], 0f)
        // Bright ambient is scaled by the band-0 irradiance factor, not floored.
        val b = ArLighting.toIrradiance(sh(4f))
        assertEquals(4f * 0.282095f, b[0], 1e-5f)
    }

    @Test fun `estimates are smoothed toward, not jumped to`() {
        val first = ArLighting.next(estimate(rgb = floatArrayOf(1f, 1f, 1f)), ArLighting.fallback())
        assertTrue("first estimate replaces the fallback outright", first.fromEstimate)
        val target = ArLighting.target(estimate(rgb = floatArrayOf(1.5f, 1.5f, 1.5f)))!!
        val next = ArLighting.next(estimate(rgb = floatArrayOf(1.5f, 1.5f, 1.5f)), first)
        val expected = first.sunLux + (target.sunLux - first.sunLux) * ArLighting.SMOOTHING
        assertEquals(expected, next.sunLux, 1e-2f)
        assertNotNull(next)
    }

    @Test fun `SH rebuild threshold`() {
        assertFalse(ArLighting.shChangedEnough(sh(1f), sh(1.01f)))
        assertTrue(ArLighting.shChangedEnough(sh(1f), sh(1.1f)))
    }
}
