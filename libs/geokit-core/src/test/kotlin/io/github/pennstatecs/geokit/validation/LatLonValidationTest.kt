package io.github.pennstatecs.geokit.validation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatLonValidationTest {

    @Test fun `isValidLatLon gate`() {
        assertTrue(LatLonValidation.isValidLatLon(41.0, -75.0))
        assertFalse(LatLonValidation.isValidLatLon(0.0, 0.0))
        assertFalse(LatLonValidation.isValidLatLon(Double.NaN, 1.0))
        assertFalse(LatLonValidation.isValidLatLon(1.0, Double.POSITIVE_INFINITY))
        assertFalse(LatLonValidation.isValidLatLon(91.0, 0.0))
        assertFalse(LatLonValidation.isValidLatLon(0.0, 181.0))
    }

    @Test fun `bounds are inclusive and only the 0,0 point is null island`() {
        assertTrue(LatLonValidation.isValidLatLon(90.0, 180.0))
        assertTrue(LatLonValidation.isValidLatLon(-90.0, -180.0))
        assertTrue("on the equator, away from 0,0", LatLonValidation.isValidLatLon(0.0, 10.0))
        assertTrue("on the prime meridian, away from 0,0", LatLonValidation.isValidLatLon(51.5, 0.0))
        assertFalse(LatLonValidation.isValidLatLon(5e-8, -5e-8))
        assertTrue(LatLonValidation.isValidLatLon(2e-7, 0.0))
    }
}
