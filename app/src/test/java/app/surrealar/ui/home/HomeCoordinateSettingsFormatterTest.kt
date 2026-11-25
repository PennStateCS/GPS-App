package app.surrealar.ui.home

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.ReceiverDatum
import app.surrealar.settings.model.VerticalReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeCoordinateSettingsFormatterTest {

    private fun rows(s: CoordinateSystemSettings) = HomeCoordinateSettingsFormatter.summary(s).rows.associate { it.label to it.value }

    @Test fun `defaults - latitude longitude, WGS84, metres, receiver H`() {
        val r = rows(CoordinateSystemSettings())
        assertEquals("Latitude / Longitude", r["System"])
        assertEquals("WGS84 (ITRF2014)", r["Datum"])
        assertEquals("Meters", r["Units"])
        assertEquals(VerticalReference.RECEIVER_MSL.menuLabel, r["Height"])
        assertEquals(ReceiverDatum.ITRF2014.label, r["Receiver datum"])
        assertNull("preview row only when on", r["NSRS 2022 preview"])
        assertNull(HomeCoordinateSettingsFormatter.summary(CoordinateSystemSettings()).note)
    }

    @Test fun `State Plane names its zone and forces NAD83(2011)`() {
        val r = rows(CoordinateSystemSettings(HorizontalSystem.STATE_PLANE, spcsZoneEpsg = 6564,
            unit = LinearUnit.US_SURVEY_FEET, vertical = VerticalReference.NAVD88_GEOID18))
        assertEquals(true, r["System"]!!.startsWith("SPCS83 · "))
        assertEquals("NAD83(2011)", r["Datum"])
        assertEquals(LinearUnit.US_SURVEY_FEET.label, r["Units"])
        assertEquals("H · NAVD88 (GEOID18)", r["Height"])
    }

    @Test fun `SPCS2022 with the preview on shows the zone, its 2022 frame and the preview row`() {
        val r = rows(CoordinateSystemSettings(HorizontalSystem.SPCS2022, nsrs2022Preview = true, spcs2022Zone = "spcs2022:421001"))
        assertEquals("SPCS2022 (beta) · zone 421001", r["System"])
        assertEquals("Zone's 2022 frame (beta)", r["Datum"])
        assertEquals("On (beta)", r["NSRS 2022 preview"])
    }

    @Test fun `a beta choice saved with the preview off shows the default actually used, with a note`() {
        val s = CoordinateSystemSettings(datum = OutputDatum.NATRF2022, vertical = VerticalReference.GEOID2022, nsrs2022Preview = false)
        val r = rows(s)
        assertEquals("WGS84 (ITRF2014)", r["Datum"])
        assertEquals(VerticalReference.RECEIVER_MSL.menuLabel, r["Height"])
        assertNotNull(HomeCoordinateSettingsFormatter.summary(s).note)
    }
}
