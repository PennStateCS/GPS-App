package app.surrealar.ui.toolbar

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.status.CorrectionHealth
import app.surrealar.gnss.status.GnssStatusInterpreter
import app.surrealar.gnss.status.GnssStatusInterpreter.Warning
import app.surrealar.receiver.selection.ConnectError
import app.surrealar.receiver.selection.LiveSourceKind
import app.surrealar.receiver.selection.ReceiverTarget
import app.surrealar.receiver.selection.SourceTransition
import app.surrealar.ui.home.HomeFieldStatusMapper
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

/**
 * The compact toolbar's use of the shared interpretation: warnings, never-green-without-proof, live-source
 * attribution (receiver switching), transitions, internal / external / replay, missing accuracy, RS4 reference.
 */
class ToolbarInterpretationTest {

    private val target = ReceiverTarget("192.168.42.1", 9001)

    private fun fix(
        rtk: RtkStatus = RtkStatus.FIX, h: Double? = 0.02, v: Double? = 0.03, age: Double? = 1.0,
        provider: Provider = Provider.EXTERNAL_TCP,
    ) = Fix(
        provider = provider, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.2, lonDeg = -76.0, altEllipsoidalM = 300.0, altMslM = 333.0, geoidSeparationM = -33.0,
        hDop = 0.8, vDop = 1.2, pDop = 1.4, hAccM = h, vAccM = v,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = rtk,
        satsUsed = 20, satsVisible = 25, diffAgeS = age, speedMps = null, courseDeg = null,
    )

    private val liveExt = ToolbarLiveSource(LiveSourceKind.EXTERNAL, null)

    private fun state(
        f: Fix?, source: LocationSourceType = LocationSourceType.EXTERNAL, corr: CorrectionHealth = CorrectionHealth.OK,
        ref: GnssStatusInterpreter.Reference? = null, live: ToolbarLiveSource? = liveExt, replay: Boolean = false,
        link: ToolbarLinkInputs = ToolbarLinkInputs(ExternalLinkPhase.CONNECTED, lastRawNmeaAtMs = 0L),
        last: LastFix? = null,
    ): GnssToolbarState = (GnssToolbarStateMapper.map(source, f, SkySnapshot(), "RS4", null, link, last, 1_000L,
        replayActive = replay, correctionHealth = corr, reference = ref, live = live) as ToolbarMapResult.Render).state

    @Test fun `RTK fix with a reported 2 cm and no warning is green and quiet`() {
        val s = state(fix())
        assertEquals(GnssStatusLevel.SUCCESS, s.fixLevel)
        assertEquals(GnssStatusLevel.SUCCESS, s.accuracyLevel)
        assertNull("no permanent OK", s.primaryWarning)
        assertEquals(GnssStatusInterpreter.Quality.HIGH_ACCURACY, s.quality)
    }

    @Test fun `RTK fix is never green without a reported accuracy within target`() {
        assertEquals(GnssStatusLevel.WARNING, state(fix(h = null, v = null)).fixLevel)          // unverified
        assertEquals(GnssStatusLevel.WARNING, state(fix(h = 0.12)).fixLevel)          // below target
        assertEquals(GnssStatusLevel.WARNING, state(fix(h = 0.12)).accuracyLevel)
        assertTrue("estimated shows ~", state(fix(h = null, v = null)).compactAccuracyText!!.startsWith("~"))
        assertEquals("an estimate is never green", GnssStatusLevel.WARNING, state(fix(rtk = RtkStatus.FIX, h = null, v = null)).accuracyLevel)
    }

    @Test fun `a warning turns RTK fix amber and is the compact warning`() {
        val s = state(fix(), corr = CorrectionHealth.STALE)
        assertEquals(GnssStatusLevel.WARNING, s.fixLevel)
        assertEquals(Warning.CORRECTIONS_STALE, s.primaryWarning)
        assertEquals("Warning: Corrections stale", s.warningDescription)
        assertEquals(Warning.CORRECTIONS_AGING, state(fix(), corr = CorrectionHealth.AGING).primaryWarning)
        assertEquals(Warning.CORRECTIONS_UNAVAILABLE, state(fix(rtk = RtkStatus.SINGLE, age = null), corr = CorrectionHealth.UNAVAILABLE).primaryWarning)
    }

    @Test fun `RS4 reference uncertainty and invalid vertical are never concealed`() {
        val s = state(fix(v = 999.0), ref = GnssStatusInterpreter.Reference.UNCONFIRMED)
        assertEquals(listOf(Warning.REFERENCE_UNCONFIRMED, Warning.VERTICAL_INVALID), s.warnings)
        assertEquals("Warning: Position reference unconfirmed. Also: Vertical accuracy invalid", s.warningDescription)
        assertEquals(Warning.REFERENCE_MAY_BE_COMPENSATED,
            state(fix(), ref = GnssStatusInterpreter.Reference.MAY_BE_COMPENSATED).primaryWarning)
        assertNull(state(fix(), ref = GnssStatusInterpreter.Reference.POLE_TIP).primaryWarning)
    }

    @Test fun `correction age, satellites and vertical accuracy stay out of the compact accuracy`() {
        val s = state(fix(v = 0.031))
        assertEquals("±0.02 m", s.compactAccuracyText)
        assertEquals("Accuracy plus or minus 2 centimetres", s.accuracyHDescription)
        assertEquals("1.0s", s.correctionAgeText)   // still computed for the report
        assertEquals("20/25", s.satelliteText)
    }

    @Test fun `float, single, no fix`() {
        assertEquals(GnssStatusLevel.WARNING, state(fix(rtk = RtkStatus.FLOAT)).fixLevel)
        assertEquals("Float", state(fix(rtk = RtkStatus.FLOAT)).fixText)
        assertEquals("Single", state(fix(rtk = RtkStatus.SINGLE, h = 1.5)).fixText)
        assertEquals(GnssStatusLevel.ERROR, state(fix(rtk = RtkStatus.SINGLE, h = 1.5)).accuracyLevel)
        assertEquals("No Fix", state(fix(rtk = RtkStatus.NONE)).fixText)
    }

    @Test fun `internal and replay have no correction warnings`() {
        val internal = state(fix(rtk = RtkStatus.SINGLE, h = 3.0, provider = Provider.INTERNAL), source = LocationSourceType.INTERNAL,
            corr = CorrectionHealth.STALE, live = ToolbarLiveSource(LiveSourceKind.DEVICE, null))
        assertTrue(internal.warnings.isEmpty()); assertEquals(GnssStatusLevel.SUCCESS, internal.accuracyLevel)
        val replay = state(fix(provider = Provider.REPLAY), corr = CorrectionHealth.STALE, replay = true,
            live = ToolbarLiveSource(LiveSourceKind.REPLAY, null))
        assertTrue(replay.isReplay); assertTrue(replay.warnings.isEmpty())
    }

    @Test fun `receiver switching - the old receiver's fix is never shown under the new selection`() {
        val s = state(fix(), live = ToolbarLiveSource(LiveSourceKind.NONE, SourceTransition.Switching(target)))
        assertEquals("Switching…", s.fixText)
        assertEquals("--", s.latLonText)
        assertTrue(s.isWaiting)
        // …and its last position is not kept either
        val lost = state(null, live = ToolbarLiveSource(LiveSourceKind.NONE, SourceTransition.Switching(target)),
            link = ToolbarLinkInputs(ExternalLinkPhase.CONNECTING), last = LastFix(fix(), atMs = 0L, coords = null))
        assertFalse(lost.isStale); assertEquals("--", lost.latLonText)
    }

    @Test fun `transitions say what is happening`() {
        fun text(t: SourceTransition) = state(null, live = ToolbarLiveSource(LiveSourceKind.NONE, t),
            link = ToolbarLinkInputs(ExternalLinkPhase.CONNECTING)).fixText
        assertEquals("Reconnecting…", text(SourceTransition.Reconnecting(target)))
        assertEquals("Connecting…", text(SourceTransition.Connecting(target)))
        assertEquals("Not connected", text(SourceTransition.ConnectFailed(target, ConnectError.Unreachable(9001))))
        assertEquals("Not connected", text(SourceTransition.ExternalNotActive(target)))
        assertEquals("No data", state(null, live = ToolbarLiveSource(LiveSourceKind.NONE, SourceTransition.WaitingForPosition(target)),
            link = ToolbarLinkInputs(ExternalLinkPhase.CONNECTED)).fixText)
    }

    @Test fun `lost fix keeps the last position, stale, without warnings or capture suitability`() {
        val s = state(null, live = ToolbarLiveSource(LiveSourceKind.NONE, SourceTransition.Reconnecting(target)),
            link = ToolbarLinkInputs(ExternalLinkPhase.CONNECTING), last = LastFix(fix(), atMs = 0L, coords = null))
        assertTrue(s.isStale)
        assertTrue(s.fixText, s.fixText.endsWith("(lost 1s)"))
        assertNull(s.compactAccuracyText)
        assertTrue(s.warnings.isEmpty())
        assertTrue(s.fixDescription.endsWith("Not for capture"))
    }

    @Test fun `toolbar and Home agree on the same fix`() {
        // Same fix, raw staleness (age 20 s): both lead with Corrections stale; with 1 s both are quiet / High accuracy.
        for ((age, expected) in listOf(20.0 to "Corrections stale", 1.0 to "High accuracy")) {
            val f = fix(age = age)
            val toolbar = state(f, corr = GnssStatusInterpreter.rawStale(age))
            val home = HomeFieldStatusMapper.map(HomeFieldStatusMapper.Inputs(
                isInternal = false, externalLabel = "RS4", receiverName = null, connectionAddress = null,
                rtkStatus = f.rtkStatus, hAccM = f.hAccM, vAccM = f.vAccM, satsUsed = 20, satsVisible = 25,
                hdop = 0.8, pdop = 1.4, correctionAgeS = age, correctionStationId = null, nmeaLinesPerSecond = 5.0,
                nmeaParseErrors = 0, hasRecentFix = true))
            assertEquals(expected, home.headline)
            assertEquals(expected, toolbar.primaryWarning?.text ?: toolbar.quality?.headline)
        }
    }
}
