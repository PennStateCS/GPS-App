package app.surrealar.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.surrealar.domain.model.LocationSourceType
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.capture.toAveragingPolicy
import io.github.pennstatecs.geokit.gnss.capture.resolvePolicy
import app.surrealar.gnss.bus.FixSwitchboard
import io.github.pennstatecs.geokit.gnss.capture.AveragingPolicy
import io.github.pennstatecs.geokit.gnss.capture.ObservationSession
import io.github.pennstatecs.geokit.gnss.model.Fix
import app.surrealar.domain.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import app.surrealar.util.DiagnosticsLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Runs averaged GNSS captures for the Add Coordinate dialog: an `ObservationSession` over the
 * selected mode's provider (internal or external), exposing capture progress/state. A capture only
 * completes with a real averaged fix (position + altitude).
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val fixSwitchboard: FixSwitchboard,
    private val captureCoordinate: app.surrealar.domain.usecase.CaptureCoordinateUseCase,
    private val sourceSettings: app.surrealar.gnss.source.SourceSettings,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private var session: ObservationSession? = null
    private var sessionForwardJob: Job? = null

    /**
     * Settings that affect the saved point, read when the capture STARTS. Saving uses these, so
     * editing e.g. the antenna height mid-capture can't change a point measured with the old pole.
     */
    data class CaptureSettingsSnapshot(
        val locationSource: LocationSourceType?,
        val receiver: app.surrealar.gnss.settings.GnssReceiverSettings?,
        val externalTcpName: String?,
        val externalTcpHost: String?,
        val externalProfileLabel: String?,
        val coordinateSystemSettings: app.surrealar.settings.model.CoordinateSystemSettings?,
    )

    private var startSnapshot: kotlinx.coroutines.Deferred<CaptureSettingsSnapshot>? = null

    private suspend fun readSnapshot() = CaptureSettingsSnapshot(
        locationSource = runCatching { settingsRepository.locationSource.first() }.getOrNull(),
        receiver = runCatching { settingsRepository.gnssReceiverSettings.first() }.getOrNull(),
        externalTcpName = runCatching { settingsRepository.externalTcpName.first() }.getOrNull(),
        externalTcpHost = runCatching { settingsRepository.externalTcpHost.first() }.getOrNull(),
        externalProfileLabel = runCatching { settingsRepository.externalReceiverProfile.first().label }.getOrNull(),
        coordinateSystemSettings = runCatching { settingsRepository.coordinateSystemSettings.first() }.getOrNull(),
    )

    /** The settings snapshot taken when the current capture started (read now if none was taken). */
    suspend fun settingsAtStart(): CaptureSettingsSnapshot? =
        runCatching { startSnapshot?.await() ?: readSnapshot() }.getOrNull()

    private val _state = MutableStateFlow<ObservationSession.State>(ObservationSession.State.Idle)
    val state: StateFlow<ObservationSession.State> = _state.asStateFlow()

    init {
        // Cancel any in-progress averaging session when the provider switches.
        // A session collecting RS2+ fixes must not continue under Internal GPS and vice versa.
        viewModelScope.launch {
            var previousProvider: app.surrealar.gnss.source.SourceSettings.ProviderChoice? = null
            sourceSettings.activeProvider.collect { provider ->
                if (previousProvider != null && previousProvider != provider) {
                    if (session != null) {
                        DiagnosticsLogger.i("CAPTURE", "Capture canceled/reset due to provider switch $previousProvider -> $provider")
                        cancel()
                    }
                }
                previousProvider = provider
            }
        }
    }

    /**
     * Loads the saved GNSS Capture settings and starts an averaging session with that policy.
     * Falls back to safe defaults if settings cannot be read.
     */
    fun startWithSavedPolicy(internal: Boolean = false) {
        viewModelScope.launch {
            // Saved preset (or Custom fields) for this mode. Fallback = the same defaults the Settings
            // screen shows, so a failed read never silently applies a different policy.
            val settings = runCatching { settingsRepository.gnssCaptureSettings.first() }
                .getOrDefault(io.github.pennstatecs.geokit.gnss.capture.GnssCaptureSettings())
            start(settings.resolvePolicy(internal), internal)
        }
    }

    /**
     * Starts an INTERNAL (device GNSS) averaged capture with the saved preset's internal policy.
     * Only internal-provider fixes are averaged.
     */
    fun startInternal() = startWithSavedPolicy(internal = true)

    /**
     * Starts an averaging session. [internal] selects which provider's fixes are averaged: false =
     * external receiver only (default), true = internal GNSS only. Fixes from the other provider are
     * never mixed in, even during a reconnect window where the live provider differs.
     */
    fun start(policy: AveragingPolicy, internal: Boolean = false) {
        // Cancel any existing session FIRST so a previous ObservationSession can't keep collecting
        // fixes or emitting state into the UI after a new capture begins.
        if (session != null) {
            DiagnosticsLogger.i("CAPTURE", "Previous capture session canceled before new start")
            captureCancelled()
        }
        stopSession()
        _state.value = ObservationSession.State.Idle
        // Snapshot the settings that shape the saved point NOW (UNDISPATCHED: reads start immediately).
        startSnapshot = viewModelScope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { readSnapshot() }

        val provider = sourceSettings.activeProvider.value
        DiagnosticsLogger.i("CAPTURE", "Capture started mode=${if (internal) "INTERNAL" else "EXTERNAL"} " +
            "activeProvider=$provider minDur=${policy.minDurationSec}s maxDur=${policy.maxDurationSec}s " +
            "minFixes=${policy.minSamples} required=${policy.requiredMinStatus}")
        // Feed the session ONLY the selected mode's provider, so internal GPS fixes can never be
        // averaged into an external capture (even with quality lowered to Single/DGPS, or while
        // External is selected but the live provider is still Internal) and vice versa.
        // Also skip the fix replayed from before this session started (the fixes flow replays its
        // last value to new subscribers) — only fixes that arrive during the session are averaged.
        val replayedBeforeStart = fixSwitchboard.fixes.replayCache.lastOrNull()
        // NMEA replay fixes (Provider.REPLAY) are never averaged into a saved point.
        val modeFixes = fixSwitchboard.fixes.filter {
            it !== replayedBeforeStart &&
                it.provider != io.github.pennstatecs.geokit.gnss.model.Provider.REPLAY &&
                (it.provider == io.github.pennstatecs.geokit.gnss.model.Provider.INTERNAL) == internal
        }
        val s = ObservationSession(viewModelScope, modeFixes, policy)
        session = s
        // Diagnostics only: a separate read-only collector (the session's own flow is untouched).
        resetCaptureDiag()
        val diag = app.surrealar.util.diagnostics.CaptureDiagnostics(policy, if (internal) "INTERNAL" else "EXTERNAL")
        captureDiag = diag
        DiagnosticsLogger.i("CAPTURE", diag.startLine())
        captureDiagJob = viewModelScope.launch { modeFixes.collect { diag.onFix(it) } }
        sessionForwardJob = viewModelScope.launch {
            s.state.collect { st ->
                _state.value = st
                diag.onState(st)
                if (st is ObservationSession.State.Complete) captureFinished(diag, st)
                if (st is ObservationSession.State.Complete) {
                    if (st.requirementsMet) {
                        DiagnosticsLogger.i("CAPTURE",
                            "Capture ready (requirements met) acceptedFixes=${st.result.samples}")
                    } else {
                        DiagnosticsLogger.w("CAPTURE",
                            "Capture timed out before requirements met acceptedFixes=${st.result.samples}/${policy.minSamples}")
                    }
                }
            }
        }
        s.start()
    }

    // ── diagnostics (log lines only) ──
    private var captureDiag: app.surrealar.util.diagnostics.CaptureDiagnostics? = null
    private var captureDiagJob: kotlinx.coroutines.Job? = null
    private var captureOutcomeLogged = false
    private var completedNotSavedLine: String? = null

    private fun captureFinished(diag: app.surrealar.util.diagnostics.CaptureDiagnostics, st: ObservationSession.State.Complete) {
        if (captureOutcomeLogged) return
        captureOutcomeLogged = true
        captureDiagJob?.cancel()
        val r = st.result
        DiagnosticsLogger.i("CAPTURE", diag.rejectedSummary(r.samples))
        val line = diag.outcomeLine(if (st.requirementsMet) "completed" else "timed out", r.samples,
            java.time.Duration.between(r.startedAt, r.endedAt).seconds, r.rtkStatus, r.hAccM, r.vAccM)
        if (st.requirementsMet) DiagnosticsLogger.i("CAPTURE", line) else DiagnosticsLogger.w("CAPTURE", line)
        if (st.requirementsMet) completedNotSavedLine = line.replace("Capture completed", "Capture completed, not saved")
        else app.surrealar.util.diagnostics.CaptureLog.append(line)
    }

    private fun captureCancelled() {
        val diag = captureDiag ?: return
        if (!captureOutcomeLogged) {
            val cap = state.value as? ObservationSession.State.Capturing
            DiagnosticsLogger.i("CAPTURE", diag.rejectedSummary(cap?.samples))
            val line = diag.outcomeLine("cancelled", cap?.samples, cap?.elapsedSec?.toLong(),
                cap?.rtkStatus?.let { io.github.pennstatecs.geokit.gnss.model.RtkStatus.fromPrefKey(it) }, cap?.hAccM, cap?.vAccM)
            DiagnosticsLogger.i("CAPTURE", line)
            app.surrealar.util.diagnostics.CaptureLog.append(line)
        } else {
            completedNotSavedLine?.let { app.surrealar.util.diagnostics.CaptureLog.append(it) }
        }
        resetCaptureDiag()
    }

    private fun resetCaptureDiag() {
        captureDiagJob?.cancel(); captureDiagJob = null
        captureDiag = null; captureOutcomeLogged = false; completedNotSavedLine = null
    }

    fun cancel() {
        if (session != null) DiagnosticsLogger.i("CAPTURE", "Capture canceled")
        captureCancelled()
        stopSession()
        _state.value = ObservationSession.State.Idle
    }

    /**
     * Tears down the current session and its state-forwarding job WITHOUT touching the public
     * state (callers set it). Forwarding is cancelled before the session so the old session's
     * final Idle emission can't leak into [_state].
     */
    private fun stopSession() {
        sessionForwardJob?.cancel()
        sessionForwardJob = null
        session?.cancel()
        session = null
    }

    override fun onCleared() {
        super.onCleared()
        sessionForwardJob?.cancel()
    }

    suspend fun save(name: String, note: String?, color: Int, iconId: String) {
        val completed = (state.value as? ObservationSession.State.Complete) ?: return
        // Defense-in-depth: never persist a timed-out, under-sampled capture. The UI disables
        // Save in that case; this guards the data path too.
        if (!completed.requirementsMet) {
            DiagnosticsLogger.w("CAPTURE", "Save blocked: requirements not met (timed out)")
            return
        }
        val finished = completed.result
        // Everything below comes from the settings as they were when the capture started.
        val snap = settingsAtStart()

        val src = snap?.locationSource ?: settingsRepository.locationSource.first()
        val provider = when (src) {
            LocationSourceType.INTERNAL  -> Provider.INTERNAL
            LocationSourceType.EXTERNAL  -> Provider.EXTERNAL_TCP
            LocationSourceType.SIMULATOR -> Provider.OTHER
        }

        val captureMethod = when (src) {
            LocationSourceType.INTERNAL  -> "internal_gps"
            LocationSourceType.EXTERNAL  -> "external_gnss"
            LocationSourceType.SIMULATOR -> "averaged"
        }

        // Receiver identity: the active profile's label (stable strings such as "Emlid Reach RS2+"),
        // from the capture-start snapshot. Existing rows keep whatever they stored.
        val sourceDevice = when (src) {
            LocationSourceType.EXTERNAL -> snap?.externalProfileLabel?.takeIf { it.isNotBlank() } ?: "External GNSS"
            LocationSourceType.INTERNAL  -> "Internal GPS"
            LocationSourceType.SIMULATOR -> null
        }

        // Pole/antenna vertical offset: subtract the height of the antenna that produced this fix.
        // External and internal have separate heights (the RS2 antenna and the pole-mounted tablet
        // sit at different heights); simulator/replay has none.
        val antennaHeightM = runCatching {
            val receiver = snap?.receiver ?: return@runCatching 0.0
            when (src) {
                LocationSourceType.EXTERNAL  -> receiver.antennaHeightM
                LocationSourceType.INTERNAL  -> receiver.internalAntennaHeightM
                LocationSourceType.SIMULATOR -> 0.0
            }
        }.getOrDefault(0.0)

        // Build → validate → save is centralized in the use case (it rejects an invalid coordinate
        // rather than persisting it). The source/provider/device mapping above stays here because it
        // reads UI/settings state.
        captureCoordinate(
            name           = name,
            note           = note,
            color          = color,
            iconId         = iconId,
            provider       = provider,
            result         = finished,
            captureMethod  = captureMethod,
            sourceDevice   = sourceDevice,
            antennaHeightM = antennaHeightM,
        )

        DiagnosticsLogger.i("CAPTURE", "Coordinate saved name=\"$name\" source=${provider.name} " +
            "samples=${finished.samples} hAcc=${finished.hAccM?.let { "%.3fm".format(it) } ?: "?"} status=${finished.rtkStatus}")
        captureDiag?.let { d ->
            app.surrealar.util.diagnostics.CaptureLog.append(d.outcomeLine("saved", finished.samples,
                java.time.Duration.between(finished.startedAt, finished.endedAt).seconds, finished.rtkStatus,
                finished.hAccM, finished.vAccM, name = name))
        }
        resetCaptureDiag()
    }
}
