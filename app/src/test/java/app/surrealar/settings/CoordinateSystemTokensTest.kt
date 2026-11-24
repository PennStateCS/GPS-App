package app.surrealar.settings

import app.surrealar.coords.globalGeoidModel
import app.surrealar.coords.toGeodeticDatum
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.ReceiverDatum
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.convert.GeodeticDatum
import io.github.pennstatecs.geokit.geoid.GeoidModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence contract of the Settings → Coordinates enums after Phase 2: the old tokens and defaults
 * are unchanged, the new values have their own stable tokens and round-trip, and an unknown stored
 * value (e.g. written by a newer app) falls back to the existing default.
 */
class CoordinateSystemTokensTest {

    @Test fun `existing tokens and order are unchanged, new ones are appended`() {
        assertEquals(listOf("wgs84", "nad83_2011", "itrf2020"), OutputDatum.entries.map { it.prefKey })
        assertEquals(listOf("ellipsoidal", "navd88_geoid18", "receiver_msl", "egm2008", "egm96"),
            VerticalReference.entries.map { it.prefKey })
        // No new receiver frames: the receiver stays ITRF2014 or NAD83(2011).
        assertEquals(listOf("itrf2014", "nad83_2011"), ReceiverDatum.entries.map { it.prefKey })
    }

    @Test fun `every value round-trips through its token`() {
        OutputDatum.entries.forEach { assertEquals(it, OutputDatum.fromPrefKey(it.prefKey)) }
        VerticalReference.entries.forEach { assertEquals(it, VerticalReference.fromPrefKey(it.prefKey)) }
        assertEquals(OutputDatum.ITRF2020, OutputDatum.fromPrefKey("itrf2020"))
        assertEquals(VerticalReference.EGM2008, VerticalReference.fromPrefKey("egm2008"))
        assertEquals(VerticalReference.EGM96, VerticalReference.fromPrefKey("egm96"))
    }

    @Test fun `unknown or missing tokens fall back to the existing defaults`() {
        assertEquals(OutputDatum.WGS84, OutputDatum.fromPrefKey("natrf2022"))
        assertEquals(OutputDatum.WGS84, OutputDatum.fromPrefKey(null))
        assertEquals(VerticalReference.RECEIVER_MSL, VerticalReference.fromPrefKey("egm2020"))
        assertEquals(VerticalReference.RECEIVER_MSL, VerticalReference.fromPrefKey(null))
        val d = CoordinateSystemSettings()
        assertEquals(OutputDatum.WGS84, d.datum)
        assertEquals(VerticalReference.RECEIVER_MSL, d.vertical)
        assertEquals(ReceiverDatum.ITRF2014, d.externalReceiverDatum)
    }

    @Test fun `labels follow the Phase 1 style`() {
        assertEquals("Orthometric height H (EGM2008)", VerticalReference.EGM2008.label)
        assertEquals("H EGM2008", VerticalReference.EGM2008.shortLabel)
        assertTrue(VerticalReference.EGM96.label.contains("fallback"))
        assertEquals("H EGM96", VerticalReference.EGM96.shortLabel)
        assertEquals("ITRF2020", OutputDatum.ITRF2020.label)
        // Existing labels untouched.
        assertEquals("WGS84 (ITRF2014)", OutputDatum.WGS84.label)
        assertEquals("NAD83(2011)", OutputDatum.NAD83_2011.label)
    }

    @Test fun `new values map to the geokit frame and models, never NATRF2022`() {
        assertEquals(GeodeticDatum.ITRF2020, OutputDatum.ITRF2020.toGeodeticDatum())
        assertTrue(OutputDatum.entries.none { it.prefKey.contains("natrf") || it.label.contains("NATRF") })
        assertEquals(GeoidModelId.EGM2008, VerticalReference.EGM2008.globalGeoidModel())
        assertEquals(GeoidModelId.EGM96, VerticalReference.EGM96.globalGeoidModel())
        assertNull(VerticalReference.NAVD88_GEOID18.globalGeoidModel())
        assertNull(VerticalReference.RECEIVER_MSL.globalGeoidModel())
        assertNull(VerticalReference.ELLIPSOIDAL.globalGeoidModel())
    }
}
