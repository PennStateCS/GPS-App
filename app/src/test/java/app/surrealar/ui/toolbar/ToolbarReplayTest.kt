package app.surrealar.ui.toolbar

import app.surrealar.domain.model.LocationSourceType
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Step 6: replay shows as REPLAY (with the icon) and never shows receiver battery or correction age. */
class ToolbarReplayTest {

    private fun fix(provider: Provider) = Fix(
        provider = provider, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.2, lonDeg = -76.0, altEllipsoidalM = 300.0, altMslM = 333.0, geoidSeparationM = -33.0,
        hDop = 0.8, vDop = 1.2, pDop = null, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = RtkStatus.FIX,
        satsUsed = 20, satsVisible = 25, diffAgeS = 1.4, speedMps = null, courseDeg = null,
    )

    private fun render(r: ToolbarMapResult) = (r as ToolbarMapResult.Render).state

    @Test fun `replay fix shows REPLAY with no battery or age`() {
        val s = render(GnssToolbarStateMapper.map(LocationSourceType.EXTERNAL, fix(Provider.REPLAY), SkySnapshot(), "RS2+"))
        assertEquals("REPLAY", s.sourceText); assertTrue(s.isReplay)
        assertFalse(s.batteryVisible); assertNull(s.correctionAgeText)
        assertEquals("Fixed", s.fixText)
    }

    @Test fun `replay active before its first fix still shows REPLAY`() {
        val s = render(GnssToolbarStateMapper.map(LocationSourceType.EXTERNAL, null, SkySnapshot(), "RS2+",
            link = ToolbarLinkInputs(externalPhase = ExternalLinkPhase.CONNECTING), replayActive = true))
        assertEquals("REPLAY", s.sourceText); assertTrue(s.isReplay); assertFalse(s.batteryVisible)
        assertEquals("No fix", s.fixText)   // not "Connecting…": there's no receiver link during replay
    }

    @Test fun `live sources are not marked as replay`() {
        val s = render(GnssToolbarStateMapper.map(LocationSourceType.EXTERNAL, fix(Provider.EXTERNAL_TCP), SkySnapshot(), "RS2+"))
        assertEquals("RS2+", s.sourceText); assertFalse(s.isReplay); assertTrue(s.batteryVisible)
    }
}
