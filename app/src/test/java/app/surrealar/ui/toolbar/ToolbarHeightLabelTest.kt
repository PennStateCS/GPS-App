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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Phase 4 WP U: concise toolbar height labels (≤ 10 characters) and a loading state instead of "--". */
class ToolbarHeightLabelTest {

    private val fix = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.234567, lonDeg = -76.123456, altEllipsoidalM = 300.0, altMslM = 422.81, geoidSeparationM = -33.0,
        hDop = 1.0, vDop = 1.2, pDop = null, hAccM = 0.015, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = RtkStatus.FIX,
        satsUsed = 18, satsVisible = 24, diffAgeS = 1.2, speedMps = null, courseDeg = null,
    )

    private fun pos(v: VerticalReference, height: Double?, label: String, loading: Boolean = false) = ConvertedPosition(
        horizontal = HorizontalSystem.GEOGRAPHIC, datum = OutputDatum.WGS84, unit = LinearUnit.METERS,
        latDeg = 41.234567, lonDeg = -76.123456, easting = null, northing = null, zoneLabel = null,
        vertical = v, height = height, heightUnavailableReason = if (height == null) "GEOID2022 data is loading" else null,
        verticalLabel = label, heightLoading = loading,
    )

    private fun state(p: ConvertedPosition) =
        (GnssToolbarStateMapper.map(LocationSourceType.EXTERNAL, fix, SkySnapshot(), "RS2+", p) as ToolbarMapResult.Render).state

    @Test fun `GEOID2022 beta shows NAPGD2022 with the beta mark, spoken in full`() {
        val s = state(pos(VerticalReference.GEOID2022, 389.12, "NAPGD2022 beta"))
        assertEquals("NAPGD2022β", s.altitudeLabel)
        assertEquals("389.12 m", s.altitudeText)
        assertEquals("Height NAPGD2022 beta 389.12 m", s.altitudeDescription)
        assertTrue(s.altitudeLabel.length <= GnssToolbarStateMapper.MAX_HEIGHT_LABEL)
    }

    @Test fun `legacy labels unchanged`() {
        for (l in listOf("NAVD88", "PRVD02", "EGM2008", "EGM96", "h", "H rcvr")) {
            val v = if (l == "h") VerticalReference.ELLIPSOIDAL else VerticalReference.NAVD88_GEOID18
            val s = state(pos(v, 422.81, l))
            assertEquals(l, s.altitudeLabel)
            assertEquals("Height $l 422.81 m", s.altitudeDescription)
        }
    }

    @Test fun `a loading model shows loading, never a stale value or dashes`() {
        val s = state(pos(VerticalReference.GEOID2022, null, "NAPGD2022 beta", loading = true))
        assertEquals(GnssToolbarStateMapper.HEIGHT_LOADING, s.altitudeText)
        assertEquals("Height NAPGD2022 beta: data is loading", s.altitudeDescription)
        // Unavailable for any other reason is still "--".
        val u = state(pos(VerticalReference.GEOID2022, null, "NAPGD2022 beta", loading = false))
        assertEquals("--", u.altitudeText)
        assertEquals("Height not available", u.altitudeDescription)
    }
}
