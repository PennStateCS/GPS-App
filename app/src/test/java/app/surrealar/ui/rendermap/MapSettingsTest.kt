package app.surrealar.ui.rendermap

import com.google.android.gms.maps.GoogleMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSettingsTest {

    @Test
    fun `map settings defaults are conservative`() {
        val s = MapSettings()
        assertEquals(GoogleMap.MAP_TYPE_NORMAL, s.defaultMapType)
        assertEquals(MapGridMode.OFF, s.defaultGridMode)
        assertEquals(PointLabelMode.OFF, s.defaultPointLabelMode)
        assertTrue(s.showMyLocationByDefault)
        assertFalse(s.keepMapToolsOpenByDefault)
        assertTrue(s.mapPointsDrawerExpandedByDefault)
    }

    @Test
    fun `map type tokens round-trip and never yield NONE`() {
        for (t in intArrayOf(
            GoogleMap.MAP_TYPE_NORMAL, GoogleMap.MAP_TYPE_SATELLITE,
            GoogleMap.MAP_TYPE_HYBRID, GoogleMap.MAP_TYPE_TERRAIN,
        )) {
            assertEquals(t, MapTypeTokens.fromToken(MapTypeTokens.toToken(t)))
        }
        // Unknown/null/none → Normal.
        assertEquals(GoogleMap.MAP_TYPE_NORMAL, MapTypeTokens.fromToken(null))
        assertEquals(GoogleMap.MAP_TYPE_NORMAL, MapTypeTokens.fromToken("none"))
        assertEquals(GoogleMap.MAP_TYPE_NORMAL, MapTypeTokens.fromToken("garbage"))
    }

    @Test
    fun `grid and label enums resolve from prefKey and legacy name`() {
        assertEquals(MapGridMode.FINE, MapGridMode.fromPrefKey("fine"))
        assertEquals(MapGridMode.COARSE, MapGridMode.fromPrefKey("COARSE")) // legacy enum name
        assertEquals(MapGridMode.OFF, MapGridMode.fromPrefKey("nope"))
        assertEquals(PointLabelMode.DISTANCE, PointLabelMode.fromPrefKey("distance"))
        assertEquals(PointLabelMode.ELEVATION, PointLabelMode.fromPrefKey("ELEVATION"))
        assertEquals(PointLabelMode.OFF, PointLabelMode.fromPrefKey(null))
    }
}
