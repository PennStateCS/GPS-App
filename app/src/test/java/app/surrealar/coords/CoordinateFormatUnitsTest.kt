package app.surrealar.coords

import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateFormatUnitsTest {

    private val usft = LinearUnit.US_SURVEY_FEET

    @Test fun `metre distances keep the old readout`() {
        assertEquals("0.42 m", CoordinateFormat.distance(0.42, LinearUnit.METERS))
        assertEquals("4.2 m", CoordinateFormat.distance(4.2, LinearUnit.METERS))
        assertEquals("42 m", CoordinateFormat.distance(42.0, LinearUnit.METERS))
        assertEquals("1.2 km", CoordinateFormat.distance(1234.0, LinearUnit.METERS))
    }

    @Test fun `feet distances scale and switch to miles`() {
        assertEquals("1.37 ftUS", CoordinateFormat.distance(0.4178, usft))
        assertEquals("13.7 ftUS", CoordinateFormat.distance(4.178, usft))
        assertEquals("1,371 ftUS", CoordinateFormat.distance(417.8, usft))
        assertEquals("1.24 mi", CoordinateFormat.distance(2000.0, usft))
        assertEquals("3.28 ft", CoordinateFormat.distance(1.0, LinearUnit.INTERNATIONAL_FEET))
    }

    @Test fun `deltas are signed and never show minus zero`() {
        assertEquals("+0.42 m", CoordinateFormat.delta(0.42, LinearUnit.METERS, 2))
        assertEquals("−1.25 m", CoordinateFormat.delta(-1.25, LinearUnit.METERS, 2))
        assertEquals("+0.00 m", CoordinateFormat.delta(-0.001, LinearUnit.METERS, 2))
        assertEquals("+3.28 ftUS", CoordinateFormat.delta(1.0, usft, 2))
    }

    @Test fun `lengths use the unit and group thousands`() {
        assertEquals("1,286.74 ftUS", CoordinateFormat.length(392.2, usft, 2).let { it })
        assertEquals("0.020 m", CoordinateFormat.length(0.02, LinearUnit.METERS))
    }

    @Test fun `copy text has no grouping commas and names the system`() {
        val p = ConvertedPosition(HorizontalSystem.STATE_PLANE, OutputDatum.NAD83_2011, usft, 41.3, -76.0,
            easting = 2_442_754.191, northing = 435_037.467, zoneLabel = "SPCS 3701",
            vertical = VerticalReference.NAVD88_GEOID18, height = 1286.74)
        assertEquals("N 435037.47, E 2442754.19 ftUS (NAD83(2011) SPCS 3701)", CoordinateFormat.copyText(p))
    }
}
