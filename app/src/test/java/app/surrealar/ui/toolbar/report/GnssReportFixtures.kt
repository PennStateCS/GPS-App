package app.surrealar.ui.toolbar.report

import app.surrealar.coords.ConvertedPosition
import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.status.CorrectionHealth
import app.surrealar.gnss.status.GnssStatusInterpreter
import app.surrealar.receiver.selection.LiveExternalSource
import app.surrealar.receiver.selection.LiveSourceKind
import app.surrealar.receiver.selection.LiveSourceState
import app.surrealar.receiver.selection.ReceiverSelectionState
import app.surrealar.receiver.selection.SourceTransition
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ExternalReceiverSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.ProfileOrigin
import app.surrealar.settings.model.VerticalReference
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures
import app.surrealar.ui.toolbar.ExternalLinkPhase
import app.surrealar.ui.toolbar.GnssToolbarStateMapper
import app.surrealar.ui.toolbar.LastFix
import app.surrealar.ui.toolbar.ToolbarFrame
import app.surrealar.ui.toolbar.ToolbarInputs
import app.surrealar.ui.toolbar.ToolbarLinkInputs
import app.surrealar.ui.toolbar.ToolbarLiveSource
import io.github.pennstatecs.geokit.gnss.model.Fix

/** Report fixtures: an RS4 Pro streaming RTK Fix, plus variants. */
internal object GnssReportFixtures {

    val receiver = ExternalReceiverSettings(ExternalReceiverProfile.REACH_RS4_PRO, ExternalConnectionType.TCP,
        "192.168.42.1", 9001, "Base RS4 Pro")

    val selection: ReceiverSelectionState = ReceiverUiFixtures.connected(ExternalReceiverProfile.REACH_RS4_PRO,
        ProfileOrigin.RECEIVER, ReceiverUiFixtures.rs4ProIdentity())

    fun live(fix: Fix?) = LiveSourceState(LiveSourceKind.EXTERNAL, fix,
        LiveExternalSource(ReceiverUiFixtures.TARGET, "Base RS4 Pro", ReceiverUiFixtures.rs4ProIdentity()), null)

    fun grid(fix: Fix) = ConvertedPosition(
        horizontal = HorizontalSystem.STATE_PLANE, datum = OutputDatum.NAD83_2011, unit = LinearUnit.US_SURVEY_FEET,
        latDeg = fix.latDeg, lonDeg = fix.lonDeg, easting = 2_442_754.19, northing = 435_037.47, zoneLabel = "SPCS 3701",
        vertical = VerticalReference.NAVD88_GEOID18, height = 1_286.74, verticalLabel = "NAVD88",
    )

    /** A frame as the toolbar pipeline would produce it. */
    fun frame(
        fix: Fix? = ReceiverUiFixtures.fix(),
        corr: CorrectionHealth = CorrectionHealth.OK,
        source: LocationSourceType = LocationSourceType.EXTERNAL,
        coords: ConvertedPosition? = fix?.let { grid(it) },
        live: ToolbarLiveSource = ToolbarLiveSource(LiveSourceKind.EXTERNAL, null),
        last: LastFix? = null,
        replay: Boolean = false,
        ref: GnssStatusInterpreter.Reference? = null,
    ): ToolbarFrame {
        val inputs = ToolbarInputs(source, fix, ReceiverUiFixtures.sky(18, 24), CoordinateSystemSettings(), "RS4 Pro",
            link = ToolbarLinkInputs(if (fix != null) ExternalLinkPhase.CONNECTED else ExternalLinkPhase.CONNECTING, lastRawNmeaAtMs = 0L),
            live = live, replayActive = replay, reference = ref)
        val result = GnssToolbarStateMapper.map(source, fix, inputs.sky, "RS4 Pro", coords, inputs.link, last, 500L,
            replayActive = replay, correctionHealth = corr, reference = ref, live = live)
        return ToolbarFrame(inputs, coords, result)
    }

    fun inputs(frame: ToolbarFrame? = frame(), live: LiveSourceState = live(frame?.inputs?.fix)) = GnssReportInputs(
        frame = frame, selection = selection, live = live, receiver = receiver,
        battery = io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery(82, true),
        corrections = io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections(isReceiving = true, channel = "NTRIP", stationId = "0042", baselineKm = 3.4),
        diag = app.surrealar.gnss.diagnostics.DiagnosticData(linesPerSecond = 12.0, totalLinesProcessed = 4_200, totalParseErrors = 0),
    )

    val switching = LiveSourceState(LiveSourceKind.NONE, null, null, SourceTransition.Switching(ReceiverUiFixtures.TARGET))

}
