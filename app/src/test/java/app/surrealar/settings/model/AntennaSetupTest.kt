package app.surrealar.settings.model

import app.surrealar.gnss.settings.GnssReceiverSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaSetupTest {

    private val receiver = GnssReceiverSettings(poleHeightM = 2.0, cameraBelowReceiverM = 0.4)

    @Test fun `profile offset is added to both heights`() {
        val s = AntennaSetup.resolve(receiver, ExternalReceiverProfile.REACH_RS2_PLUS)
        assertEquals(0.134, s.phaseCenterOffsetM, 1e-12)
        assertTrue(s.offsetFromProfile)
        assertEquals(2.134, s.captureOffsetM, 1e-12)
        assertEquals(0.534, s.antennaAboveCameraM, 1e-12)
    }

    @Test fun `override replaces the profile offset`() {
        val s = AntennaSetup.resolve(receiver.copy(phaseCenterOffsetOverrideM = 0.08), ExternalReceiverProfile.REACH_RS2_PLUS)
        assertEquals(0.08, s.phaseCenterOffsetM, 1e-12)
        assertFalse(s.offsetFromProfile)
        assertEquals(2.08, s.captureOffsetM, 1e-12)
    }

    @Test fun `unknown offset counts as zero until entered`() {
        val s = AntennaSetup.resolve(receiver, ExternalReceiverProfile.GENERIC_NMEA_TCP)
        assertEquals(0.0, s.phaseCenterOffsetM, 0.0)
        assertFalse(s.offsetFromProfile)
        assertEquals(2.0, s.captureOffsetM, 1e-12)
    }

    @Test fun `camera above the receiver gives a smaller antenna height`() {
        val s = AntennaSetup.resolve(receiver.copy(cameraBelowReceiverM = -0.05), ExternalReceiverProfile.REACH_RS2_PLUS)
        assertEquals(0.084, s.antennaAboveCameraM, 1e-12)
    }
}
