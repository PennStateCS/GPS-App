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

    @Test fun `Home uses the toolbar's stabilised correction health - aging is never green (consistency, review M2)`() {
        val conn = ReceiverUiFixtures.connected(ExternalReceiverProfile.REACH_RS4_PRO, identity = ReceiverUiFixtures.rs4ProIdentity())
        val s = HomeFieldStatusMapper.map(HomeFieldStatusInputs.build(
            snapshot(hAcc = 0.012), Provider.EXTERNAL_TCP, LocationSourceType.EXTERNAL, rs4Pro,
            DiagnosticData(linesPerSecond = 10.0, totalParseErrors = 0), SkySnapshot(), conn, null, 0,
            toolbarCorrection = tc(app.surrealar.gnss.status.CorrectionHealth.AGING)))
        assertFalse("not High accuracy while corrections are aging", s.state == State.HIGH_ACCURACY)
        assertTrue(s.headline + " / " + s.additionalWarnings, (listOf(s.headline) + s.additionalWarnings).any { it.contains("aging") })
    }

    // ── Final stabilization: toolbar correction state on Home (recovering chip, stream matching) ──────────────

    private val rs4ProKey = app.surrealar.ui.toolbar.toolbarReceiverKey(rs4Pro)

    /** A toolbar correction state computed for the RS4 Pro stream (override tags to simulate a mismatch). */
    private fun tc(health: app.surrealar.gnss.status.CorrectionHealth, recovering: Boolean = false,
                   source: LocationSourceType = LocationSourceType.EXTERNAL, key: String = rs4ProKey,
                   provider: Provider? = Provider.EXTERNAL_TCP, liveExternal: Boolean = true) =
        HomeToolbarCorrection(health, recovering, source, key, provider, liveExternal)

    private fun statusWith(toolbar: HomeToolbarCorrection?, ageS: Double? = 1.0, provider: Provider? = Provider.EXTERNAL_TCP,
                           source: LocationSourceType = LocationSourceType.EXTERNAL) =
        HomeFieldStatusMapper.map(HomeFieldStatusInputs.build(
            snapshot(hAcc = 0.012).copy(correctionAgeS = ageS), provider, source, rs4Pro,
            DiagnosticData(linesPerSecond = 10.0, totalParseErrors = 0), SkySnapshot(),
            ReceiverUiFixtures.connected(ExternalReceiverProfile.REACH_RS4_PRO), null, 0, toolbarCorrection = toolbar))

    private fun rtkChip(s: HomeFieldStatusMapper.FieldStatus) = s.chips.firstOrNull { it.label.startsWith("RTK") }

    @Test fun `stale then recovered - the held warning shows with an RTK chip that says recovering (item 1)`() {
        val s = statusWith(tc(app.surrealar.gnss.status.CorrectionHealth.STALE, recovering = true), ageS = 1.0)
        assertEquals("Corrections stale", s.headline)
        assertEquals("RTK 1.0s · recovering", rtkChip(s)?.label)
        // Once the toolbar releases the warning: the plain live age, no warning.
        val after = statusWith(tc(app.surrealar.gnss.status.CorrectionHealth.OK), ageS = 1.0)
        assertFalse(after.headline.contains("stale"))
        assertEquals("RTK 1.0s", rtkChip(after)?.label)
    }

    @Test fun `a warning that is not merely held keeps the plain age chip (raw age still bad)`() {
        val s = statusWith(tc(app.surrealar.gnss.status.CorrectionHealth.AGING, recovering = false), ageS = 12.0)
        assertEquals("Corrections aging", s.headline)
        assertEquals("RTK 12s", rtkChip(s)?.label)   // ages ≥ 10 s print without a decimal
        assertEquals("RTK stale", rtkChip(statusWith(tc(app.surrealar.gnss.status.CorrectionHealth.STALE), ageS = 20.0))?.label)
    }

    @Test fun `receiver switch - a toolbar state computed for another receiver is ignored (item 2)`() {
        val other = tc(app.surrealar.gnss.status.CorrectionHealth.STALE, recovering = true, key = "reach_rs2_plus@192.168.42.7:9001")
        val s = statusWith(other, ageS = 1.0)
        assertFalse("the previous receiver's warning is not shown", s.headline.contains("stale"))
        assertEquals("RTK 1.0s", rtkChip(s)?.label)
    }

    @Test fun `internal - external transitions - state from the other source or provider is ignored`() {
        // Toolbar frame still from tablet GNSS while Home shows the external receiver.
        assertFalse(statusWith(tc(app.surrealar.gnss.status.CorrectionHealth.STALE, source = LocationSourceType.INTERNAL))
            .headline.contains("stale"))
        // Frame's fix from another provider (e.g. the tablet) while Home's live fix is external.
        assertFalse(statusWith(tc(app.surrealar.gnss.status.CorrectionHealth.STALE, provider = Provider.INTERNAL))
            .headline.contains("stale"))
        // Home itself on tablet GNSS: no external correction state at all.
        val internal = statusWith(tc(app.surrealar.gnss.status.CorrectionHealth.STALE), provider = Provider.INTERNAL,
            source = LocationSourceType.INTERNAL)
        assertFalse(internal.headline.contains("Corrections"))
    }

    @Test fun `disconnect - reconnect - a waiting or lost toolbar frame does not drive Home`() {
        val waiting = tc(app.surrealar.gnss.status.CorrectionHealth.STALE, liveExternal = false)
        assertFalse(statusWith(waiting).headline.contains("stale"))
        // Without a matching toolbar state Home keeps its raw rule: a genuinely old age is still stale.
        assertEquals("Corrections stale", statusWith(waiting, ageS = 20.0).headline)
    }

    @Test fun `missing or invalid correction age - no RTK chip, no recovering text`() {
        assertEquals(null, rtkChip(statusWith(tc(app.surrealar.gnss.status.CorrectionHealth.STALE, recovering = true), ageS = null)))
        assertEquals(null, rtkChip(statusWith(tc(app.surrealar.gnss.status.CorrectionHealth.OK), ageS = -1.0)))
    }

    @Test fun `Home and toolbar warnings are compatible - the same health gives the same headline`() {
        for (h in app.surrealar.gnss.status.CorrectionHealth.entries) {
            val s = statusWith(tc(h), ageS = if (h == app.surrealar.gnss.status.CorrectionHealth.UNAVAILABLE) null else 1.0)
            val expected = when (h) {
                app.surrealar.gnss.status.CorrectionHealth.OK -> null
                app.surrealar.gnss.status.CorrectionHealth.AGING -> "Corrections aging"
                app.surrealar.gnss.status.CorrectionHealth.STALE -> "Corrections stale"
                app.surrealar.gnss.status.CorrectionHealth.UNAVAILABLE -> "Corrections unavailable"
            }
            if (expected == null) assertFalse("$h: ${s.headline}", s.headline.startsWith("Corrections"))
            else assertEquals("$h", expected, s.headline)
        }
    }
}
