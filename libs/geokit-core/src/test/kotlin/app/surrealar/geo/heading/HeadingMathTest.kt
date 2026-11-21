package app.surrealar.geo.heading

import app.surrealar.geo.heading.HeadingMath.AXIS_MINUS_X
import app.surrealar.geo.heading.HeadingMath.AXIS_MINUS_Y
import app.surrealar.geo.heading.HeadingMath.AXIS_MINUS_Z
import app.surrealar.geo.heading.HeadingMath.AXIS_X
import app.surrealar.geo.heading.HeadingMath.AXIS_Y
import app.surrealar.geo.heading.HeadingMath.AXIS_Z
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingMathTest {

    @Test fun `declination is applied - west declination turns magnetic into a smaller true heading`() {
        assertEquals(78.4, HeadingMath.trueHeading(90.0, -11.6), 1e-9)
        assertEquals(348.4, HeadingMath.trueHeading(0.0, -11.6), 1e-9)     // wraps below 0
        assertEquals(5.0, HeadingMath.trueHeading(355.0, 10.0), 1e-9)      // wraps above 360
    }

    @Test fun `normalisation to 0-360`() {
        assertEquals(270.0, HeadingMath.normalize360(-90.0), 1e-9)
        assertEquals(0.0, HeadingMath.normalize360(720.0), 1e-9)
        assertEquals(0.0, HeadingMath.normalize360(Double.NaN), 0.0)
    }

    @Test fun `remap axes - flat for every display rotation`() {
        assertEquals(AXIS_X to AXIS_Y, HeadingMath.remapAxes(HeadingMath.ROTATION_0, upright = false))
        assertEquals(AXIS_Y to AXIS_MINUS_X, HeadingMath.remapAxes(HeadingMath.ROTATION_90, upright = false))
        assertEquals(AXIS_MINUS_X to AXIS_MINUS_Y, HeadingMath.remapAxes(HeadingMath.ROTATION_180, upright = false))
        assertEquals(AXIS_MINUS_Y to AXIS_X, HeadingMath.remapAxes(HeadingMath.ROTATION_270, upright = false))
    }

    @Test fun `remap axes - upright uses the back-camera direction`() {
        assertEquals(AXIS_X to AXIS_Z, HeadingMath.remapAxes(HeadingMath.ROTATION_0, upright = true))
        assertEquals(AXIS_Z to AXIS_MINUS_X, HeadingMath.remapAxes(HeadingMath.ROTATION_90, upright = true))
        assertEquals(AXIS_MINUS_X to AXIS_MINUS_Z, HeadingMath.remapAxes(HeadingMath.ROTATION_180, upright = true))
        assertEquals(AXIS_MINUS_Z to AXIS_X, HeadingMath.remapAxes(HeadingMath.ROTATION_270, upright = true))
    }

    @Test fun `upright detection with hysteresis`() {
        assertFalse(HeadingMath.isUpright(1.0, wasUpright = true))                       // flat
        assertTrue(HeadingMath.isUpright(0.05, wasUpright = false))                      // vertical
        val z45 = Math.cos(Math.toRadians(45.0))
        assertTrue("in the band: keep upright", HeadingMath.isUpright(z45, wasUpright = true))
        assertFalse("in the band: keep flat", HeadingMath.isUpright(z45, wasUpright = false))
        assertTrue("face-down tilt counts the same", HeadingMath.isUpright(-0.05, wasUpright = false))
    }

    @Test fun `declination altitude prefers MSL, then ellipsoidal minus N, then 0`() {
        assertEquals(363.0, HeadingMath.altitudeForDeclination(altMslM = 363.0, altEllipsoidalM = 330.0, geoidSeparationM = -33.0), 0.0)
        assertEquals(363.0, HeadingMath.altitudeForDeclination(altMslM = null, altEllipsoidalM = 330.0, geoidSeparationM = -33.0), 1e-9)
        assertEquals(0.0, HeadingMath.altitudeForDeclination(altMslM = null, altEllipsoidalM = 330.0, geoidSeparationM = null), 0.0)
        assertEquals(0.0, HeadingMath.altitudeForDeclination(null, null, null), 0.0)
    }

    @Test fun `declination is recomputed only after moving 10 km or 5 minutes`() {
        assertTrue(HeadingMath.needsDeclinationUpdate(null, null, null, 40.95, -75.97, 0))
        assertFalse(HeadingMath.needsDeclinationUpdate(40.95, -75.97, 0, 40.96, -75.97, 60_000))   // 1.1 km, 1 min
        assertTrue(HeadingMath.needsDeclinationUpdate(40.95, -75.97, 0, 41.05, -75.97, 60_000))    // 11 km
        assertTrue(HeadingMath.needsDeclinationUpdate(40.95, -75.97, 0, 40.95, -75.97, 6 * 60_000)) // 6 min
    }
}
