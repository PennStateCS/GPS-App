package app.surrealar.ui.toolbar.report

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.status.CorrectionHealth
import app.surrealar.receiver.selection.LiveSourceKind
import app.surrealar.receiver.selection.LiveSourceState
import app.surrealar.receiver.selection.SourceTransition
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures
import app.surrealar.ui.toolbar.LastFix
import app.surrealar.ui.toolbar.ToolbarLiveSource
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GNSS Status report content (pure). */
class GnssStatusReportPresenterTest {

    private fun row(r: GnssStatusReport, section: String, label: String): ReportRow? =
        r.sections.firstOrNull { it.id == section }?.rows?.firstOrNull { it.label == label }

    private fun value(r: GnssStatusReport, section: String, label: String) = row(r, section, label)?.value

    @Test fun `sections A to F for a live RS4 Pro`() {
        val r = GnssStatusReportPresenter.build(GnssReportFixtures.inputs().copy(
            tilt = app.surrealar.receiver.tilt.TiltStatus.NONE.copy(applicable = true,
                compensatedOutput = app.surrealar.settings.model.CompensatedOutputSetting.DISABLED)))
        assertEquals(listOf("receiver", "solution", "corrections", "position", "receiver_specific", "advanced"), r.sections.map { it.id })
        assertTrue(r.sections.last().collapsible)
        assertEquals("External receiver", value(r, "receiver", "Source"))
        assertEquals("Reach RS4 Pro base · Emlid Reach RS4 Pro", value(r, "receiver", "Receiver"))
        assertEquals("Connected · receiving positions", value(r, "receiver", "Connection"))
        assertEquals("82 %, charging", value(r, "receiver", "Battery"))
        assertEquals("34.7", value(r, "receiver", "Firmware"))
        assertEquals("High accuracy", r.headline)
        assertEquals("Fix", value(r, "solution", "Solution"))
        assertEquals("±0.01 m", value(r, "solution", "Horizontal accuracy"))
        assertEquals("Reported by the receiver · 68 % radius", row(r, "solution", "Horizontal accuracy")!!.note)
        assertEquals("±0.020 m", value(r, "solution", "Vertical accuracy"))
        assertEquals("18 used / 24 visible", value(r, "solution", "Satellites"))
        assertEquals("HDOP 0.7 · VDOP 1.1 · PDOP 1.3", value(r, "solution", "DOP"))
        assertEquals("1.0 s", value(r, "corrections", "Correction age"))
        assertEquals("Within limits", value(r, "corrections", "Health"))
        assertEquals("Receiving · NTRIP", value(r, "corrections", "Correction link"))
        assertEquals("0042", value(r, "corrections", "Base station"))
        assertEquals("N 435,037.47 · E 2,442,754.19 ftUS", value(r, "position", "Grid"))
        assertEquals("Not applicable for this frame", value(r, "position", "Coordinate epoch"))
        assertEquals("1,286.74 ftUS NAVD88", value(r, "position", "Elevation"))
        assertEquals("12 lines/s", value(r, "advanced", "NMEA stream"))
        assertTrue(r.coordToggleAvailable); assertFalse(r.showingLatLon)
    }

    @Test fun `missing values read unavailable or not reported, zero stays zero`() {
        val fix = ReceiverUiFixtures.fix(used = null, visible = null, diffAge = null).copy(hDop = null, vDop = null, pDop = null, vAccM = null)
        val frame = GnssReportFixtures.frame(fix).let { it.copy(inputs = it.inputs.copy(sky = io.github.pennstatecs.geokit.gnss.model.SkySnapshot())) }
        val r = GnssStatusReportPresenter.build(GnssReportFixtures.inputs(frame).copy(battery = null, corrections = null,
            diag = app.surrealar.gnss.diagnostics.DiagnosticData()))
        assertEquals("Not reported", value(r, "receiver", "Battery"))
        assertEquals("Not reported", value(r, "solution", "Vertical accuracy"))
        assertEquals("Unavailable", value(r, "solution", "Satellites"))
        assertEquals("Unavailable", value(r, "solution", "DOP"))
        assertEquals("Not reported", value(r, "corrections", "Correction age"))
        assertEquals("Not reported", value(r, "corrections", "Correction link"))
        assertEquals("No data received", value(r, "advanced", "NMEA stream"))
        assertEquals("0 total · 0 in the last minute", value(r, "advanced", "Parse errors"))
        val zero = GnssStatusReportPresenter.build(GnssReportFixtures.inputs(GnssReportFixtures.frame(ReceiverUiFixtures.fix(diffAge = 0.0))))
        assertEquals("0.0 s", value(zero, "corrections", "Correction age"))
    }

    @Test fun `correction warnings and the shared headline`() {
        val stale = GnssStatusReportPresenter.build(GnssReportFixtures.inputs(GnssReportFixtures.frame(
            ReceiverUiFixtures.fix(diffAge = 20.0), corr = CorrectionHealth.STALE)))
        assertEquals("Corrections stale", stale.headline)
        assertEquals(ReportLevel.WARNING, stale.level)
        assertEquals(ReportLevel.WARNING, row(stale, "corrections", "Health")!!.level)
        assertEquals("20.0 s", value(stale, "corrections", "Correction age"))
        // Home agrees (raw rule): same headline from the Home mapper for the same inputs
        assertEquals("Corrections stale", GnssStatusReportPresenter.homeStatus(GnssReportFixtures.inputs(GnssReportFixtures.frame(
            ReceiverUiFixtures.fix(diffAge = 20.0), corr = CorrectionHealth.STALE)))!!.headline)
        val aging = GnssStatusReportPresenter.build(GnssReportFixtures.inputs(GnssReportFixtures.frame(
            ReceiverUiFixtures.fix(diffAge = 11.0), corr = CorrectionHealth.AGING)))
        assertEquals("Corrections aging", aging.headline)
        assertEquals("Aging above 10 s · stale above 15 s", value(aging, "corrections", "Limits"))
    }

    @Test fun `switching - no fix from the previous receiver, selected receiver labelled as not live`() {
        val frame = GnssReportFixtures.frame(ReceiverUiFixtures.fix(),
            live = ToolbarLiveSource(LiveSourceKind.NONE, SourceTransition.Switching(ReceiverUiFixtures.TARGET)))
        val r = GnssStatusReportPresenter.build(GnssReportFixtures.inputs(frame, GnssReportFixtures.switching))
        assertEquals("Switching receiver…", value(r, "receiver", "Connection"))
        assertEquals("Selected — not the live source yet", row(r, "receiver", "Receiver")!!.note)
        assertEquals("Unavailable", value(r, "solution", "Horizontal accuracy"))
        assertEquals("Unavailable", value(r, "position", "Position"))
        assertEquals("Not reported", value(r, "receiver", "Firmware"))
    }

    @Test fun `lost fix - last known position marked stale, not for capture`() {
        val frame = GnssReportFixtures.frame(null, live = ToolbarLiveSource(LiveSourceKind.NONE, SourceTransition.Reconnecting(ReceiverUiFixtures.TARGET)),
            last = LastFix(ReceiverUiFixtures.fix(), 0L, null))
        val r = GnssStatusReportPresenter.build(GnssReportFixtures.inputs(frame,
            LiveSourceState(LiveSourceKind.NONE, null, null, SourceTransition.Reconnecting(ReceiverUiFixtures.TARGET))))
        assertEquals("Last known position", value(r, "position", "Status"))
        assertEquals(ReportLevel.WARNING, row(r, "position", "Status")!!.level)
        assertTrue(row(r, "solution", "Solution")!!.note!!.contains("not for capture"))
        assertEquals("Link lost, reconnecting…", value(r, "receiver", "Connection"))
    }

    @Test fun `device GNSS and replay`() {
        val internalFix = ReceiverUiFixtures.fix(Provider.INTERNAL, RtkStatus.SINGLE)
        val frame = GnssReportFixtures.frame(internalFix, source = LocationSourceType.INTERNAL,
            live = ToolbarLiveSource(LiveSourceKind.DEVICE, null))
        val r = GnssStatusReportPresenter.build(GnssReportFixtures.inputs(frame, LiveSourceState(LiveSourceKind.DEVICE, internalFix)).copy(
            selection = ReceiverUiFixtures.internal))
        assertEquals("Device GNSS", value(r, "receiver", "Source"))
        assertNull(row(r, "receiver", "Battery"))
        assertEquals("Not applicable (device GNSS)", value(r, "corrections", "Corrections"))
        assertNull(r.sections.firstOrNull { it.id == "receiver_specific" })
        val replayFix = ReceiverUiFixtures.fix(Provider.REPLAY)
        val rp = GnssStatusReportPresenter.build(GnssReportFixtures.inputs(GnssReportFixtures.frame(replayFix, replay = true,
            live = ToolbarLiveSource(LiveSourceKind.REPLAY, null)), LiveSourceState(LiveSourceKind.REPLAY, replayFix)))
        assertEquals("NMEA replay (recorded data)", value(rp, "receiver", "Source"))
        assertNotNull(row(rp, "receiver", "Replay"))
    }

    @Test fun `copy status - readable snapshot with timestamp, never the receiver address`() {
        val r = GnssStatusReportPresenter.build(GnssReportFixtures.inputs(GnssReportFixtures.frame(
            ReceiverUiFixtures.fix(diffAge = 20.0), corr = CorrectionHealth.STALE)))
        val text = GnssStatusReportPresenter.snapshotText(r, "2026-11-25 12:00:00 EST")
        println(text)
        listOf("Time: 2026-11-25 12:00:00 EST", "Status: Corrections stale", "Warnings: Corrections stale",
            "Receiver: Reach RS4 Pro base · Emlid Reach RS4 Pro", "Solution: Fix", "Correction age: 20.0 s",
            "Grid: N 435,037.47 · E 2,442,754.19 ftUS", "Reference system: NAD83(2011)", "Coordinate epoch:",
            "Horizontal accuracy: ±0.01 m (Reported by the receiver · 68 % radius)", "Elevation: 1,286.74 ftUS NAVD88")
            .forEach { assertTrue("missing '$it'", text.contains(it)) }
        assertFalse("address stays out of copies", text.contains("192.168.42.1"))
    }
}
