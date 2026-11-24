package app.surrealar.coords

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.convert.GeodeticDatum
import io.github.pennstatecs.geokit.convert.HeightUnavailableReason
import io.github.pennstatecs.geokit.convert.LengthUnit
import io.github.pennstatecs.geokit.convert.TargetSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.pennstatecs.geokit.convert.VerticalReference as GeoVerticalReference

/** Settings → geokit-core conversion types, and the library result → the app's ConvertedPosition. */
class CoordinateSettingsMappingTest {

    @Test fun `units map with identical lengths`() {
        assertEquals(LengthUnit.METRE, LinearUnit.METERS.toLengthUnit())
        assertEquals(LengthUnit.US_SURVEY_FOOT, LinearUnit.US_SURVEY_FEET.toLengthUnit())
        assertEquals(LengthUnit.INTERNATIONAL_FOOT, LinearUnit.INTERNATIONAL_FEET.toLengthUnit())
        for (u in LinearUnit.entries) assertEquals(u.name, u.metersPerUnit, u.toLengthUnit().metresPerUnit, 0.0)
    }

    @Test fun `datums and vertical references map one to one`() {
        assertEquals(GeodeticDatum.WGS84_ITRF2014, OutputDatum.WGS84.toGeodeticDatum())
        assertEquals(GeodeticDatum.NAD83_2011, OutputDatum.NAD83_2011.toGeodeticDatum())
        assertEquals(GeoVerticalReference.ELLIPSOIDAL, VerticalReference.ELLIPSOIDAL.toGeoVerticalReference())
        assertEquals(GeoVerticalReference.GEOID18_ORTHOMETRIC, VerticalReference.NAVD88_GEOID18.toGeoVerticalReference())
        assertEquals(GeoVerticalReference.RECEIVER_MSL, VerticalReference.RECEIVER_MSL.toGeoVerticalReference())
    }

    @Test fun `horizontal systems map, unknown State Plane zone falls back to PA North`() {
        assertEquals(TargetSystem.Geographic, CoordinateSystemSettings().toTargetSystem())
        assertEquals(TargetSystem.UtmAuto, CoordinateSystemSettings(horizontal = HorizontalSystem.UTM).toTargetSystem())
        assertEquals(TargetSystem.StatePlane(6423),
            CoordinateSystemSettings(horizontal = HorizontalSystem.STATE_PLANE, spcsZoneEpsg = 6423).toTargetSystem())
        assertEquals(TargetSystem.StatePlane(CoordinateSystemSettings.DEFAULT_SPCS_ZONE_EPSG),
            CoordinateSystemSettings(horizontal = HorizontalSystem.STATE_PLANE, spcsZoneEpsg = 99999).toTargetSystem())
    }

    @Test fun `effective datum agrees with the app's`() {
        for (h in HorizontalSystem.entries) for (d in OutputDatum.entries) {
            val s = CoordinateSystemSettings(horizontal = h, datum = d)
            assertEquals("$h $d", s.effectiveDatum.toGeodeticDatum(), s.toOutputSystem().effectiveDatum)
        }
    }

    @Test fun `source datum flag maps`() {
        assertEquals(GeodeticDatum.NAD83_2011, SourcePosition(1.0, 2.0, null, null, isNad83 = true, epochYear = 2026.0).toGeoSource().datum)
        val w = SourcePosition(1.0, 2.0, 3.0, 4.0, isNad83 = false, epochYear = 2026.5).toGeoSource()
        assertEquals(GeodeticDatum.WGS84_ITRF2014, w.datum)
        assertEquals(3.0, w.ellipsoidalHeightM!!, 0.0); assertEquals(4.0, w.receiverMslHeightM!!, 0.0)
        assertEquals(2026.5, w.epochYear, 0.0)
    }

    @Test fun `result keeps the app labels and reason strings`() {
        val conv = CoordinateConverter { _, _ -> null }
        val src = SourcePosition(41.3477, -76.0226, 390.0, null, isNad83 = false, epochYear = 2026.886)
        val msl = conv.convert(src, CoordinateSystemSettings())
        assertEquals("H rcvr", msl.verticalLabel)
        assertEquals(HeightUnavailableReason.NO_RECEIVER_MSL.description, msl.heightUnavailableReason)
        assertEquals("receiver reported no orthometric height", msl.heightUnavailableReason)
        assertNull(msl.height)
        val ell = conv.convert(src, CoordinateSystemSettings(vertical = VerticalReference.ELLIPSOIDAL))
        assertEquals("h", ell.verticalLabel)
        val geoid = conv.convert(src, CoordinateSystemSettings(vertical = VerticalReference.NAVD88_GEOID18))
        assertEquals("NAVD88", geoid.verticalLabel)     // named even without a geoid value
        assertEquals("outside GEOID18 coverage", geoid.heightUnavailableReason)
        val noH = conv.convert(src.copy(ellipsoidalHeightM = null), CoordinateSystemSettings(vertical = VerticalReference.ELLIPSOIDAL))
        assertEquals("no ellipsoidal height", noH.heightUnavailableReason)
    }
}
