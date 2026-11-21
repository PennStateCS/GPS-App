package app.surrealar.ui.toolbar

import app.surrealar.domain.model.LocationSourceType
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ToolbarLinkLogTest {
    private val EXT = LocationSourceType.EXTERNAL
    private fun fix() = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.2, lonDeg = -76.0, altEllipsoidalM = 300.0, altMslM = 333.0, geoidSeparationM = -33.0,
        hDop = 0.8, vDop = 1.2, pDop = null, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = RtkStatus.FIX,
        satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = null, courseDeg = null,
    )
    private fun noFix(link: ToolbarLinkInputs, last: LastFix? = null) =
        ToolbarLinkLog.classify(GnssToolbarStateMapper.noFix(EXT, "RS2+", link, last, 100_000L))

    @Test fun `link states from toolbar states`() {
        val live = (GnssToolbarStateMapper.map(EXT, fix(), SkySnapshot(), "RS2+") as ToolbarMapResult.Render).state
        assertEquals(ToolbarLinkLog.Link.CONNECTED, ToolbarLinkLog.classify(live))
        assertEquals(ToolbarLinkLog.Link.CONNECTING, noFix(ToolbarLinkInputs(externalPhase = ExternalLinkPhase.CONNECTING)))
        assertEquals(ToolbarLinkLog.Link.STALE, noFix(ToolbarLinkInputs(externalPhase = ExternalLinkPhase.CONNECTED, lastRawNmeaAtMs = 88_000L)))
        assertEquals(ToolbarLinkLog.Link.NO_FIX, noFix(ToolbarLinkInputs(externalPhase = ExternalLinkPhase.CONNECTED, lastRawNmeaAtMs = 99_900L)))
        assertEquals(ToolbarLinkLog.Link.LOST, noFix(ToolbarLinkInputs(externalPhase = ExternalLinkPhase.CONNECTING), LastFix(fix(), 95_000L, null)))
    }
}