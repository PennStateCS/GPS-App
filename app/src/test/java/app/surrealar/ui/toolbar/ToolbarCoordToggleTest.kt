package app.surrealar.ui.toolbar

import app.surrealar.coords.ConvertedPosition
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Step 5: tapping LL/NE switches lat/lon ↔ grid only when a grid system is configured. */
class ToolbarCoordToggleTest {

    private val fix = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.3477, lonDeg = -76.0226, altEllipsoidalM = 391.2, altMslM = 422.8, geoidSeparationM = -31.6,
        hDop = 0.8, vDop = 1.2, pDop = null, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = RtkStatus.FIX,
        satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = null, courseDeg = null,
    )

    private val grid = ConvertedPosition(
        horizontal = HorizontalSystem.STATE_PLANE, datum = OutputDatum.NAD83_2011, unit = LinearUnit.METERS,
        latDeg = 41.34769, lonDeg = -76.02259, easting = 744_552.97, northing = 132_599.69, zoneLabel = "SPCS 3701",
        vertical = VerticalReference.NAVD88_GEOID18, height = 422.81,
    )
    private val geographic = grid.copy(horizontal = HorizontalSystem.GEOGRAPHIC, easting = null, northing = null, zoneLabel = null)

    private fun state(coords: ConvertedPosition, preferLatLon: Boolean) =
        (GnssToolbarStateMapper.map(LocationSourceType.EXTERNAL, fix, SkySnapshot(), "RS2+", coords,
            preferLatLon = preferLatLon) as ToolbarMapResult.Render).state

    @Test fun `grid configured - NE by default, lat lon when toggled`() {
        val ne = state(grid, preferLatLon = false)
        assertTrue(ne.coordToggleAvailable)
        assertEquals("NE", ne.coordLabel)
        assertTrue(ne.latLonText, ne.latLonText.startsWith("N "))

        val ll = state(grid, preferLatLon = true)
        assertEquals("LL", ll.coordLabel)
        assertEquals("41.347690, -76.022590", ll.latLonText)
        assertEquals("height unaffected", ne.altitudeText, ll.altitudeText)
    }

    @Test fun `no grid - nothing to toggle`() {
        val s = state(geographic, preferLatLon = false)
        assertFalse(s.coordToggleAvailable)
        assertEquals(s, state(geographic, preferLatLon = true).copy())
    }
}
