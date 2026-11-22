package app.surrealar.ui.toolbar

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.ui.toolbar.ToolbarPipeline.next
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

/** Step 2: the toolbar says why there's no position, and greys a lost position for up to 60 s. */
class ToolbarNoFixStatesTest {

    private val EXT = LocationSourceType.EXTERNAL
    private val INT = LocationSourceType.INTERNAL

    private fun fix(provider: Provider = Provider.EXTERNAL_TCP, rtk: RtkStatus = RtkStatus.FIX, lat: Double = 41.2) = Fix(
        provider = provider, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = lat, lonDeg = -76.0, altEllipsoidalM = 300.0, altMslM = 333.0, geoidSeparationM = -33.0,
        hDop = 0.8, vDop = 1.2, pDop = null, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = rtk,
        satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = null, courseDeg = null,
    )

    private val connecting = ToolbarLinkInputs(externalPhase = ExternalLinkPhase.CONNECTING)
    private fun connected(lastRaw: Long?) = ToolbarLinkInputs(externalPhase = ExternalLinkPhase.CONNECTED, lastRawNmeaAtMs = lastRaw)

    private fun state(source: LocationSourceType, link: ToolbarLinkInputs, last: LastFix? = null, now: Long = 100_000L) =
        GnssToolbarStateMapper.noFix(source, "RS2+", link, last, now)

    @Test fun `external connecting`() {
        val s = state(EXT, connecting)
        assertEquals("Connecting…", s.fixText); assertEquals(GnssStatusLevel.NONE, s.fixLevel)
        assertEquals("--", s.latLonText); assertFalse(s.isStale)
    }

    @Test fun `external connected but silent counts the seconds`() {
        assertEquals("No data 12s", state(EXT, connected(lastRaw = 88_000L)).fixText)
        assertEquals("No data", state(EXT, connected(lastRaw = null)).fixText)
    }

    @Test fun `external streaming NMEA without a fix`() {
        assertEquals("No fix", state(EXT, connected(lastRaw = 99_500L)).fixText)
    }

    @Test fun `internal location off vs acquiring`() {
        assertEquals("Location off", state(INT, ToolbarLinkInputs(locationEnabled = false)).fixText)
        assertEquals("No fix", state(INT, ToolbarLinkInputs(locationEnabled = true)).fixText)
    }

    @Test fun `lost link keeps the last position greyed with the age, no accuracy or sats`() {
        val last = LastFix(fix(), atMs = 88_000L, coords = null)
        val s = state(EXT, connecting, last)
        assertTrue(s.isStale)
        assertEquals("Fix (lost 12s)", s.fixText)
        assertEquals(GnssStatusLevel.NONE, s.fixLevel)
        assertEquals("41.200000, -76.000000", s.latLonText)
        assertEquals("333.00 m", s.altitudeText)
        assertNull(s.accuracyText); assertNull(s.satelliteText)
        assertEquals("RS2+", s.sourceText)
        // Same when the receiver is connected but has stopped sending.
        assertTrue(state(EXT, connected(lastRaw = 80_000L), last).isStale)
    }

    @Test fun `after 60 s lost the position falls back to --`() {
        val last = LastFix(fix(), atMs = 39_000L, coords = null)    // 61 s ago
        val s = state(EXT, connecting, last)
        assertFalse(s.isStale); assertEquals("--", s.latLonText); assertEquals("Connecting…", s.fixText)
    }

    @Test fun `data still flowing means no fix, not lost`() {
        val last = LastFix(fix(), atMs = 95_000L, coords = null)
        val s = state(EXT, connected(lastRaw = 99_800L), last)
        assertFalse(s.isStale); assertEquals("No fix", s.fixText)
    }

    @Test fun `internal fix lost keeps the internal position greyed`() {
        val last = LastFix(fix(Provider.INTERNAL, RtkStatus.SINGLE), atMs = 95_000L, coords = null)
        val s = state(INT, ToolbarLinkInputs(locationEnabled = true), last)
        assertTrue(s.isStale); assertTrue(s.fixText, s.fixText.endsWith("(lost 5s)"))
    }

    @Test fun `a fix from the other provider is never shown as the lost position`() {
        val internalLast = LastFix(fix(Provider.INTERNAL), atMs = 95_000L, coords = null)
        val s = state(EXT, connecting, internalLast)
        assertFalse(s.isStale); assertEquals("--", s.latLonText)
    }

    // ── Pipeline memory: forgotten on source or receiver change, never keeps replay ──

    private fun inputs(source: LocationSourceType, f: Fix?, key: String = "reach_rs2_plus@192.168.42.1:9001") =
        ToolbarInputs(source, f, SkySnapshot(), CoordinateSystemSettings(), "RS2+", receiverKey = key)

    @Test fun `memory follows the current source and receiver only`() {
        var m: ToolbarPipeline.FixMemory? = null
        m = m.next(inputs(EXT, fix()), null, 1_000L)
        assertEquals(1_000L, m?.last?.atMs)
        m = m.next(inputs(EXT, null), null, 2_000L)
        assertEquals("kept while lost", 1_000L, m?.last?.atMs)
        assertNull("switching to internal forgets it", m.next(inputs(INT, null), null, 3_000L))
        assertNull("another receiver forgets it", m.next(inputs(EXT, null, key = "reach_rs4@10.0.0.7:9001"), null, 3_000L))
        assertEquals("replay fixes are never remembered", 1_000L, m.next(inputs(EXT, fix(Provider.REPLAY)), null, 4_000L)?.last?.atMs)
    }
}
