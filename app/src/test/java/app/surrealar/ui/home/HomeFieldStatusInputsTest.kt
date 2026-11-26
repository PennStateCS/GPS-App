package app.surrealar.ui.home

import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.diagnostics.DiagnosticData
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.receiver.selection.ConnectionAttempt
import app.surrealar.receiver.selection.ReceiverSelectionState
import app.surrealar.receiver.selection.ReceiverTarget
import app.surrealar.receiver.tilt.EpochReference
import app.surrealar.receiver.tilt.TiltStatus
import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ExternalReceiverSettings
import app.surrealar.ui.home.HomeFieldStatusMapper.State
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.model.FixSnapshot
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home Field Status integration path (selection core state + live fix + tilt status → inputs → mapper) with real
 * [ReceiverSelectionState] / [TiltStatus] values: startup, switching, reconnecting, receiver-reported identity and the
 * RS4 position reference.
 */
class HomeFieldStatusInputsTest {

    private val target = ReceiverUiFixtures.TARGET
    private val rs4Pro = ExternalReceiverSettings(ExternalReceiverProfile.REACH_RS4_PRO, ExternalConnectionType.TCP,
        target.host, target.port, "Base RS4 Pro")

    private fun snapshot(lat: Double? = 41.25, rtk: String = "FIX", hAcc: Double? = 0.012, vAcc: Double? = 0.02) = FixSnapshot(
        timestampMillis = 1_000L, timestampSource = TimestampSource.DEVICE, lat = lat, lon = lat?.let { -75.88 },
        altMsl = 180.0, geoidSeparation = -34.0, altEllipsoidal = 146.0, speedMps = null, courseDeg = null,
        satsUsed = 18, hdop = 0.7, vDop = 1.1, pDop = 1.3, satellitesInView = 24, horizontalAccuracyM = hAcc,
        verticalAccuracyM = vAcc, correctionAgeS = 1.0, correctionStationId = null, multipathIndex = null,
        rtkStatus = rtk, stdLatM = null, stdLonM = null, stdAltM = null,
    )

    private fun status(
        snap: FixSnapshot = snapshot(),
        provider: Provider? = Provider.EXTERNAL_TCP,
        source: LocationSourceType = LocationSourceType.EXTERNAL,
        receiver: ExternalReceiverSettings = rs4Pro,
        conn: ReceiverSelectionState? = ReceiverUiFixtures.connected(ExternalReceiverProfile.REACH_RS4_PRO),
        tilt: TiltStatus? = null,
        recentErrors: Long = 0,
    ) = HomeFieldStatusMapper.map(HomeFieldStatusInputs.build(
        snap, provider, source, receiver, DiagnosticData(linesPerSecond = 10.0, totalParseErrors = 500), SkySnapshot(),
        conn, tilt, recentErrors,
    ))

    @Test fun `receiver-reported name and model are shown for the live receiver at the saved host`() {
        val conn = ReceiverUiFixtures.connected(ExternalReceiverProfile.REACH_RS4_PRO, identity = ReceiverUiFixtures.rs4ProIdentity())
        val s = status(conn = conn)
        assertEquals("Reach RS4 Pro base · Emlid Reach RS4 Pro", s.receiverDetail)
        assertEquals(State.HIGH_ACCURACY, s.state)
    }

    @Test fun `identity from another host is ignored - the saved name is shown`() {
        val conn = ReceiverUiFixtures.connected(identity = ReceiverUiFixtures.rs4ProIdentity().copy(host = "192.168.42.7"))
        assertEquals("Base RS4 Pro", status(conn = conn).receiverDetail)
    }

    @Test fun `startup - external selected, adapter not running, no fix - no receiver data, saved receiver only selected`() {
        val conn = ReceiverSelectionState(selectedSource = LocationSourceType.EXTERNAL, savedTarget = target,
            activeProvider = ProviderChoice.INTERNAL, link = null)
        val s = status(snap = snapshot(lat = null), provider = null, conn = conn)
        assertEquals(State.NO_RECEIVER_DATA, s.state)
        assertEquals("Selected: Base RS4 Pro", s.receiverDetail)
    }

    @Test fun `startup - tablet fix live while the saved receiver is tested - connecting, no tablet metrics`() {
        val conn = ReceiverSelectionState(selectedSource = LocationSourceType.EXTERNAL, savedTarget = target,
            activeProvider = ProviderChoice.INTERNAL, attempt = ConnectionAttempt.Testing(1, target, null),
            identity = ReceiverUiFixtures.rs4ProIdentity())
        val s = status(snap = snapshot(rtk = "SINGLE", hAcc = 4.0), provider = Provider.INTERNAL, conn = conn)
        assertEquals(State.CONNECTING, s.state)
        assertEquals(listOf("No fix"), s.chips.map { it.label })
        assertFalse(s.receiverDetail.contains("Emlid"))
    }

    @Test fun `testing ANOTHER receiver while the saved one is down - not shown as connecting to the saved one`() {
        val conn = ReceiverSelectionState(selectedSource = LocationSourceType.EXTERNAL, savedTarget = target,
            activeProvider = ProviderChoice.EXTERNAL_TCP, link = ConnectionState.ERROR,
            attempt = ConnectionAttempt.Testing(2, ReceiverTarget("192.168.42.7", 9001), null))
        assertEquals(State.NO_RECEIVER_DATA, status(snap = snapshot(lat = null), provider = null, conn = conn).state)
    }

    @Test fun `reconnecting - fixes stopped, link retrying`() {
        val conn = ReceiverUiFixtures.connected().copy(link = ConnectionState.RECONNECTING)
        assertEquals(State.RECONNECTING, status(snap = snapshot(lat = null), provider = null, conn = conn).state)
    }

    @Test fun `switch to tablet GNSS - a lingering external fix and its identity are not shown`() {
        val conn = ReceiverSelectionState(identity = ReceiverUiFixtures.rs4ProIdentity())
        val s = status(source = LocationSourceType.INTERNAL, conn = conn)
        assertEquals(State.WAITING, s.state)
        assertEquals("Tablet GNSS receiver", s.receiverDetail)
    }

    @Test fun `RS4 with unknown compensated output and no classification - reference unconfirmed headline and chip`() {
        val tilt = TiltStatus.NONE.copy(applicable = true, profile = ExternalReceiverProfile.REACH_RS4_PRO,
            compensatedOutput = CompensatedOutputSetting.UNKNOWN, reference = null)
        val s = status(tilt = tilt)
        assertEquals(State.REFERENCE_UNCONFIRMED, s.state)
        assertEquals("Reference unconfirmed", s.chips.first().label)
    }

    @Test fun `RS4 may-be-compensated epoch - warning`() {
        val tilt = TiltStatus.NONE.copy(applicable = true, compensatedOutput = CompensatedOutputSetting.ENABLED,
            reference = EpochReference.MAY_BE_COMPENSATED)
        val s = status(tilt = tilt)
        assertEquals(State.REFERENCE_UNCONFIRMED, s.state)
        assertEquals(HomeFieldStatusMapper.Severity.WARNING, s.severity)
    }

    @Test fun `tilt status is ignored for tablet GNSS`() {
        val tilt = TiltStatus.NONE.copy(applicable = true, reference = EpochReference.MAY_BE_COMPENSATED)
        val s = status(snap = snapshot(rtk = "SINGLE", hAcc = 3.0), provider = Provider.INTERNAL,
            source = LocationSourceType.INTERNAL, conn = ReceiverSelectionState(), tilt = tilt)
        assertEquals(State.INTERNAL_GPS, s.state)
        assertTrue(s.chips.none { it.label.contains("compensated") })
    }

    @Test fun `receiver vertical 999 m marker - vertical invalid, never green`() {
        val s = status(snap = snapshot(vAcc = 999.0))
        assertEquals(State.VERTICAL_INVALID, s.state)
        assertFalse(s.primaryDetail.contains("999"))
    }

    @Test fun `errors chip uses the recent window, not the cumulative total`() {
        val s = status(recentErrors = 2)
        assertTrue(s.chips.any { it.label == "Errors 2 in 1 min" })
        assertTrue(s.chips.none { it.label.contains("500") })
        assertTrue(status(recentErrors = 0).chips.none { it.label.startsWith("Errors") })
    }

    // ── With the receiver-selection core's live-source attribution (stabilization item 4) ──────────────────

    private fun statusWithLive(live: app.surrealar.receiver.selection.LiveSourceState,
                               conn: ReceiverSelectionState? = ReceiverUiFixtures.connected(ExternalReceiverProfile.REACH_RS4_PRO,
                                   identity = ReceiverUiFixtures.rs4ProIdentity())) =
        HomeFieldStatusMapper.map(HomeFieldStatusInputs.build(
            snapshot(), Provider.EXTERNAL_TCP, LocationSourceType.EXTERNAL, rs4Pro,
            DiagnosticData(linesPerSecond = 10.0, totalParseErrors = 0), SkySnapshot(), conn, null, 0, liveSource = live))

    @Test fun `core attributes the fix to the connected receiver - identity and High accuracy shown`() {
        val live = app.surrealar.receiver.selection.LiveSourceState(
            kind = app.surrealar.receiver.selection.LiveSourceKind.EXTERNAL,
            external = app.surrealar.receiver.selection.LiveExternalSource(target, "Base RS4 Pro", ReceiverUiFixtures.rs4ProIdentity()))
        val s = statusWithLive(live)
        assertEquals(State.HIGH_ACCURACY, s.state)
        assertEquals("Reach RS4 Pro base · Emlid Reach RS4 Pro", s.receiverDetail)
    }

    @Test fun `switching A to B - an external fix the core does not attribute (A's last fix) is not shown as B`() {
        // Provider is external and the link reads connected, but the core fenced the fix (it predates B's connection).
        val live = app.surrealar.receiver.selection.LiveSourceState(kind = app.surrealar.receiver.selection.LiveSourceKind.NONE)
        val s = statusWithLive(live)
        assertFalse(s.state == State.HIGH_ACCURACY)
        assertFalse("no receiver-reported identity attached", s.receiverDetail.contains("Emlid Reach RS4 Pro"))
    }
}
