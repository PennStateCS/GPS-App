package app.surrealar.settings

import app.surrealar.coords.appGeoidModel
import app.surrealar.coords.globalGeoidModel
import app.surrealar.coords.toGeodeticDatum
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.ReceiverDatum
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.convert.GeodeticDatum
import io.github.pennstatecs.geokit.geoid.GeoidModelId
import io.github.pennstatecs.geokit.status.ProductStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        assertEquals(listOf("ellipsoidal", "navd88_geoid18", "receiver_msl", "egm2008", "egm96", "geoid2022_beta"),
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
        assertEquals(VerticalReference.GEOID2022, VerticalReference.fromPrefKey("geoid2022_beta"))
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
        assertEquals("beta preview is OFF by default", false, d.nsrs2022Preview)
        assertEquals(VerticalReference.RECEIVER_MSL, VerticalReference.fromPrefKey("geoid2022"))   // only the exact token
    }

    @Test fun `beta preview - GEOID2022 is beta, offered only with the preview on, never a default`() {
        assertEquals(listOf(VerticalReference.GEOID2022), VerticalReference.entries.filter { it.isBeta })
        assertEquals(ProductStatus.VERIFIED_BETA, VerticalReference.GEOID2022.status)
        assertTrue(VerticalReference.entries.filter { !it.isBeta }.all { it.status == ProductStatus.VERIFIED_LEGACY })
        assertTrue(VerticalReference.entries.none { it.status == ProductStatus.UNAVAILABLE })
        val off = CoordinateSystemSettings()
        assertEquals(VerticalReference.entries - VerticalReference.GEOID2022, off.selectableVerticals)
        assertEquals(VerticalReference.entries, off.copy(nsrs2022Preview = true).selectableVerticals)
        // Saved beta choice with the preview off: default shown, stored value kept.
        val suspended = CoordinateSystemSettings(vertical = VerticalReference.GEOID2022)
        assertEquals(VerticalReference.GEOID2022, suspended.vertical)
        assertEquals(VerticalReference.RECEIVER_MSL, suspended.effectiveVertical)
        assertTrue(suspended.betaVerticalSuspended)
        assertEquals(VerticalReference.RECEIVER_MSL, suspended.forConversion().vertical)
        assertEquals("geoid2022_beta", suspended.vertical.prefKey)
        val active = suspended.copy(nsrs2022Preview = true)
        assertEquals(VerticalReference.GEOID2022, active.effectiveVertical)
        assertSame(active, active.forConversion())
        for (v in VerticalReference.entries.filter { !it.isBeta }) {
            val s = CoordinateSystemSettings(vertical = v)
            assertSame(s, s.forConversion())
            assertEquals(v, s.effectiveVertical)
        }
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
        assertEquals("Orthometric height H (NAPGD2022, GEOID2022 beta)", VerticalReference.GEOID2022.label)
        assertEquals("H GEOID2022 beta", VerticalReference.GEOID2022.shortLabel)
    }

    @Test fun `new values map to the geokit frame and models, never NATRF2022`() {
        assertEquals(GeodeticDatum.ITRF2020, OutputDatum.ITRF2020.toGeodeticDatum())
        assertTrue(OutputDatum.entries.none { it.prefKey.contains("natrf") || it.label.contains("NATRF") })
        assertEquals(GeoidModelId.EGM2008, VerticalReference.EGM2008.globalGeoidModel())
        assertEquals(GeoidModelId.EGM96, VerticalReference.EGM96.globalGeoidModel())
        assertNull(VerticalReference.NAVD88_GEOID18.globalGeoidModel())
        assertNull(VerticalReference.RECEIVER_MSL.globalGeoidModel())
        assertNull(VerticalReference.ELLIPSOIDAL.globalGeoidModel())
        // GEOID2022 is never a static global model (no EGM path); the app opens it as its own model.
        assertNull(VerticalReference.GEOID2022.globalGeoidModel())
        assertEquals(GeoidModelId.GEOID2022, VerticalReference.GEOID2022.appGeoidModel())
        assertEquals(GeoidModelId.EGM2008, VerticalReference.EGM2008.appGeoidModel())
        assertNull(VerticalReference.NAVD88_GEOID18.appGeoidModel())
        assertTrue(OutputDatum.entries.none { it.prefKey.contains("2022") })
    }
}
