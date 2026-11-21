package app.surrealar.coords

import io.github.pennstatecs.geokit.projection.SpcsZones
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app side of State Plane: the converter's use of the selected zone and the persisted setting.
 * (Moved out of SpcsZonesTest when the zone math moved to :geokit-core; the pure zone tests live there.)
 */
class SpcsZoneSettingsTest {

    @Test fun `converter uses the selected zone and flags a point outside it`() {
        val conv = CoordinateConverter { _, _ -> null }
        val src = SourcePosition(34.0522, -118.2437, 100.0, null, isNad83 = true, epochYear = 2026.9)
        val ca5 = SpcsZones.all.first { it.ngsZone == 405 }
        val ok = conv.convert(src, CoordinateSystemSettings(HorizontalSystem.STATE_PLANE, spcsZoneEpsg = ca5.epsg))
        val (e, n) = ca5.projection.forward(34.0522, -118.2437)
        assertEquals(e, ok.easting!!, 1e-9); assertEquals(n, ok.northing!!, 1e-9)
        assertEquals("SPCS 0405", ok.zoneLabel)
        assertFalse(ok.outsideZone)

        val wrong = conv.convert(src, CoordinateSystemSettings(HorizontalSystem.STATE_PLANE, spcsZoneEpsg = 6562,
            unit = LinearUnit.US_SURVEY_FEET))
        assertTrue(wrong.outsideZone)
        assertTrue(CoordinateFormat.systemSummary(wrong).endsWith("outside zone"))
    }

    @Test fun `horizontal setting persists the zone and reads the earlier PA-only values`() {
        val s = CoordinateSystemSettings(HorizontalSystem.STATE_PLANE, spcsZoneEpsg = 6417)
        assertEquals("spcs83:6417", s.horizontalPrefValue)
        assertEquals(HorizontalSystem.STATE_PLANE to 6417, CoordinateSystemSettings.parseHorizontal("spcs83:6417"))
        assertEquals(HorizontalSystem.STATE_PLANE to 6564, CoordinateSystemSettings.parseHorizontal("spcs83_pa_south"))
        assertEquals(HorizontalSystem.UTM, CoordinateSystemSettings.parseHorizontal("utm").first)
        assertEquals(HorizontalSystem.GEOGRAPHIC, CoordinateSystemSettings.parseHorizontal(null).first)
        assertEquals("utm", CoordinateSystemSettings(HorizontalSystem.UTM).horizontalPrefValue)
    }
}
