package app.surrealar.receiver.selection

import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.external.ExternalEndpointTracker
import io.github.pennstatecs.geokit.gnss.model.Fix
import app.surrealar.gnss.source.GnssSourceCoordinator
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.receiver.EmlidDiscovery
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ProfileOrigin
import app.surrealar.settings.model.RecentReceiver
import app.surrealar.settings.model.RecentReceiversCodec
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDiscovery
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverVendor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Receiver selection core (Phase 5): the one place that tests, switches, identifies and remembers external
 * receivers. App-scoped (a Hilt singleton built in ReceiverModule), so closing a screen or sheet never cancels an
 * attempt or disconnects anything. It reuses the existing pieces and adds no second connection path:
 * [GnssSourceCoordinator] flips the active provider, the external adapter (geokit TcpNmeaSource /
 * ReconnectingNmeaAdapter, untouched) owns the live socket, and ReceiverDeviceManager's device info is the only
 * identification source.
 *
 * ### Test before switch
 * [connect] probes the target first: a TCP NMEA sniff on its own short-lived socket, and the Emlid HTTP API in
 * parallel for Emlid profiles only (a Generic NMEA receiver gets no HTTP or Socket.IO request). Only when the sniff
 * receives NMEA are the settings saved (connection type → host/port[/name] → profile → selected source) and the
 * external provider activated, in the coordinator's documented order. Any other result ([ConnectError]) changes
 * nothing: the working receiver (or device GNSS) stays active and saved.
 *
 * ### Attempts
 * One attempt at a time. A second request for the target being tested is ignored; a request for another target
 * supersedes the running one. A superseded attempt never saves or activates anything: it is checked when it would
 * commit (under [lock]), and once committed it activates only if no switch to device GNSS came after it.
 * [selectInternal] / [disconnect] cancel the attempt and switch to device GNSS synchronously.
 *
 * ### Identity and profile
 * See [ReceiverIdentityRules]: the profile is changed automatically only when its origin is not USER, no capture is
 * running and the receiver reported a recognised model for the saved host; otherwise [ReceiverSelectionState.mismatch]
 * is set for a banner. Host and port are never changed by identification, and receiver features (tilt, compensated
 * output) are never inferred.
 *
 * Threading: [connect], [selectInternal], [disconnect] may be called from any thread (main in practice).
 */
class ReceiverConnectionController(
    private val settings: SettingsRepository,
    private val coordinator: GnssSourceCoordinator,
    /** App scope (outlives every screen). */
    private val scope: CoroutineScope,
    private val probe: ReceiverProbe = SocketReceiverProbe,
    /** SourceSettings.activeProvider. */
    private val activeProvider: StateFlow<ProviderChoice> = MutableStateFlow(ProviderChoice.INTERNAL),
    /** ExternalAdapter.connectionState (null: not observed). */
    private val externalLink: StateFlow<ReconnectingNmeaAdapter.ConnectionState>? = null,
    /** ReceiverDeviceManager.deviceInfo: the only identification source. */
    private val deviceInfo: StateFlow<ReceiverDeviceInfo?> = MutableStateFlow(null),
    /** ActiveCaptureTracker.active. */
    private val captureActive: StateFlow<Boolean> = MutableStateFlow(false),
    /** Network scan for the picker (Emlid discovery); null = none. */
    private val discovery: ReceiverDiscovery? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
    /** ReceiverDeviceManager.restartDevice: re-polls the device info after a switch / reconnect (null: none). */
    private val restartDevice: (() -> Unit)? = null,
    /** FixSwitchboard.currentFix: the live fix of the bound provider (for [liveSource]). */
    private val liveFix: StateFlow<Fix?> = MutableStateFlow(null),
    /** ExternalEndpointTracker.attempts: which endpoint the external adapter connected to (null: unknown). */
    private val externalEndpoint: StateFlow<ExternalEndpointTracker.Attempt>? = null,
) {
    private val _state = MutableStateFlow(ReceiverSelectionState())
    /** Everything receiver selection shows; see [ReceiverSelectionState]. */
    val state: StateFlow<ReceiverSelectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ReceiverSelectionEvent>(extraBufferCapacity = 8)
    /** One-off notices (automatic profile change) for a snackbar. */
    val events: SharedFlow<ReceiverSelectionEvent> = _events.asSharedFlow()

    private val _liveSource = MutableStateFlow(LiveSourceState())
    /**
     * The ACTUAL live positioning source (stabilization item 4), as opposed to the selected receiver in [state]: which
     * kind of source produced the current fix, the external receiver's identity only when the live fix came from
     * that connected receiver, and the transition in progress otherwise. See [LiveSourceState] / [LiveSourceRules].
     * Status UI (Home, toolbar, receiver card) should label the live fix from this, never from the saved receiver.
     */
    val liveSource: StateFlow<LiveSourceState> = _liveSource.asStateFlow()

    /** The fix that was current at the last fence (switch, link reconnect, endpoint read): never attributed. */
    @Volatile private var fencedFix: Fix? = null
    /** The endpoint the external adapter last connected to. */
    @Volatile private var connectedEndpoint: ReceiverTarget? = null
    @Volatile private var lastEndpointSeq = -1L
    private val liveLock = Any()

    /** Guards [attemptSeq], [internalSeq], [job] and every active-provider flip made here. */
    private val lock = Any()
    private var attemptSeq = 0
    /** [attemptSeq] value of the last switch to device GNSS (an attempt older than it must not activate). */
    private var internalSeq = 0
    /**
     * The most recent provider activation made here: its sequence number and whether it was External. A device GNSS
     * save queued behind a NEWER External commit must not overwrite that commit's saved source (item 6).
     */
    private var lastActivationSeq = 0
    private var lastActivationExternal = false
    private var job: Job? = null
    private var scanJob: Job? = null

    /** Serialises every settings write made here (commit of an attempt, device GNSS, profile changes). */
    private val switchLock = Mutex()

    /** Mismatches the user answered "Keep" (this process only). */
    private val dismissed = java.util.Collections.synchronizedSet(HashSet<String>())
    /** The identity an automatic profile change was last made (or attempted) for: at most once per report. */
    @Volatile private var autoAppliedKey: String? = null
    /** host|name the saved name was last filled from (at most once per report). */
    @Volatile private var nameFillKey: String? = null

    init {
        observe("locationSource", { settings.locationSource }) { v -> _state.update { it.copy(selectedSource = v) } }
        observe("externalTcpHost", { settings.externalTcpHost }) { v -> savedHost = v; publishTarget() }
        observe("externalTcpPort", { settings.externalTcpPort }) { v -> savedPort = v; publishTarget() }
        observe("externalTcpName", { settings.externalTcpName }) { v -> _state.update { it.copy(savedName = v?.takeIf { n -> n.isNotBlank() }) } }
        observe("externalReceiverProfile", { settings.externalReceiverProfile }) { v -> _state.update { it.copy(profile = v) } }
        observe("externalReceiverProfileOrigin", { settings.externalReceiverProfileOrigin }) { v -> _state.update { it.copy(profileOrigin = v) } }
        observe("externalRecentReceivers", { settings.externalRecentReceivers }) { v -> _state.update { it.copy(recent = v) } }
        observe("activeProvider", { activeProvider }) { v -> _state.update { it.copy(activeProvider = v) } }
        externalLink?.let { link ->
            observe("externalLink", { link }) { v ->
                val previous = _state.value.link
                _state.update { it.copy(link = v) }
                // The link (re)connected: the receiver at that address may be another unit (same hotspot IP).
                if (v == ReconnectingNmeaAdapter.ConnectionState.CONNECTED && previous != null &&
                    previous != ReconnectingNmeaAdapter.ConnectionState.CONNECTED) fenceIdentity("link connected")
            }
        }
        observe("captureActive", { captureActive }) { v -> _state.update { it.copy(captureActive = v) } }
        observe("deviceInfo", { deviceInfo }) { _ -> }
        externalEndpoint?.let { ep ->
            observe("externalEndpoint", { ep }) { a ->
                // A new connection attempt: whatever fix is held now came from the previous connection.
                if (lastEndpointSeq >= 0 && a.seq != lastEndpointSeq) fencedFix = liveFix.value
                lastEndpointSeq = a.seq
                connectedEndpoint = if (!a.host.isNullOrBlank() && a.port != null) ReceiverTarget(a.host.trim(), a.port) else null
                publishLiveSource()
            }
        }
        // Every selection-state change (attempt, provider, link, saved target, identity) and every live fix.
        scope.launch { _state.collect { publishLiveSource() } }
        scope.launch {
            try {
                liveFix.collect { publishLiveSource() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticsLogger.w(DiagTags.RECEIVER, "Receiver selection could not observe the live fix", e)
            }
        }
    }

    /** Recomputes [liveSource] from live values (provider, link, coordinator target) and [state]. */
    private fun publishLiveSource() {
        try {
            synchronized(liveLock) {
                val s = _state.value
                val active = coordinator.activeExternalTarget?.let { ReceiverTarget(it.first.trim(), it.second) }
                _liveSource.value = LiveSourceRules.derive(LiveSourceRules.Input(
                    activeProvider = activeProvider.value,
                    fix = liveFix.value,
                    fencedFix = fencedFix,
                    link = externalLink?.value,
                    connectedEndpoint = connectedEndpoint,
                    endpointKnown = externalEndpoint != null,
                    activeExternalTarget = active,
                    selectedSource = s.selectedSource,
                    savedTarget = s.savedTarget,
                    savedName = s.savedName,
                    attempt = s.attempt,
                    identity = s.identity,
                ))
            }
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.RECEIVER, "Live source not updated", e)
        }
    }

    /**
     * The device info that was current at the last switch or link (re)connect: it may describe the previous unit at
     * the same address (every Reach hotspot is 192.168.42.1), so it never identifies the receiver. Only a report that
     * arrives afterwards does (the device is restarted at the fence, so a fresh report follows at once).
     */
    @Volatile private var staleInfo: ReceiverDeviceInfo? = null

    private fun fenceIdentity(reason: String) {
        val current = deviceInfo.value
        staleInfo = current
        // Likewise the fix held right now belongs to the previous unit / connection: never attributed after this.
        fencedFix = liveFix.value
        autoAppliedKey = null
        nameFillKey = null
        if (current != null) DiagnosticsLogger.d(DiagTags.RECEIVER) { "Receiver identity waits for a fresh device report reason=\"$reason\"" }
        reevaluateIdentity()
        publishLiveSource()
        try {
            restartDevice?.invoke()
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.RECEIVER, "Receiver device restart failed", e)
        }
    }

    /** [deviceInfo] unless it is the report fenced off by [fenceIdentity]. */
    private fun trustedInfo(): ReceiverDeviceInfo? = deviceInfo.value?.takeUnless { it === staleInfo }

    // Host and port arrive on separate flows.
    @Volatile private var savedHost: String? = null
    @Volatile private var savedPort: Int? = null
    private fun publishTarget() {
        val h = savedHost?.trim()?.takeIf { it.isNotEmpty() }
        val p = savedPort
        val t = if (h != null && p != null && p in 1..65535) ReceiverTarget(h, p) else null
        _state.update { it.copy(savedTarget = t) }
    }

    /**
     * Collects a flow on [scope], applies [onEach] and then re-evaluates identity and mismatch. A missing flow (test
     * fakes) or a failing one is logged and ignored, never an exception.
     */
    private fun <T> observe(name: String, flow: () -> Flow<T>?, onEach: (T) -> Unit) {
        scope.launch {
            try {
                val f = flow() ?: return@launch
                f.collect { v ->
                    onEach(v)
                    reevaluateIdentity()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticsLogger.w(DiagTags.RECEIVER, "Receiver selection could not observe $name", e)
            }
        }
    }

    // ───────────────────────────── connect / switch ─────────────────────────────

    /**
     * Tests [request]'s receiver and, only if it streams NMEA, saves it and makes it the active source (see the class
     * KDoc). Returns at once; follow [state] for the outcome. [confirmCaptureStop] = the user confirmed that switching
     * stops the running capture.
     */
    fun connect(request: ConnectRequest, confirmCaptureStop: Boolean = false): ConnectStart =
        start(request, confirmCaptureStop, forceReconnect = false)

    /**
     * True when [target] is the receiver the external provider is streaming from right now. Read live (the active
     * provider and the coordinator's target), never from [state], whose copies start at defaults until collected.
     */
    private fun isActiveExternal(target: ReceiverTarget, request: ConnectRequest): Boolean {
        if (activeProvider.value != ProviderChoice.EXTERNAL_TCP) return false
        val active = coordinator.activeExternalTarget
        val sameTarget = if (active != null) active.first.trim().equals(target.host, ignoreCase = true) && active.second == target.port
            else _state.value.savedTarget == target
        return sameTarget && (request.profile == null || request.profile == _state.value.profile)
    }

    private fun start(request: ConnectRequest, confirmCaptureStop: Boolean, forceReconnect: Boolean): ConnectStart {
        val host = request.host.trim()
        if (host.isEmpty() || request.port !in 1..65535) return ConnectStart.INVALID
        val target = ReceiverTarget(host, request.port)
        synchronized(lock) {
            val attempt = _state.value.attempt
            when (attempt) {
                is ConnectionAttempt.Testing -> if (attempt.target == target && attempt.profile == request.profile) return ConnectStart.DUPLICATE_IGNORED
                is ConnectionAttempt.Switching -> if (attempt.target == target) return ConnectStart.DUPLICATE_IGNORED
                else -> Unit
            }
            val active = isActiveExternal(target, request)
            val link = externalLink?.value
            if (captureActive.value && !active && !confirmCaptureStop) return ConnectStart.NEEDS_CAPTURE_CONFIRMATION
            if (active && link == ReconnectingNmeaAdapter.ConnectionState.CONNECTED) return ConnectStart.ALREADY_ACTIVE
            // Just switched to it and the adapter is still connecting: a repeat tap must not probe again.
            if (active && attempt is ConnectionAttempt.Succeeded && attempt.target == target &&
                (link == null || link == ReconnectingNmeaAdapter.ConnectionState.CONNECTING)) return ConnectStart.ALREADY_ACTIVE
            val id = ++attemptSeq
            job?.cancel()
            _state.update { it.copy(attempt = ConnectionAttempt.Testing(id, target, request.profile)) }
            DiagnosticsLogger.i(DiagTags.RECEIVER, "External receiver test started port=${target.port} attempt=$id")
            job = scope.launch { runAttempt(id, target, request, confirmCaptureStop, forceReconnect) }
        }
        return ConnectStart.STARTED
    }

    /**
     * Reconnects the saved External TCP receiver (test before switch, like [connect]); [ConnectStart.NOTHING_SAVED]
     * when the saved source is not External TCP or no host/port is saved. When that receiver is already the active
     * target (but not streaming), a successful test also makes the external adapter reconnect now.
     */
    suspend fun reconnectSaved(confirmCaptureStop: Boolean = false): ConnectStart {
        val source = read("locationSource") { settings.locationSource.first() }
        val type = read("externalConnType") { settings.externalConnType.first() }
        val host = read("externalTcpHost") { settings.externalTcpHost.first() }
        val port = read("externalTcpPort") { settings.externalTcpPort.first() }
        if (source != LocationSourceType.EXTERNAL || type != ExternalConnectionType.TCP || host.isNullOrBlank() || port == null) {
            return ConnectStart.NOTHING_SAVED
        }
        return start(ConnectRequest(host, port), confirmCaptureStop, forceReconnect = true)
    }

    private fun isCurrent(id: Int) = synchronized(lock) { id == attemptSeq }

    private suspend fun runAttempt(id: Int, target: ReceiverTarget, request: ConnectRequest, confirmed: Boolean, forceReconnect: Boolean) {
        try {
            // The vendor decides whether the Emlid HTTP API may be asked: an unreadable profile asks nothing.
            val storedProfile = read("externalReceiverProfile") { settings.externalReceiverProfile.first() }
            val vendor = (request.profile ?: storedProfile)?.vendor
            val (tcp, http) = runProbe(target, vendor)
            if (!isCurrent(id)) {
                DiagnosticsLogger.i(DiagTags.RECEIVER, "External receiver test ignored: superseded attempt=$id")
                return
            }
            val error = classify(tcp, http, target.port)
            if (error != null) {
                DiagnosticsLogger.w(DiagTags.RECEIVER,
                    "External receiver test failed: nothing changed tcp=$tcp http=${http ?: "not asked"} port=${target.port} attempt=$id")
                finish(id, ConnectionAttempt.Failed(id, target, error))
                return
            }
            commit(id, target, request, storedProfile, http, confirmed, forceReconnect,
                noPositionYet = tcp == TcpProbeResult.RECEIVING_NMEA_NO_POSITION)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.RECEIVER, "External receiver test failed port=${target.port} attempt=$id", e)
            finish(id, ConnectionAttempt.Failed(id, target, ConnectError.Unexpected(e.javaClass.simpleName)))
        }
    }

    /**
     * The probe of [runAttempt] and [test]: the TCP NMEA sniff, plus the Emlid HTTP API in parallel only when [vendor]
     * is Emlid (an unreadable profile, null, asks nothing).
     */
    private suspend fun runProbe(target: ReceiverTarget, vendor: ReceiverVendor?): Pair<TcpProbeResult, Boolean?> = coroutineScope {
        val tcp = async { probe.tcp(target.host, target.port) }
        val http = if (vendor == ReceiverVendor.EMLID) async { probe.http(target.host) } else null
        tcp.await() to http?.await()
    }

    /**
     * Null when the sniff received valid NMEA (with or without a position: a receiver without a fix yet is reachable);
     * otherwise why the receiver can't be used. Used by both [runAttempt] and [test].
     */
    private fun classify(tcp: TcpProbeResult, http: Boolean?, port: Int): ConnectError? = when (tcp) {
        TcpProbeResult.RECEIVING_NMEA, TcpProbeResult.RECEIVING_NMEA_NO_POSITION -> null
        TcpProbeResult.CONNECT_FAILED -> if (http == true) ConnectError.HttpOnly(port) else ConnectError.Unreachable(port)
        TcpProbeResult.CONNECTED_NO_DATA -> ConnectError.NoData(port)
        TcpProbeResult.RECEIVING_RTCM_OR_BIN -> ConnectError.NotNmea(port)
        TcpProbeResult.INVALID_NMEA -> ConnectError.NotNmea(port, malformedNmea = true)
    }

    /**
     * "Test connection" (manual dialog): runs the same probe as [connect] for [request] and returns what it found,
     * WITHOUT saving anything, switching the source or touching [state]. Emlid HTTP is asked only when the request's
     * profile (or, with none, the stored profile) is an Emlid one. Cancelling the caller cancels the test.
     */
    suspend fun test(request: ConnectRequest): ReceiverTestResult {
        val host = request.host.trim()
        if (host.isEmpty() || request.port !in 1..65535) return ReceiverTestResult.Invalid
        val target = ReceiverTarget(host, request.port)
        return try {
            val storedProfile = if (request.profile == null) read("externalReceiverProfile") { settings.externalReceiverProfile.first() } else null
            val (tcp, http) = runProbe(target, (request.profile ?: storedProfile)?.vendor)
            val error = classify(tcp, http, target.port)
            DiagnosticsLogger.i(DiagTags.RECEIVER,
                "External receiver connection test (no switch) tcp=$tcp http=${http ?: "not asked"} port=${target.port}")
            if (error == null) ReceiverTestResult.Reachable(target, http, noPositionYet = tcp == TcpProbeResult.RECEIVING_NMEA_NO_POSITION)
            else ReceiverTestResult.Failed(target, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.RECEIVER, "External receiver connection test failed port=${target.port}", e)
            ReceiverTestResult.Failed(target, ConnectError.Unexpected(e.javaClass.simpleName))
        }
    }

    /**
     * Saves [target] and activates it, unless the attempt was superseded before it got here, or a capture started
     * during the test that the user has not confirmed stopping ([ConnectError.CaptureStarted]: nothing saved).
     * The settings are written in one transaction ([SettingsRepository.saveExternalTcpReceiver]): a failed write
     * leaves the previous receiver saved and active.
     */
    private suspend fun commit(
        id: Int, target: ReceiverTarget, request: ConnectRequest, storedProfile: ExternalReceiverProfile?, http: Boolean?,
        confirmed: Boolean, forceReconnect: Boolean, noPositionYet: Boolean = false,
    ) {
        switchLock.withLock {
            synchronized(lock) {
                if (id != attemptSeq) {
                    DiagnosticsLogger.i(DiagTags.RECEIVER, "External receiver switch ignored: superseded before saving attempt=$id")
                    return
                }
                // Re-checked live: a capture may have started while the probe ran.
                if (captureActive.value && !confirmed && !isActiveExternal(target, request)) {
                    DiagnosticsLogger.i(DiagTags.RECEIVER, "External receiver switch not made: a capture started during the test attempt=$id")
                    _state.update { it.copy(attempt = ConnectionAttempt.Failed(id, target, ConnectError.CaptureStarted)) }
                    return
                }
                _state.update { it.copy(attempt = ConnectionAttempt.Switching(id, target)) }
            }
            // Committed: finish saving even if a newer request cancels this job meanwhile (no half-saved receiver).
            val activated = withContext(NonCancellable) {
                // One transaction: connection type, host/port/name, the profile picked for this connection (as the
                // user's), and the selected source; then activate (GnssSourceCoordinator order).
                val profile = request.profile
                settings.saveExternalTcpReceiver(
                    host = target.host, port = target.port, name = request.name.trim(),
                    profile = profile?.takeIf { it != storedProfile }, origin = ProfileOrigin.USER,
                    source = LocationSourceType.EXTERNAL,
                )
                DiagnosticsLogger.i(DiagTags.GNSS, "External receiver settings saved port=${target.port} attempt=$id http=${http ?: "not asked"}")
                val activate = synchronized(lock) {
                    if (internalSeq > id) false
                    else {
                        // The device info on hand may be the previous unit's (same address): wait for a fresh one.
                        fenceIdentity("receiver switched")
                        // Reconnect of the receiver that is already the active target: activating it again keeps the
                        // (dead) stream, so the adapter is told to reconnect.
                        val sameActive = forceReconnect && isActiveExternal(target, request)
                        coordinator.activateExternalTcpProvider(target.host, target.port, reason = "settings-connect #$id")
                        if (sameActive) coordinator.reconnectExternalTcpProvider("reconnect saved receiver #$id")
                        lastActivationSeq = id
                        lastActivationExternal = true
                        publishLiveSource()
                        true
                    }
                }
                if (activate) {
                    _state.update { it.copy(selectionSaveError = null) }
                    rememberRecent(target, (profile ?: storedProfile)?.prefKey, request.name)
                }
                activate
            }
            if (!activated) {
                DiagnosticsLogger.i(DiagTags.RECEIVER, "External receiver not activated: device GNSS selected meanwhile attempt=$id")
                return
            }
            finish(id, ConnectionAttempt.Succeeded(id, target, http, noPositionYet))
        }
    }

    /** Publishes the end state of attempt [id] unless a newer attempt (or device GNSS) replaced it. */
    private fun finish(id: Int, end: ConnectionAttempt) = synchronized(lock) {
        if (id == attemptSeq) _state.update { it.copy(attempt = end) }
    }

    /**
     * Makes device GNSS active now (synchronously) and saves it as the selected source with mock publishing off. Any
     * running attempt is cancelled and can never activate External afterwards.
     */
    fun selectInternal(reason: String = "settings") {
        switchToInternal(reason, clearReceiver = false)
    }

    /** [selectInternal] and forget the saved host/port/name (recent receivers keep it). */
    fun disconnect(reason: String = "settings") {
        switchToInternal(reason, clearReceiver = true)
    }

    private fun switchToInternal(reason: String, clearReceiver: Boolean) {
        val seq: Int
        synchronized(lock) {
            val cancelled = _state.value.busy
            internalSeq = ++attemptSeq
            seq = internalSeq
            lastActivationSeq = seq
            lastActivationExternal = false
            job?.cancel()
            job = null
            coordinator.activateInternalProvider(reason)
            _state.update { it.copy(attempt = ConnectionAttempt.Idle) }
            DiagnosticsLogger.i(DiagTags.GNSS,
                "External receiver ${if (clearReceiver) "disconnected" else "deselected"}: device GNSS selected" +
                    (if (cancelled) " (test cancelled)" else "") + " attempt=$attemptSeq")
        }
        publishLiveSource()
        // Queued behind any commit in progress (switchLock): an External commit that was already saving when device
        // GNSS was chosen finishes its write first and does not activate (internalSeq > its id); this save then
        // writes Internal last, so the saved selection matches the active provider.
        scope.launch { switchLock.withLock { persistInternal(seq, clearReceiver) } }
    }

    /**
     * Saves device GNSS for the switch [seq] in one transaction ([SettingsRepository.saveInternalSource]), unless a
     * newer External commit has saved and activated itself meanwhile (its saved source must stand). A failed write is
     * retried once; if it still fails the active provider stays device GNSS (the user's explicit choice) and
     * [ReceiverSelectionState.selectionSaveError] reports that the saved selection could not be updated.
     */
    private suspend fun persistInternal(seq: Int, clearReceiver: Boolean) {
        val superseded = synchronized(lock) { lastActivationExternal && lastActivationSeq > seq }
        if (superseded) {
            DiagnosticsLogger.i(DiagTags.GNSS, "Device GNSS save skipped: a newer receiver was saved and activated attempt=$seq")
            return
        }
        var failure: Exception? = null
        for (attempt in 1..2) {
            try {
                settings.saveInternalSource(clearReceiver)
                _state.update { it.copy(selectionSaveError = null) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                DiagnosticsLogger.e(DiagTags.SETTINGS, "Settings save failed section=locationSource try=$attempt", e)
            }
        }
        _state.update {
            it.copy(selectionSaveError = "Device GNSS is active, but the selection could not be saved " +
                "(${failure?.javaClass?.simpleName ?: "error"}). Select it again; after a restart the previous source may be used.")
        }
    }

    /**
     * Saves the External source choice without a receiver to test (the old screen's External radio with no saved
     * receiver: it only shows the entry form). The active provider is not changed.
     */
    fun selectExternalWithoutReceiver() {
        scope.launch {
            switchLock.withLock {
                try {
                    settings.setLocationSource(LocationSourceType.EXTERNAL)
                    settings.setExternalConnType(ExternalConnectionType.TCP)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticsLogger.e(DiagTags.SETTINGS, "Settings save failed section=locationSource", e)
                }
            }
        }
    }

    // ───────────────────────────── identity / profile ─────────────────────────────

    private fun reevaluateIdentity() {
        val s = _state.value
        val identity = ReceiverIdentityRules.identify(
            trustedInfo(), s.savedTarget?.host,
            externalActive = activeProvider.value == ProviderChoice.EXTERNAL_TCP,
        )
        val decision = ReceiverIdentityRules.decide(identity, s.profile, s.profileOrigin, captureActive.value, dismissed.toSet())
        val mismatch = (decision as? ReceiverIdentityRules.Decision.Mismatch)?.mismatch
        if (s.identity != identity || s.mismatch != mismatch) _state.update { it.copy(identity = identity, mismatch = mismatch) }
        if (identity != null && identity != s.identity) updateRecentIdentity(identity, s)
        if (identity != null && s.savedName.isNullOrBlank()) fillSavedName(identity)
        if (decision is ReceiverIdentityRules.Decision.AutoApply) autoApply(identity!!, decision.profile)
    }

    private fun autoApply(identity: ReceiverIdentity, profile: ExternalReceiverProfile) {
        val key = "${identity.host}|${identity.reportedModel}|${profile.prefKey}"
        if (autoAppliedKey == key) return
        autoAppliedKey = key
        scope.launch {
            switchLock.withLock {
                // Re-check with fresh values: the user may have chosen a profile or started a capture meanwhile.
                val origin = read("externalReceiverProfileOrigin") { settings.externalReceiverProfileOrigin.first() }
                val current = read("externalReceiverProfile") { settings.externalReceiverProfile.first() }
                val host = read("externalTcpHost") { settings.externalTcpHost.first() }
                // The report must still be a fresh one for the saved host (not fenced off by a switch meanwhile).
                val fresh = ReceiverIdentityRules.identify(trustedInfo(), host, activeProvider.value == ProviderChoice.EXTERNAL_TCP)
                val again = if (origin == null || current == null) null else ReceiverIdentityRules.decide(
                    fresh?.takeIf { it.reportedModel == identity.reportedModel }, current, origin, captureActive.value)
                if (origin == null || current == null || again != ReceiverIdentityRules.Decision.AutoApply(profile)) {
                    // Not applied now (user choice, capture, other receiver): the next state change decides again.
                    autoAppliedKey = null
                    return@withLock
                }
                try {
                    settings.setExternalReceiverProfile(profile, ProfileOrigin.RECEIVER)
                    DiagnosticsLogger.i(DiagTags.RECEIVER,
                        "External receiver profile set from the receiver's report profile=${profile.prefKey} previous=${current.prefKey} origin=${origin.prefKey}")
                    _events.tryEmit(ReceiverSelectionEvent.ProfileAutoApplied(current, profile, identity.reportedModel ?: profile.label))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticsLogger.w(DiagTags.RECEIVER, "External receiver profile from report not saved", e)
                }
            }
        }
    }

    /**
     * "Use [reported model]" on the mismatch banner: saves the reported profile (origin RECEIVER). During a capture it
     * returns [ApplyProfileResult.NEEDS_CAPTURE_CONFIRMATION] unless [confirmCaptureStop] (the change stops it).
     */
    fun applyReportedProfile(confirmCaptureStop: Boolean = false): ApplyProfileResult {
        val m = _state.value.mismatch ?: return ApplyProfileResult.NO_MISMATCH
        if (captureActive.value && !confirmCaptureStop) return ApplyProfileResult.NEEDS_CAPTURE_CONFIRMATION
        scope.launch {
            switchLock.withLock {
                try {
                    settings.setExternalReceiverProfile(m.reportedProfile, ProfileOrigin.RECEIVER)
                    DiagnosticsLogger.i(DiagTags.RECEIVER,
                        "External receiver profile set from the receiver's report by the user profile=${m.reportedProfile.prefKey} previous=${m.currentProfile.prefKey}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticsLogger.w(DiagTags.RECEIVER, "External receiver profile from report not saved", e)
                }
            }
        }
        return ApplyProfileResult.APPLIED
    }

    /** "Keep" on the mismatch banner: the profile becomes the user's choice and this report is not shown again. */
    fun keepCurrentProfile() {
        val m = _state.value.mismatch ?: return
        dismissed += ReceiverIdentityRules.dismissKey(m.host, m.reportedModel, m.currentProfile)
        reevaluateIdentity()
        scope.launch {
            switchLock.withLock {
                try { settings.setExternalReceiverProfile(m.currentProfile, ProfileOrigin.USER) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { DiagnosticsLogger.w(DiagTags.RECEIVER, "External receiver profile origin not saved", e) }
            }
        }
    }

    /** The user picked [profile] (selector / manual dialog): saved with origin USER. */
    suspend fun setProfileByUser(profile: ExternalReceiverProfile) {
        switchLock.withLock { settings.setExternalReceiverProfile(profile, ProfileOrigin.USER) }
    }

    // ───────────────────────────── recent receivers ─────────────────────────────

    private suspend fun rememberRecent(target: ReceiverTarget, profileKey: String?, name: String) {
        try {
            val list = read("externalRecentReceivers") { settings.externalRecentReceivers.first() } ?: emptyList()
            val entry = RecentReceiver(target.host, target.port, profileKey, name.trim().takeIf { it.isNotEmpty() },
                lastConnectedMs = clockMs())
            settings.setExternalRecentReceivers(RecentReceiversCodec.upsert(list, entry))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.RECEIVER, "Recent receivers not saved", e)
        }
    }

    /** Adds what the receiver reported (model, serial, firmware, name) to its recent entry, when it changed. */
    private fun updateRecentIdentity(identity: ReceiverIdentity, s: ReceiverSelectionState) {
        val target = s.savedTarget ?: return
        val existing = s.recent.firstOrNull { it.endpointKey == RecentReceiver(target.host, target.port).endpointKey }
        if (existing != null && existing.model == identity.reportedModel && existing.serial == identity.serial &&
            existing.firmware == identity.firmware && (identity.name == null || existing.name == identity.name)) return
        scope.launch {
            switchLock.withLock {
                try {
                    val list = read("externalRecentReceivers") { settings.externalRecentReceivers.first() } ?: return@withLock
                    val entry = RecentReceiver(
                        host = target.host, port = target.port,
                        profileKey = existing?.profileKey ?: s.profile.prefKey,
                        name = identity.name, model = identity.reportedModel, serial = identity.serial,
                        firmware = identity.firmware, lastConnectedMs = existing?.lastConnectedMs ?: clockMs(),
                    )
                    settings.setExternalRecentReceivers(RecentReceiversCodec.upsert(list, entry))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticsLogger.w(DiagTags.RECEIVER, "Recent receivers not saved", e)
                }
            }
        }
    }

    /**
     * The receiver's own name (a fresh, verified report for the saved host) becomes the saved name when none is
     * stored. A stored name (entered by the user, or reported before) is never overwritten.
     */
    private fun fillSavedName(identity: ReceiverIdentity) {
        val reported = identity.name?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (!_state.value.savedName.isNullOrBlank()) return
        val key = "${identity.host.lowercase(java.util.Locale.ROOT)}|$reported"
        if (nameFillKey == key) return
        nameFillKey = key
        scope.launch {
            switchLock.withLock {
                try {
                    val host = read("externalTcpHost") { settings.externalTcpHost.first() }?.trim() ?: return@withLock
                    val port = read("externalTcpPort") { settings.externalTcpPort.first() } ?: return@withLock
                    val stored = read("externalTcpName") { settings.externalTcpName.first() }
                    if (!stored.isNullOrBlank() || !host.equals(identity.host, ignoreCase = true)) return@withLock
                    // Still this receiver's report (not fenced off by a switch meanwhile)?
                    if (trustedInfo()?.name?.trim() != reported) return@withLock
                    settings.setExternalTcp(host, port, reported)
                    DiagnosticsLogger.i(DiagTags.RECEIVER, "External receiver name saved from the receiver's report")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticsLogger.w(DiagTags.RECEIVER, "External receiver name not saved", e)
                }
            }
        }
    }

    /** Removes one entry from the recent list (picker "Forget"). */
    suspend fun forgetRecent(receiver: RecentReceiver) {
        switchLock.withLock {
            val list = read("externalRecentReceivers") { settings.externalRecentReceivers.first() } ?: return@withLock
            settings.setExternalRecentReceivers(list.filterNot { it == receiver })
        }
    }

    // ───────────────────────────── network scan ─────────────────────────────

    /**
     * Starts a network scan (ignored while one runs); results in [ReceiverSelectionState.scan]. The scan is Emlid
     * discovery (an HTTP sweep of the subnet plus mDNS), so it runs only for an Emlid profile or when the user asked
     * for it explicitly ([emlidScan], "Scan for Emlid receivers"): a Generic NMEA user never triggers it implicitly.
     * Returns false when nothing was started for that reason.
     */
    fun scanNetwork(timeoutMs: Long = 10_000L, emlidScan: Boolean = false): Boolean {
        if (!emlidScan && _state.value.profile.vendor != ReceiverVendor.EMLID) {
            DiagnosticsLogger.d(DiagTags.RECEIVER) { "Receiver network scan not started: not an Emlid profile and not requested" }
            return false
        }
        val d = discovery
        if (d == null) {
            _state.update { it.copy(scan = NetworkScanState.Failed("Network scan is not available.")) }
            return true
        }
        synchronized(lock) {
            if (scanJob?.isActive == true) return true
            _state.update { it.copy(scan = NetworkScanState.Scanning) }
            scanJob = scope.launch {
                try {
                    val found = d.discover(timeoutMs).map {
                        ScannedReceiver(it.host, it.nmeaPort ?: ExternalReceiverProfile.REACH_RS2_PLUS.defaultPort, it.label,
                            EmlidDiscovery.UNCONFIRMED_MODEL_LABEL)
                    }
                    DiagnosticsLogger.i(DiagTags.RECEIVER, "Receiver network scan finished found=${found.size}")
                    _state.update { it.copy(scan = NetworkScanState.Done(found, clockMs())) }
                } catch (e: CancellationException) {
                    _state.update { it.copy(scan = NetworkScanState.Idle) }
                    throw e
                } catch (e: Exception) {
                    DiagnosticsLogger.w(DiagTags.RECEIVER, "Receiver network scan failed", e)
                    _state.update { it.copy(scan = NetworkScanState.Failed("Network scan failed.")) }
                }
            }
        }
        return true
    }

    /** A settings read that logs and returns null on failure (cancellation is rethrown). */
    private suspend fun <T> read(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DiagnosticsLogger.w(DiagTags.RECEIVER, "Receiver selection setting read failed setting=$what", e)
        null
    }
}
