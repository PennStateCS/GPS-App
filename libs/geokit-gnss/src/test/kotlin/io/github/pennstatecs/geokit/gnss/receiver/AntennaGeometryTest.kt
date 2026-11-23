package io.github.pennstatecs.geokit.gnss.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class AntennaGeometryTest {

    @Test fun `phase-centre offset is added to both heights`() {
        val g = AntennaGeometry(poleHeightM = 2.0, cameraBelowArpM = 0.4, phaseCenterOffsetM = 0.134)
        assertEquals(2.134, g.captureOffsetM, 1e-12)
        assertEquals(0.534, g.antennaAboveCameraM, 1e-12)
    }

    @Test fun `zero offset leaves the measured heights`() {
        val g = AntennaGeometry(poleHeightM = 1.8, cameraBelowArpM = 0.6, phaseCenterOffsetM = 0.0)
        assertEquals(1.8, g.captureOffsetM, 0.0)
        assertEquals(0.6, g.antennaAboveCameraM, 0.0)
    }

    @Test fun `camera above the ARP gives a smaller antenna height`() {
        val g = AntennaGeometry(poleHeightM = 2.0, cameraBelowArpM = -0.05, phaseCenterOffsetM = 0.134)
        assertEquals(0.084, g.antennaAboveCameraM, 1e-12)
    }

    @Test fun `same arithmetic as pole plus offset, bit for bit`() {
        val g = AntennaGeometry(poleHeightM = 1.8, cameraBelowArpM = 0.466, phaseCenterOffsetM = 0.134)
        assertEquals(1.8 + 0.134, g.captureOffsetM, 0.0)
        assertEquals(0.466 + 0.134, g.antennaAboveCameraM, 0.0)
    }
}
