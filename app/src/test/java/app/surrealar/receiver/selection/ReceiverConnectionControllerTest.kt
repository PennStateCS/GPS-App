package app.surrealar.receiver.selection

import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.ExternalReconnectable
import app.surrealar.gnss.source.GnssSourceCoordinator
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ProfileOrigin
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.receiver.DiscoveredReceiver
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDiscovery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 receiver selection core on plain JVM: test before switch, attempts, Emlid-only HTTP probing, device GNSS
 * switches, reconnect, capture confirmation, identification and the profile-mismatch rules. A real
 * [GnssSourceCoordinator] over a real [SourceSettings]; the probes and settings are fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverConnectionControllerTest {

    private val settings = FakeReceiverSettings()
    private val provider = MutableStateFlow(ProviderChoice.INTERNAL)
    private val sourceSettings = SourceSettings(provider, MutableStateFlow(emptyList()), MutableStateFlow(null))
    private val reconnects = mutableListOf<String>()
    private val coordinator = GnssSourceCoordinator(sourceSettings, settings,
        object : ExternalReconnectable { override fun reconnectNow(reason: String) { reconnects += reason } })
    private val link = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val deviceInfo = MutableStateFlow<ReceiverDeviceInfo?>(null)
    private val capture = MutableStateFlow(false)

    /** Probe fake: each TCP probe waits for its result; HTTP answers [httpAnswer] at once. */
    private inner class FakeProbe : ReceiverProbe {
        val tcpResults = ArrayDeque<CompletableDeferred<TcpProbeResult>>()
        var tcpCalls = 0
        var httpCalls = 0
        var httpAnswer = false
        /** Ignore cancellation while waiting (a blocking socket read): only the attempt guards can stop it. */
        var ignoreCancellation = false
        fun next(): CompletableDeferred<TcpProbeResult> = CompletableDeferred<TcpProbeResult>().also { tcpResults.addLast(it) }
        override suspend fun tcp(host: String, port: Int): TcpProbeResult {
            tcpCalls++
            val d = tcpResults.removeFirstOrNull() ?: CompletableDeferred(TcpProbeResult.RECEIVING_NMEA)
            return if (ignoreCancellation) withContext(NonCancellable) { d.await() } else d.await()
        }
        override suspend fun http(host: String): Boolean { httpCalls++; return httpAnswer }
    }

    private val probe = FakeProbe()

    /** ReceiverDeviceManager.restartDevice calls (the fake does not clear [deviceInfo]: a slow restart). */
    private var restarts = 0

    private fun TestScope.newController(discovery: ReceiverDiscovery? = null) = ReceiverConnectionController(
        settings = settings, coordinator = coordinator, scope = backgroundScope, probe = probe,
        activeProvider = sourceSettings.activeProvider, externalLink = link, deviceInfo = deviceInfo,
        captureActive = capture, discovery = discovery, clockMs = { 1_000L }, restartDevice = { restarts++ },
    )

    private fun TestScope.controller(discovery: ReceiverDiscovery? = null) = newController(discovery).also { runCurrent() }

    /** A working receiver P (192.168.42.1:9001) saved and active. */
    private fun TestScope.workingExternal() {
        settings.source.value = LocationSourceType.EXTERNAL
        settings.connType.value = ExternalConnectionType.TCP
        settings.host.value = "192.168.42.1"
        settings.port.value = 9001
        coordinator.activateExternalTcpProvider("192.168.42.1", 9001, "test setup")
        link.value = ConnectionState.CONNECTED
        runCurrent()
    }

    private fun assertWorkingExternalUnchanged() {
        assertEquals(emptyList<String>(), settings.writes.toList())
        assertEquals(ProviderChoice.EXTERNAL_TCP, provider.value)
        assertEquals("192.168.42.1", settings.host.value)
        assertEquals(9001, settings.port.value)
        assertEquals(LocationSourceType.EXTERNAL, settings.source.value)
        assertEquals("no reconnect to another target", emptyList<String>(), reconnects)
    }

    // ── test before switch ────────────────────────────────────────────────────

    @Test fun `a failed probe leaves the saved receiver and the active provider unchanged`() = runTest {
        workingExternal()
        val c = controller()
        val r = probe.next()
        assertEquals(ConnectStart.STARTED, c.connect(ConnectRequest("192.168.42.7", 9001)))
        assertTrue(c.state.value.attempt is ConnectionAttempt.Testing)
        r.complete(TcpProbeResult.CONNECT_FAILED)
        runCurrent()

        val a = c.state.value.attempt as ConnectionAttempt.Failed
        assertEquals(ConnectError.Unreachable(9001), a.error)
        assertWorkingExternalUnchanged()
        assertEquals(0, settings.recentWrites)
    }

    @Test fun `an HTTP-only answer is a specific error and changes nothing`() = runTest {
        workingExternal()
        probe.httpAnswer = true
        val c = controller()
        probe.next().complete(TcpProbeResult.CONNECT_FAILED)
        c.connect(ConnectRequest("192.168.42.7", 9001))
        runCurrent()

        val a = c.state.value.attempt as ConnectionAttempt.Failed
        assertEquals(ConnectError.HttpOnly(9001), a.error)
        assertEquals("Receiver found but NMEA stream not available on port 9001.", a.error.message)
        assertWorkingExternalUnchanged()
    }

    @Test fun `no data and a non-NMEA stream are failures that change nothing`() = runTest {
        workingExternal()
        val c = controller()
        probe.next().complete(TcpProbeResult.CONNECTED_NO_DATA)
        c.connect(ConnectRequest("192.168.42.7", 9001))
        runCurrent()
        assertEquals(ConnectError.NoData(9001), (c.state.value.attempt as ConnectionAttempt.Failed).error)

        probe.next().complete(TcpProbeResult.RECEIVING_RTCM_OR_BIN)
        c.connect(ConnectRequest("192.168.42.7", 9002))
        runCurrent()
        assertEquals(ConnectError.NotNmea(9002), (c.state.value.attempt as ConnectionAttempt.Failed).error)
        assertWorkingExternalUnchanged()
    }

    @Test fun `success saves in order, then activates, and remembers the receiver`() = runTest {
        val c = controller()
        val providerAtWrite = mutableListOf<ProviderChoice>()
        settings.onWrite = { providerAtWrite += provider.value }
        assertEquals(ConnectStart.STARTED, c.connect(ConnectRequest("192.168.42.7", 9001, name = "Base")))
        runCurrent()

        assertEquals(listOf("connType=TCP", "tcp=192.168.42.7:9001", "source=EXTERNAL"), settings.writes.toList())
        assertEquals("every write happened before activation", List(3) { ProviderChoice.INTERNAL }, providerAtWrite)
        assertEquals(ProviderChoice.EXTERNAL_TCP, provider.value)
        val a = c.state.value.attempt as ConnectionAttempt.Succeeded
        assertEquals(ReceiverTarget("192.168.42.7", 9001), a.target)
        assertEquals("Base", settings.name.value)
        val recent = settings.recent.value.single()
        assertEquals("192.168.42.7", recent.host)
        assertEquals(9001, recent.port)
        assertEquals(ExternalReceiverProfile.DEFAULT.prefKey, recent.profileKey)
        assertEquals("Base", recent.name)
        assertEquals(1_000L, recent.lastConnectedMs)
    }

    @Test fun `a profile picked for the connection is saved as the user's before the source`() = runTest {
        val c = controller()
        c.connect(ConnectRequest("10.0.0.5", 9000, profile = ExternalReceiverProfile.GENERIC_NMEA_TCP))
        runCurrent()
        assertEquals(listOf("connType=TCP", "tcp=10.0.0.5:9000", "profile=generic_nmea_tcp/user", "source=EXTERNAL"),
            settings.writes.toList())
        assertEquals(ProviderChoice.EXTERNAL_TCP, provider.value)
    }

    @Test fun `switching to another receiver while external reconnects to the new target`() = runTest {
        workingExternal()
        val c = controller()
        c.connect(ConnectRequest("192.168.42.7", 9001))
        runCurrent()
        assertEquals(listOf("receiver changed to 192.168.42.7:9001"), reconnects)
        assertEquals(ProviderChoice.EXTERNAL_TCP, provider.value)
    }

    // ── attempts ──────────────────────────────────────────────────────────────

    @Test fun `duplicate taps for the target being tested are ignored`() = runTest {
        val c = controller()
        val r = probe.next()
        assertEquals(ConnectStart.STARTED, c.connect(ConnectRequest("192.168.42.7", 9001)))
        assertEquals(ConnectStart.DUPLICATE_IGNORED, c.connect(ConnectRequest(" 192.168.42.7 ", 9001)))
        assertEquals(ConnectStart.DUPLICATE_IGNORED, c.connect(ConnectRequest("192.168.42.7", 9001)))
        runCurrent()
        r.complete(TcpProbeResult.RECEIVING_NMEA)
        runCurrent()
        assertEquals(1, probe.tcpCalls)
        assertEquals(1, settings.writes.count { it == "source=EXTERNAL" })
    }

    @Test fun `a superseded attempt never saves or activates, even when its probe finishes`() = runTest {
        probe.ignoreCancellation = true
        val c = controller()
        val first = probe.next()
        val second = probe.next()
        c.connect(ConnectRequest("192.168.42.7", 9001))
        runCurrent()
        c.connect(ConnectRequest("192.168.42.8", 9001))      // supersedes the first
        runCurrent()
        first.complete(TcpProbeResult.RECEIVING_NMEA)         // the old probe still delivers NMEA
        runCurrent()
        assertEquals("nothing saved for the superseded attempt", emptyList<String>(), settings.writes.toList())
        assertEquals(ProviderChoice.INTERNAL, provider.value)

        second.complete(TcpProbeResult.CONNECT_FAILED)
        runCurrent()
        assertEquals(emptyList<String>(), settings.writes.toList())
        assertEquals(ProviderChoice.INTERNAL, provider.value)
        assertTrue(c.state.value.attempt is ConnectionAttempt.Failed)
    }

    @Test fun `selecting device GNSS during a probe cancels it and External is never activated`() = runTest {
        probe.ignoreCancellation = true
        settings.source.value = LocationSourceType.EXTERNAL
        val c = controller()
        val r = probe.next()
        c.connect(ConnectRequest("192.168.42.7", 9001))
        runCurrent()
        c.selectInternal("settings")
        assertEquals("device GNSS active at once", ProviderChoice.INTERNAL, provider.value)
        r.complete(TcpProbeResult.RECEIVING_NMEA)
        runCurrent()

        assertEquals(ProviderChoice.INTERNAL, provider.value)
        assertEquals(listOf("mock=false", "source=INTERNAL"), settings.writes.toList())
        assertEquals(ConnectionAttempt.Idle, c.state.value.attempt)
    }

    @Test fun `disconnect switches to device GNSS and clears the saved receiver`() = runTest {
        workingExternal()
        val c = controller()
        c.disconnect()
        assertEquals(ProviderChoice.INTERNAL, provider.value)
        runCurrent()
        assertEquals(listOf("mock=false", "source=INTERNAL", "clearTcp"), settings.writes.toList())
        assertNull(settings.host.value)
    }

    @Test fun `external to device GNSS and back`() = runTest {
        workingExternal()
        val c = controller()
        c.selectInternal()
        runCurrent()
        assertEquals(ProviderChoice.INTERNAL, provider.value)
        assertEquals(LocationSourceType.INTERNAL, settings.source.value)
        assertEquals("device GNSS selected: nothing saved to reconnect", ConnectStart.NOTHING_SAVED, c.reconnectSaved())

        assertEquals(ConnectStart.STARTED, c.connect(ConnectRequest("192.168.42.1", 9001)))
        runCurrent()
        assertEquals(ProviderChoice.EXTERNAL_TCP, provider.value)
        assertEquals(LocationSourceType.EXTERNAL, settings.source.value)
        assertTrue(c.state.value.attempt is ConnectionAttempt.Succeeded)
    }

    @Test fun `reconnectSaved tests the saved receiver, and is a no-op without one`() = runTest {
        val c = controller()
        assertEquals(ConnectStart.NOTHING_SAVED, c.reconnectSaved())

        settings.source.value = LocationSourceType.EXTERNAL
        settings.host.value = "192.168.42.1"
        settings.port.value = 9001
        runCurrent()
        assertEquals(ConnectStart.STARTED, c.reconnectSaved())
        runCurrent()
        assertEquals(ProviderChoice.EXTERNAL_TCP, provider.value)
        assertEquals(1, probe.tcpCalls)

        // Streaming from that receiver now: nothing to test or switch.
        link.value = ConnectionState.CONNECTED
        runCurrent()
        assertEquals(ConnectStart.ALREADY_ACTIVE, c.reconnectSaved())
        assertEquals(1, probe.tcpCalls)
    }

    // ── Emlid-only HTTP ───────────────────────────────────────────────────────

    @Test fun `a Generic NMEA receiver gets no HTTP probe`() = runTest {
        settings.profile.value = ExternalReceiverProfile.GENERIC_NMEA_TCP
        val c = controller()
        probe.next().complete(TcpProbeResult.CONNECT_FAILED)
        c.connect(ConnectRequest("10.0.0.5", 9000))
        runCurrent()
        assertEquals(0, probe.httpCalls)
        assertEquals(ConnectError.Unreachable(9000), (c.state.value.attempt as ConnectionAttempt.Failed).error)

        // Also when Generic is picked for the connection while an Emlid profile is stored.
        settings.profile.value = ExternalReceiverProfile.REACH_RS4
        c.connect(ConnectRequest("10.0.0.6", 9000, profile = ExternalReceiverProfile.GENERIC_NMEA_TCP))
        runCurrent()
        assertEquals(0, probe.httpCalls)
    }

    @Test fun `an Emlid profile asks the HTTP API alongside the NMEA sniff`() = runTest {
        settings.profile.value = ExternalReceiverProfile.REACH_RS4
        val c = controller()
        c.connect(ConnectRequest("192.168.42.7", 9001))
        runCurrent()
        assertEquals(1, probe.httpCalls)
    }

    // ── capture safety ────────────────────────────────────────────────────────

    @Test fun `switching receivers during a capture needs confirmation and starts nothing until confirmed`() = runTest {
        workingExternal()
        capture.value = true
        val c = controller()
        assertEquals(ConnectStart.NEEDS_CAPTURE_CONFIRMATION, c.connect(ConnectRequest("192.168.42.7", 9001)))
        assertEquals(0, probe.tcpCalls)
        assertEquals(ConnectionAttempt.Idle, c.state.value.attempt)
        assertEquals("the active receiver itself needs no confirmation", ConnectStart.ALREADY_ACTIVE,
            c.connect(ConnectRequest("192.168.42.1", 9001)))

        assertEquals(ConnectStart.STARTED, c.connect(ConnectRequest("192.168.42.7", 9001), confirmCaptureStop = true))
        runCurrent()
        assertEquals("tcp=192.168.42.7:9001", settings.writes[1])
    }

    // ── identification and the profile ────────────────────────────────────────

    private fun info(model: String?, host: String = "192.168.42.1", serial: String? = "SN1") =
        ReceiverDeviceInfo(host = host, name = "reach-base", model = model, firmware = "34.10", serial = serial)

    @Test fun `a reported model replaces a default profile, never the host or port`() = runTest {
        workingExternal()
        settings.origin.value = ProfileOrigin.DEFAULT
        val c = controller()
        val events = mutableListOf<ReceiverSelectionEvent>()
        backgroundScope.launchCollect(c, events)
        deviceInfo.value = info("Reach RS4")
        runCurrent()

        assertEquals(ExternalReceiverProfile.REACH_RS4, settings.profile.value)
        assertEquals(ProfileOrigin.RECEIVER, settings.origin.value)
        assertEquals(listOf("profile=reach_rs4/receiver"), settings.writes.filter { it.startsWith("profile=") })
        // Phase 5 fix: the receiver's own name also fills the blank saved name (host/port unchanged).
        assertEquals(listOf("tcp=192.168.42.1:9001"), settings.writes.filter { it.startsWith("tcp=") })
        assertEquals("reach-base", settings.name.value)
        assertEquals("192.168.42.1", settings.host.value)
        assertEquals(9001, settings.port.value)
        assertEquals(ReceiverSelectionEvent.ProfileAutoApplied(ExternalReceiverProfile.REACH_RS2_PLUS, ExternalReceiverProfile.REACH_RS4, "Reach RS4"),
            events.single())
        assertNull(c.state.value.mismatch)
        val id = c.state.value.identity!!
        assertTrue(id.modelVerified)
        // The recent entry carries what the receiver reported.
        val recent = settings.recent.value.single()
        assertEquals("Reach RS4", recent.model)
        assertEquals("SN1", recent.serial)
        assertEquals("34.10", recent.firmware)
    }

    @Test fun `a user-chosen profile is never replaced and a mismatch is shown instead`() = runTest {
        workingExternal()
        settings.origin.value = ProfileOrigin.USER
        val c = controller()
        deviceInfo.value = info("Reach RS4 Pro")
        runCurrent()

        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, settings.profile.value)
        assertTrue(settings.writes.none { it.startsWith("profile=") })
        val m = c.state.value.mismatch!!
        assertEquals(ExternalReceiverProfile.REACH_RS4_PRO, m.reportedProfile)
        assertEquals(MismatchReason.PROFILE_SET_BY_USER, m.reason)

        // "Keep": the profile stays, marked as the user's; the banner goes.
        c.keepCurrentProfile()
        runCurrent()
        assertNull(c.state.value.mismatch)
        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, settings.profile.value)
    }

    @Test fun `use the reported profile from the banner`() = runTest {
        workingExternal()
        settings.origin.value = ProfileOrigin.USER
        val c = controller()
        deviceInfo.value = info("Reach RS4 Pro")
        runCurrent()
        assertEquals(ApplyProfileResult.APPLIED, c.applyReportedProfile())
        runCurrent()
        assertEquals(ExternalReceiverProfile.REACH_RS4_PRO, settings.profile.value)
        assertEquals(ProfileOrigin.RECEIVER, settings.origin.value)
        assertNull(c.state.value.mismatch)
    }

    @Test fun `no automatic profile change during a capture`() = runTest {
        workingExternal()
        settings.origin.value = ProfileOrigin.DEFAULT
        capture.value = true
        val c = controller()
        deviceInfo.value = info("Reach RS4")
        runCurrent()

        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, settings.profile.value)
        assertEquals(MismatchReason.CAPTURE_ACTIVE, c.state.value.mismatch!!.reason)
        assertEquals(ApplyProfileResult.NEEDS_CAPTURE_CONFIRMATION, c.applyReportedProfile())
        runCurrent()
        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, settings.profile.value)

        // Capture over: the default profile is now replaced.
        capture.value = false
        runCurrent()
        assertEquals(ExternalReceiverProfile.REACH_RS4, settings.profile.value)
    }

    @Test fun `unknown, ambiguous or stale reports keep the profile`() = runTest {
        workingExternal()
        settings.origin.value = ProfileOrigin.DEFAULT
        val c = controller()
        for (i in listOf(info("Reach RS2"), info("Reach RS3"), info(null), info("Reach RS4", host = "192.168.42.99"))) {
            deviceInfo.value = i
            runCurrent()
            assertNull(c.state.value.mismatch)
        }
        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, settings.profile.value)
        assertTrue(settings.writes.none { it.startsWith("profile=") })
        assertNull("info for another host is not this receiver's identity", c.state.value.identity)

        // Device GNSS active: a report (left over) identifies nothing.
        provider.value = ProviderChoice.INTERNAL
        deviceInfo.value = info("Reach RS4")
        runCurrent()
        assertNull(c.state.value.identity)
        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, settings.profile.value)
    }

    private fun CoroutineScope.launchCollect(c: ReceiverConnectionController, into: MutableList<ReceiverSelectionEvent>) =
        launch(start = CoroutineStart.UNDISPATCHED) { c.events.collect { into += it } }

    // ── test(): probe only, no switch (manual dialog "Test connection") ──────────

    @Test fun `test reports NMEA without saving, switching or changing the attempt state`() = runTest {
        workingExternal()
        val c = controller()
        val r = c.test(ConnectRequest("192.168.42.7", 9002, profile = ExternalReceiverProfile.REACH_RS4))
        assertEquals(ReceiverTestResult.Reachable(ReceiverTarget("192.168.42.7", 9002), httpReachable = false), r)
        assertEquals("the Emlid profile asked HTTP once", 1, probe.httpCalls)
        assertEquals(ConnectionAttempt.Idle, c.state.value.attempt)
        assertWorkingExternalUnchanged()
        assertEquals(0, settings.recentWrites)
    }

    @Test fun `test failures are classified like connect and change nothing`() = runTest {
        workingExternal()
        probe.httpAnswer = true
        val c = controller()
        probe.next().complete(TcpProbeResult.CONNECT_FAILED)
        val r = c.test(ConnectRequest("192.168.42.7", 9002, profile = ExternalReceiverProfile.REACH_RS2_PLUS))
        assertEquals(ReceiverTestResult.Failed(ReceiverTarget("192.168.42.7", 9002), ConnectError.HttpOnly(9002)), r)
        assertWorkingExternalUnchanged()
    }

    @Test fun `test of a Generic NMEA receiver makes no Emlid request and invalid input is rejected`() = runTest {
        val c = controller()
        c.test(ConnectRequest("10.0.0.5", 9000, profile = ExternalReceiverProfile.GENERIC_NMEA_TCP))
        assertEquals(0, probe.httpCalls)
        assertEquals(1, probe.tcpCalls)
        assertEquals(ReceiverTestResult.Invalid, c.test(ConnectRequest(" ", 9000)))
        assertEquals(ReceiverTestResult.Invalid, c.test(ConnectRequest("10.0.0.5", 0)))
        assertEquals("invalid input never probes", 1, probe.tcpCalls)
        assertEquals(emptyList<String>(), settings.writes.toList())
        assertEquals(ProviderChoice.INTERNAL, provider.value)
    }

    // ── review fixes (M2–M5, L8, L9, saved name) ────────────────────────────────

    @Test fun `a capture that starts during the probe stops the switch until confirmed`() = runTest {
        workingExternal()
        val c = controller()
        val r = probe.next()
        assertEquals(ConnectStart.STARTED, c.connect(ConnectRequest("192.168.42.7", 9001)))
        runCurrent()
        capture.value = true                     // the user starts a capture while the test runs
        r.complete(TcpProbeResult.RECEIVING_NMEA)
        runCurrent()

        assertEquals(ConnectError.CaptureStarted, (c.state.value.attempt as ConnectionAttempt.Failed).error)
        assertWorkingExternalUnchanged()
        assertEquals(0, settings.recentWrites)

        assertEquals(ConnectStart.NEEDS_CAPTURE_CONFIRMATION, c.connect(ConnectRequest("192.168.42.7", 9001)))
        assertEquals(ConnectStart.STARTED, c.connect(ConnectRequest("192.168.42.7", 9001), confirmCaptureStop = true))
        runCurrent()
        assertEquals("192.168.42.7", settings.host.value)
        assertTrue(c.state.value.attempt is ConnectionAttempt.Succeeded)
    }

    @Test fun `connect reads live values right after construction`() = runTest {
        workingExternal()
        capture.value = true
        val c = newController()                  // no runCurrent: none of the state collectors has run yet
        assertEquals(ConnectStart.ALREADY_ACTIVE, c.connect(ConnectRequest("192.168.42.1", 9001)))
        assertEquals(ConnectStart.NEEDS_CAPTURE_CONFIRMATION, c.connect(ConnectRequest("192.168.42.7", 9001)))
        assertEquals(0, probe.tcpCalls)
    }

    @Test fun `the previous unit's report at the same address never auto-applies after a switch`() = runTest {
        settings.origin.value = ProfileOrigin.DEFAULT
        settings.host.value = "192.168.42.1"; settings.port.value = 9001
        // Unit A (an RS4) was identified at the hotspot address; its report is still on hand.
        deviceInfo.value = info("Reach RS4", serial = "SN-A")
        val c = controller()
        c.connect(ConnectRequest("192.168.42.1", 9001))     // unit B, same hotspot IP
        runCurrent()

        assertEquals(ProviderChoice.EXTERNAL_TCP, provider.value)
        assertEquals(1, restarts)
        assertNull("the old report identifies nothing", c.state.value.identity)
        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, settings.profile.value)
        assertTrue(settings.writes.none { it.startsWith("profile=") })

        // A link reconnect fences again; still the old report: nothing.
        link.value = ConnectionState.RECONNECTING; runCurrent()
        link.value = ConnectionState.CONNECTED; runCurrent()
        assertEquals(2, restarts)
        assertNull(c.state.value.identity)
        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, settings.profile.value)

        // B's own fresh report identifies it.
        deviceInfo.value = info("Reach RS2+", serial = "SN-B")
        runCurrent()
        assertEquals("SN-B", c.state.value.identity!!.serial)
        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, settings.profile.value)
    }

    @Test fun `a link reconnect waits for a fresh report before identifying`() = runTest {
        workingExternal()
        settings.origin.value = ProfileOrigin.USER
        deviceInfo.value = info("Reach RS4 Pro")
        val c = controller()
        assertTrue(c.state.value.mismatch != null)
        link.value = ConnectionState.RECONNECTING; runCurrent()
        link.value = ConnectionState.CONNECTED; runCurrent()
        assertNull(c.state.value.identity)
        assertNull(c.state.value.mismatch)
        deviceInfo.value = null; runCurrent()             // the restarted device clears and polls again
        deviceInfo.value = info("Reach RS4 Pro"); runCurrent()
        assertEquals(ExternalReceiverProfile.REACH_RS4_PRO, c.state.value.mismatch!!.reportedProfile)
    }

    private class FakeDiscovery : ReceiverDiscovery {
        var calls = 0
        override suspend fun discover(timeoutMs: Long): List<DiscoveredReceiver> {
            calls++
            return listOf(DiscoveredReceiver("192.168.42.1", "reach-a", nmeaPort = 9001))
        }
    }

    @Test fun `the network scan runs only for an Emlid profile or when asked explicitly`() = runTest {
        settings.profile.value = ExternalReceiverProfile.GENERIC_NMEA_TCP
        val d = FakeDiscovery()
        val c = controller(d)
        assertEquals(false, c.scanNetwork())
        runCurrent()
        assertEquals("no implicit Emlid sweep for Generic NMEA", 0, d.calls)
        assertEquals(NetworkScanState.Idle, c.state.value.scan)

        assertEquals(true, c.scanNetwork(emlidScan = true))   // "Scan for Emlid receivers"
        runCurrent()
        assertEquals(1, d.calls)
        assertEquals(1, (c.state.value.scan as NetworkScanState.Done).results.size)

        settings.profile.value = ExternalReceiverProfile.REACH_RS4
        runCurrent()
        assertEquals(true, c.scanNetwork())
        runCurrent()
        assertEquals(2, d.calls)
    }

    @Test fun `a failed settings write leaves the previous receiver saved and active`() = runTest {
        workingExternal()
        settings.name.value = "Rover"
        val c = controller()
        settings.failNextCommit = true
        c.connect(ConnectRequest("192.168.42.7", 9002, profile = ExternalReceiverProfile.GENERIC_NMEA_TCP, name = "Other"))
        runCurrent()

        assertTrue((c.state.value.attempt as ConnectionAttempt.Failed).error is ConnectError.Unexpected)
        assertWorkingExternalUnchanged()
        assertEquals("Rover", settings.name.value)
        assertEquals(ExternalReceiverProfile.DEFAULT, settings.profile.value)
        assertEquals(0, settings.receiverCommits)
        assertEquals(0, settings.recentWrites)
    }

    @Test fun `the settings of a switch are saved in one transaction`() = runTest {
        val c = controller()
        c.connect(ConnectRequest("10.0.0.5", 9000, profile = ExternalReceiverProfile.GENERIC_NMEA_TCP))
        runCurrent()
        assertEquals(1, settings.receiverCommits)
    }

    @Test fun `a repeat connect while the new receiver is still connecting does not probe again`() = runTest {
        val c = controller()
        assertEquals(ConnectStart.STARTED, c.connect(ConnectRequest("192.168.42.7", 9001)))
        runCurrent()
        assertTrue(c.state.value.attempt is ConnectionAttempt.Succeeded)
        link.value = ConnectionState.CONNECTING
        runCurrent()
        assertEquals(ConnectStart.ALREADY_ACTIVE, c.connect(ConnectRequest("192.168.42.7", 9001)))
        assertEquals(ConnectStart.ALREADY_ACTIVE, c.reconnectSaved())
        assertEquals(1, probe.tcpCalls)
        assertEquals(1, settings.receiverCommits)
    }

    @Test fun `reconnectSaved of the active but dropped receiver makes the adapter reconnect`() = runTest {
        workingExternal()
        link.value = ConnectionState.RECONNECTING
        val c = controller()
        assertEquals(ConnectStart.STARTED, c.reconnectSaved())
        runCurrent()
        assertEquals(1, probe.tcpCalls)
        assertEquals(listOf("reconnect saved receiver #1"), reconnects)
        assertEquals(ProviderChoice.EXTERNAL_TCP, provider.value)

        // A plain connect to the same (active) target keeps the stream: no forced reconnect.
        link.value = ConnectionState.ERROR
        runCurrent()
        c.connect(ConnectRequest("192.168.42.1", 9001))
        runCurrent()
        assertEquals(1, reconnects.size)
    }

    @Test fun `a reconnect keeps the saved name and a reported name fills a blank one`() = runTest {
        workingExternal()
        settings.name.value = "Base"
        link.value = ConnectionState.RECONNECTING
        val c = controller()
        c.reconnectSaved()                                   // no name in the request
        runCurrent()
        assertEquals("Base", settings.name.value)

        // Another receiver without a name: the old name is not carried over.
        c.connect(ConnectRequest("192.168.42.7", 9001))
        runCurrent()
        assertEquals("", settings.name.value)
        // Its own (fresh) report fills the blank name ...
        deviceInfo.value = info("Reach RS2+", host = "192.168.42.7", serial = "SN7")
        runCurrent()
        assertEquals("reach-base", settings.name.value)
        // ... but never replaces one the user entered.
        settings.name.value = "My rover"
        deviceInfo.value = ReceiverDeviceInfo(host = "192.168.42.7", name = "other-name", model = "Reach RS2+", firmware = "34.10", serial = "SN7")
        runCurrent()
        assertEquals("My rover", settings.name.value)
    }
}
