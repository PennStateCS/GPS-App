package app.surrealar.receiver.selection

import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
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
) {
    private val _state = MutableStateFlow(ReceiverSelectionState())
    /** Everything receiver selection shows; see [ReceiverSelectionState]. */
    val state: StateFlow<ReceiverSelectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ReceiverSelectionEvent>(extraBufferCapacity = 8)
    /** One-off notices (automatic profile change) for a snackbar. */
    val events: SharedFlow<ReceiverSelectionEvent> = _events.asSharedFlow()

    /** Guards [attemptSeq], [internalSeq], [job] and every active-provider flip made here. */
    private val lock = Any()
    private var attemptSeq = 0
    /** [attemptSeq] value of the last switch to device GNSS (an attempt older than it must not activate). */
    private var internalSeq = 0
    private var job: Job? = null
    private var scanJob: Job? = null

    /** Serialises every settings write made here (commit of an attempt, device GNSS, profile changes). */
    private val switchLock = Mutex()

    /** Mismatches the user answered "Keep" (this process only). */
    private val dismissed = java.util.Collections.synchronizedSet(HashSet<String>())
    /** The identity an automatic profile change was last made (or attempted) for: at most once per report. */
    @Volatile private var autoAppliedKey: String? = null

    init {
        observe("locationSource", { settings.locationSource }) { v -> _state.update { it.copy(selectedSource = v) } }
        observe("externalTcpHost", { settings.externalTcpHost }) { v -> savedHost = v; publishTarget() }
        observe("externalTcpPort", { settings.externalTcpPort }) { v -> savedPort = v; publishTarget() }
        observe("externalTcpName", { settings.externalTcpName }) { v -> _state.update { it.copy(savedName = v?.takeIf { n -> n.isNotBlank() }) } }
        observe("externalReceiverProfile", { settings.externalReceiverProfile }) { v -> _state.update { it.copy(profile = v) } }
        observe("externalReceiverProfileOrigin", { settings.externalReceiverProfileOrigin }) { v -> _state.update { it.copy(profileOrigin = v) } }
        observe("externalRecentReceivers", { settings.externalRecentReceivers }) { v -> _state.update { it.copy(recent = v) } }
        observe("activeProvider", { activeProvider }) { v -> _state.update { it.copy(activeProvider = v) } }
        externalLink?.let { link -> observe("externalLink", { link }) { v -> _state.update { it.copy(link = v) } } }
        observe("captureActive", { captureActive }) { v -> _state.update { it.copy(captureActive = v) } }
        observe("deviceInfo", { deviceInfo }) { _ -> }
    }

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
    fun connect(request: ConnectRequest, confirmCaptureStop: Boolean = false): ConnectStart {
        val host = request.host.trim()
        if (host.isEmpty() || request.port !in 1..65535) return ConnectStart.INVALID
        val target = ReceiverTarget(host, request.port)
        synchronized(lock) {
            val s = _state.value
            when (val a = s.attempt) {
                is ConnectionAttempt.Testing -> if (a.target == target && a.profile == request.profile) return ConnectStart.DUPLICATE_IGNORED
                is ConnectionAttempt.Switching -> if (a.target == target) return ConnectStart.DUPLICATE_IGNORED
                else -> Unit
            }
            val externalActive = s.activeProvider == ProviderChoice.EXTERNAL_TCP && s.selectedSource == LocationSourceType.EXTERNAL
            val sameTarget = s.savedTarget == target && (request.profile == null || request.profile == s.profile)
            if (s.captureActive && !(externalActive && sameTarget) && !confirmCaptureStop) return ConnectStart.NEEDS_CAPTURE_CONFIRMATION
            if (externalActive && sameTarget && s.link == ReconnectingNmeaAdapter.ConnectionState.CONNECTED) {
                return ConnectStart.ALREADY_ACTIVE
            }
            val id = ++attemptSeq
            job?.cancel()
            _state.update { it.copy(attempt = ConnectionAttempt.Testing(id, target, request.profile)) }
            DiagnosticsLogger.i(DiagTags.RECEIVER, "External receiver test started port=${target.port} attempt=$id")
            job = scope.launch { runAttempt(id, target, request) }
        }
        return ConnectStart.STARTED
    }

    /**
     * Reconnects the saved External TCP receiver (test before switch, like [connect]); [ConnectStart.NOTHING_SAVED]
     * when the saved source is not External TCP or no host/port is saved.
     */
    suspend fun reconnectSaved(confirmCaptureStop: Boolean = false): ConnectStart {
        val source = read("locationSource") { settings.locationSource.first() }
        val type = read("externalConnType") { settings.externalConnType.first() }
        val host = read("externalTcpHost") { settings.externalTcpHost.first() }
        val port = read("externalTcpPort") { settings.externalTcpPort.first() }
        if (source != LocationSourceType.EXTERNAL || type != ExternalConnectionType.TCP || host.isNullOrBlank() || port == null) {
            return ConnectStart.NOTHING_SAVED
        }
        return connect(ConnectRequest(host, port), confirmCaptureStop)
    }

    private fun isCurrent(id: Int) = synchronized(lock) { id == attemptSeq }

    private suspend fun runAttempt(id: Int, target: ReceiverTarget, request: ConnectRequest) {
        try {
            // The vendor decides whether the Emlid HTTP API may be asked: an unreadable profile asks nothing.
            val storedProfile = read("externalReceiverProfile") { settings.externalReceiverProfile.first() }
            val vendor = (request.profile ?: storedProfile)?.vendor
            val (tcp, http) = coroutineScope {
                val tcp = async { probe.tcp(target.host, target.port) }
                val http = if (vendor == ReceiverVendor.EMLID) async { probe.http(target.host) } else null
                tcp.await() to http?.await()
            }
            if (!isCurrent(id)) {
                DiagnosticsLogger.i(DiagTags.RECEIVER, "External receiver test ignored: superseded attempt=$id")
                return
            }
            val error = when (tcp) {
                TcpProbeResult.RECEIVING_NMEA -> null
                TcpProbeResult.CONNECT_FAILED -> if (http == true) ConnectError.HttpOnly(target.port) else ConnectError.Unreachable(target.port)
                TcpProbeResult.CONNECTED_NO_DATA -> ConnectError.NoData(target.port)
                TcpProbeResult.RECEIVING_RTCM_OR_BIN -> ConnectError.NotNmea(target.port)
            }
            if (error != null) {
                DiagnosticsLogger.w(DiagTags.RECEIVER,
                    "External receiver test failed: nothing changed tcp=$tcp http=${http ?: "not asked"} port=${target.port} attempt=$id")
                finish(id, ConnectionAttempt.Failed(id, target, error))
                return
            }
            commit(id, target, request, storedProfile, http)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.RECEIVER, "External receiver test failed port=${target.port} attempt=$id", e)
            finish(id, ConnectionAttempt.Failed(id, target, ConnectError.Unexpected(e.javaClass.simpleName)))
        }
    }

    /** Saves [target] and activates it, unless the attempt was superseded before it got here. */
    private suspend fun commit(id: Int, target: ReceiverTarget, request: ConnectRequest, storedProfile: ExternalReceiverProfile?, http: Boolean?) {
        switchLock.withLock {
            synchronized(lock) {
                if (id != attemptSeq) {
                    DiagnosticsLogger.i(DiagTags.RECEIVER, "External receiver switch ignored: superseded before saving attempt=$id")
                    return
                }
                _state.update { it.copy(attempt = ConnectionAttempt.Switching(id, target)) }
            }
            // Committed: finish saving even if a newer request cancels this job meanwhile (no half-saved receiver).
            val activated = withContext(NonCancellable) {
                // Order (GnssSourceCoordinator): connection type → host/port → selected source → activate. The
                // profile the user picked for this connection is saved before the source.
                settings.setExternalConnType(ExternalConnectionType.TCP)
                settings.setExternalTcp(target.host, target.port, request.name.trim())
                val profile = request.profile
                if (profile != null && profile != storedProfile) settings.setExternalReceiverProfile(profile, ProfileOrigin.USER)
                settings.setLocationSource(LocationSourceType.EXTERNAL)
                DiagnosticsLogger.i(DiagTags.GNSS, "External receiver settings saved port=${target.port} attempt=$id http=${http ?: "not asked"}")
                val activate = synchronized(lock) {
                    if (internalSeq > id) false
                    else { coordinator.activateExternalTcpProvider(target.host, target.port, reason = "settings-connect #$id"); true }
                }
                if (activate) rememberRecent(target, (profile ?: storedProfile)?.prefKey, request.name)
                activate
            }
            if (!activated) {
                DiagnosticsLogger.i(DiagTags.RECEIVER, "External receiver not activated: device GNSS selected meanwhile attempt=$id")
                return
            }
            finish(id, ConnectionAttempt.Succeeded(id, target, http))
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
        synchronized(lock) {
            val cancelled = _state.value.busy
            internalSeq = ++attemptSeq
            job?.cancel()
            job = null
            coordinator.activateInternalProvider(reason)
            _state.update { it.copy(attempt = ConnectionAttempt.Idle) }
            DiagnosticsLogger.i(DiagTags.GNSS,
                "External receiver ${if (clearReceiver) "disconnected" else "deselected"}: device GNSS selected" +
                    (if (cancelled) " (test cancelled)" else "") + " attempt=$attemptSeq")
        }
        scope.launch {
            switchLock.withLock {
                try {
                    settings.setMockLocationEnabled(false)
                    settings.setLocationSource(LocationSourceType.INTERNAL)
                    if (clearReceiver) settings.clearExternalTcp()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticsLogger.e(DiagTags.SETTINGS, "Settings save failed section=locationSource", e)
                }
            }
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
            deviceInfo.value, s.savedTarget?.host,
            externalActive = s.activeProvider == ProviderChoice.EXTERNAL_TCP,
        )
        val decision = ReceiverIdentityRules.decide(identity, s.profile, s.profileOrigin, s.captureActive, dismissed.toSet())
        val mismatch = (decision as? ReceiverIdentityRules.Decision.Mismatch)?.mismatch
        if (s.identity != identity || s.mismatch != mismatch) _state.update { it.copy(identity = identity, mismatch = mismatch) }
        if (identity != null && identity != s.identity) updateRecentIdentity(identity, s)
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
                val again = if (origin == null || current == null) null else ReceiverIdentityRules.decide(
                    identity.takeIf { host?.trim().equals(it.host, ignoreCase = true) }, current, origin, captureActive.value)
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
        if (_state.value.captureActive && !confirmCaptureStop) return ApplyProfileResult.NEEDS_CAPTURE_CONFIRMATION
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

    /** Removes one entry from the recent list (picker "Forget"). */
    suspend fun forgetRecent(receiver: RecentReceiver) {
        switchLock.withLock {
            val list = read("externalRecentReceivers") { settings.externalRecentReceivers.first() } ?: return@withLock
            settings.setExternalRecentReceivers(list.filterNot { it == receiver })
        }
    }

    // ───────────────────────────── network scan ─────────────────────────────

    /** Starts a network scan (ignored while one runs); results in [ReceiverSelectionState.scan]. */
    fun scanNetwork(timeoutMs: Long = 10_000L) {
        val d = discovery
        if (d == null) {
            _state.update { it.copy(scan = NetworkScanState.Failed("Network scan is not available.")) }
            return
        }
        synchronized(lock) {
            if (scanJob?.isActive == true) return
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
