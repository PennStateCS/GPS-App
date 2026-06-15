package com.example.surveyingapp.gnss.model

import org.junit.Test
import org.junit.Assert.*

class SkyGeometryTest {

    @Test
    fun testSkyGeometryInstantiationPreservesAllFields() {
        // Create a SkyGeometry with a GPS satellite (svid=5, az=123.0, el=45.5, snr=41.0, used=true)
        val skyGeometry = SkyGeometry(
            svid = 5,
            constellation = Constellation.GPS,
            azDeg = 123.0,
            elDeg = 45.5,
            snrDbHz = 41.0,
            usedInFix = true
        )

        // Assert all fields are preserved
        assertEquals(5, skyGeometry.svid)
        assertEquals(Constellation.GPS, skyGeometry.constellation)
        assertEquals(123.0, skyGeometry.azDeg ?: -1.0, 0.001)
        assertEquals(45.5, skyGeometry.elDeg ?: -1.0, 0.001)
        assertEquals(41.0, skyGeometry.snrDbHz ?: -1.0, 0.001)
        assertTrue(skyGeometry.usedInFix)
    }
}
