package app.surrealar.ui.settings

import app.surrealar.gnss.settings.GnssReceiverSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AntennaHeightInputTest {

    @Test fun `valid heights parse`() {
        assertEquals(1.8, AntennaHeightInput.parseMeters("1.8")!!, 0.0)
        assertEquals(2.0, AntennaHeightInput.parseMeters(" 2 ")!!, 0.0)
        assertEquals(0.0, AntennaHeightInput.parseMeters("0")!!, 0.0)
        assertEquals(1.75, AntennaHeightInput.parseMeters("1,75")!!, 0.0)   // comma-decimal keyboards
    }

    @Test fun `cleared or junk input is rejected, not saved as 0`() {
        assertNull(AntennaHeightInput.parseMeters(""))
        assertNull(AntennaHeightInput.parseMeters("   "))
        assertNull(AntennaHeightInput.parseMeters("."))
        assertNull(AntennaHeightInput.parseMeters("-"))
        assertNull(AntennaHeightInput.parseMeters(null))
    }

    @Test fun `negative and implausible heights are rejected`() {
        assertNull(AntennaHeightInput.parseMeters("-1.0"))
        assertNull(AntennaHeightInput.parseMeters("1800"))     // typed in mm
        assertNull(AntennaHeightInput.parseMeters("NaN"))
    }

    @Test fun `internal antenna height defaults to 0 for handheld tablets`() {
        assertEquals(0.0, GnssReceiverSettings().internalAntennaHeightM, 0.0)
    }
}
