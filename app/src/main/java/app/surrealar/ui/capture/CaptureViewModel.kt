package app.surrealar.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.surrealar.coords.frameToken
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
import app.surrealar.util.diagnostics.DiagTags
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
    private val settingsRepository: SettingsRepository,
    private val feedDatum: app.surrealar.gnss.source.ReceiverFeedDatum,
    /**
     * RS4 / RS4 Pro tilt status (Phase 4 WP C): classifies each external epoch's position reference. Null (tests
     * that predate it) = no reference gating, exactly as before.
     */
    private val tiltMonitor: app.surrealar.receiver.tilt.ReceiverTiltMonitor? = null,
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
        /** The receiver profile (null when unreadable: the saved point then uses the default profile, as before). */
        val externalProfile: app.surrealar.settings.model.ExternalReceiverProfile? = null,
        /** "Compensated NMEA output (Emlid Flow)": read only for RS4 / RS4 Pro profiles; null otherwise or unreadable. */
        val compensatedOutput: app.surrealar.settings.model.CompensatedOutputSetting? = null,
    )

    private var startSnapshot: kotlinx.coroutines.Deferred<CaptureSettingsSnapshot>? = null

    /** Reads one snapshot field; a failure is logged (W, with the exception) and reads as null. */
    private suspend inline fun <T> readOrNull(field: String, read: () -> T): T? = try {
        read()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture settings read failed: using default field=$field", e)
        null
    }

    private suspend fun readSnapshot(): CaptureSettingsSnapshot {
        val profile = readOrNull("externalProfile") { settingsRepository.externalReceiverProfile.first() }
        return CaptureSettingsSnapshot(
            locationSource = readOrNull("locationSource") { settingsRepository.locationSource.first() },
            receiver = readOrNull("receiver") { settingsRepository.gnssReceiverSettings.first() },
            externalTcpName = readOrNull("externalTcpName") { settingsRepository.externalTcpName.first() },
            externalTcpHost = readOrNull("externalTcpHost") { settingsRepository.externalTcpHost.first() },
            externalProfileLabel = profile?.label,
            coordinateSystemSettings = readOrNull("coordinateSystem") { settingsRepository.coordinateSystemSettings.first() },
            externalProfile = profile,
            compensatedOutput = if (app.surrealar.receiver.tilt.PositionReferencePolicy.appliesTo(profile))
                readOrNull("compensatedOutput") { settingsRepository.compensatedNmeaOutput.first() } else null,
        )
    }

    /** The settings snapshot taken when the current capture started (read now if none was taken). */
    suspend fun settingsAtStart(): CaptureSettingsSnapshot? =
        readOrNull("snapshot") { startSnapshot?.await() ?: readSnapshot() }

    /**
     * Why the last [save] was refused, for the UI to show (null = no error). Set when the antenna
     * offset can't be determined: saving with a made-up 0 m would store wrong heights.
     */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    /** The UI showed [saveError]. */
    fun clearSaveError() { _saveError.value = null }

    private val _state = MutableStateFlow<ObservationSession.State>(ObservationSession.State.Idle)
    val state: StateFlow<ObservationSession.State> = _state.asStateFlow()

    /**
     * Why the capture was stopped by the app (source or receiver datum changed during the occupation),
     * for the UI to show until the next capture starts; null = not interrupted. Nothing is saved from
     * an interrupted capture.
     */
    private val _interruption = MutableStateFlow<String?>(null)
    val interruption: StateFlow<String?> = _interruption.asStateFlow()

    /**
     * The External receiver datum an external occupation is bound to. [fromSetting] = it is the datum
     * setting read at start (the user's choice); false = that read failed and the occupation is bound
     * to the datum the feed was applying.
     */
    private data class OccupationDatum(val datum: app.surrealar.settings.model.ReceiverDatum, val fromSetting: Boolean)

    /**
     * The receiver datum the current external occupation is bound to (null: internal capture or none),
     * resolved at start. Only fixes the feed normalised under it are averaged ([admitExternalFix]); a
     * change of the setting during the occupation stops the capture ([watchReceiverDatum]). So it is
     * the datum in force for every accepted observation.
     */
    private var occupationDatum: kotlinx.coroutines.Deferred<OccupationDatum>? = null
    private var datumWatchJob: Job? = null

    /**
     * Position-reference rules of the current/completed RS4 / RS4 Pro external occupation (null: internal capture
     * or none). Its [app.surrealar.receiver.tilt.CaptureReferenceGate.bound] is the reference every averaged epoch
     * had (antenna or receiver-compensated pole tip); saving reads it ([externalAntennaOffsetM]).
     */
    private var referenceGate: app.surrealar.receiver.tilt.CaptureReferenceGate? = null
    /** How long only possibly-compensated epochs may arrive before the occupation stops (tests shorten it). */
    internal var referenceNoValidEpochsMs: Long = app.surrealar.receiver.tilt.CaptureReferenceGate.DEFAULT_NO_VALID_EPOCHS_MS
    /** Watches settings that change how an external occupation is interpreted (profile, host, compensated output). */
    private var setupWatchJob: Job? = null
    /** Correction base station id the current external occupation is bound to (null: none reported yet). */
    private var occupationStation: String? = null
    /** A different base station id seen since the last epoch of [occupationStation], not yet persistent. */
    private var pendingStation: String? = null
    private var pendingStationEpochs = 0
    private var pendingStationFirstMs = 0L
    /** Receiver firmware when the occupation started (null: unknown then); the tilt rules prefer it over the live value. */
    private var occupationFirmware: String? = null

    private val _referenceWarning = MutableStateFlow<String?>(null)
    /**
     * A warning the capture dialog shows for the whole occupation (no dismiss), and that is noted in diagnostics
     * when the point is saved ([noteSavedPoint]): set for an RS4 / RS4 Pro external capture whose "Compensated NMEA
     * output" setting is Unknown ([RS4_OUTPUT_UNKNOWN_WARNING]), or when the receiver profile could not be read
     * ([PROFILE_UNREADABLE_WARNING]). The maths is unchanged (today's antenna reduction for antenna epochs).
     */
    val referenceWarning: StateFlow<String?> = _referenceWarning.asStateFlow()

    private val _positionReference = MutableStateFlow<app.surrealar.receiver.tilt.EpochReference?>(null)
    /**
     * The position reference the running RS4 / RS4 Pro occupation is bound to (null: not bound yet, not an
     * RS4-class capture, or internal), for the capture dialog.
     */
    val positionReference: StateFlow<app.surrealar.receiver.tilt.EpochReference?> = _positionReference.asStateFlow()

    private val _heightInvalidNow = MutableStateFlow(false)
    /** True while the latest epoch's vertical accuracy is an invalid marker (e.g. 999 m): epochs are not averaged. */
    val heightInvalidNow: StateFlow<Boolean> = _heightInvalidNow.asStateFlow()

    private val _heightExcludedEpochs = MutableStateFlow(0)
    /** Epochs of the current occupation excluded because their vertical accuracy was invalid ([CaptureHeightGate]). */
    val heightExcludedEpochs: StateFlow<Int> = _heightExcludedEpochs.asStateFlow()

    /**
     * The position reference of the completed (or running) external occupation: POLE_TIP when the receiver
     * delivered tilt-compensated pole-tip positions, ANTENNA otherwise; null when not an RS4 / RS4 Pro capture.
     */
    fun occupationPositionReference(): app.surrealar.receiver.tilt.EpochReference? = referenceGate?.bound

    /**
     * The height to subtract from an external capture taken with [snap]'s settings. Receiver-compensated
     * pole-tip occupations: 0 (the receiver already applied its own pole height; the app's pole height and
     * phase-centre offset are NEVER subtracted a second time). Otherwise today's pole height + phase-centre
     * offset (unchanged maths). Throws when the receiver settings could not be read.
     */
    fun externalAntennaOffsetM(snap: CaptureSettingsSnapshot?): Double {
        if (occupationPositionReference() == app.surrealar.receiver.tilt.EpochReference.POLE_TIP) return 0.0
        return app.surrealar.settings.model.AntennaSetup.resolve(
            snap?.receiver ?: error("receiver settings unavailable"),
            app.surrealar.settings.model.ExternalReceiverProfile.fromLabel(snap?.externalProfileLabel)
                ?: app.surrealar.settings.model.ExternalReceiverProfile.DEFAULT,
        ).captureOffsetM
    }

    /**
     * Capture method to store for an external capture: [CAPTURE_METHOD_POLE_TIP] for a receiver-compensated
     * pole-tip occupation, [CAPTURE_METHOD_REFERENCE_UNCONFIRMED] when the occupation's position reference is
     * unconfirmed ([unconfirmedReferenceWarning]; antenna reduction applied), else [default] (provenance; no
     * schema change).
     */
    suspend fun externalCaptureMethod(default: String): String = when {
        occupationPositionReference() == app.surrealar.receiver.tilt.EpochReference.POLE_TIP -> CAPTURE_METHOD_POLE_TIP
        unconfirmedReferenceWarning() != null -> CAPTURE_METHOD_REFERENCE_UNCONFIRMED
        else -> default
    }

    /**
     * Unconfirmed position reference (Phase 4 stabilization WP A): the [referenceWarning] of the current / completed
     * external occupation when its reference is not confirmed — RS4 / RS4 Pro with "Compensated NMEA output"
     * Unknown, or a receiver profile that could not be read at the start — and the occupation is not a confirmed
     * receiver pole tip. Null for internal captures, RS2+ / generic profiles, and RS4 captures with the setting
     * Enabled or Disabled. Read from the start snapshot (not the live settings): the epochs were classified with it.
     */
    suspend fun unconfirmedReferenceWarning(): String? {
        val gate = referenceGate ?: return null
        if (occupationPositionReference() == app.surrealar.receiver.tilt.EpochReference.POLE_TIP) return null
        val snap = settingsAtStart()
        // Product decision (2026-11-25): only genuine uncertainty needs an acknowledgment. An unreadable profile counts
        // only once the stream itself showed an IMU receiver (Emlid ETC seen); a conventional RS2+ stream (no ETC)
        // keeps its usual capture behaviour. RS4 / RS4 Pro with the output setting Unknown stays uncertain.
        val warning = if (snap?.externalProfile == null) PROFILE_UNREADABLE_WARNING.takeIf { unknownProfileEtcSeen }
            else referenceWarningFor(snap)
        // …and only when an epoch of uncertain reference was actually averaged.
        return warning?.takeIf { gate.uncertainAdmitted }
    }

    /** An unreadable-profile occupation whose stream carried Emlid ETC sentences (an IMU receiver): genuinely uncertain. */
    @Volatile private var unknownProfileEtcSeen = false

    /**
     * Acknowledgment of an unconfirmed reference, remembered for the current occupation only: [start] (a new
     * capture or Restart) clears it. A verified setting (Enabled / Disabled) never needs one; any change of
     * profile, host, firmware or the setting needs a new occupation, so a stale acknowledgment can never carry over.
     */
    private var referenceAcknowledged = false

    /** True when saving the completed capture first needs the user's explicit acknowledgment ([acknowledgeUnconfirmedReference]). */
    suspend fun referenceAcknowledgmentNeeded(): Boolean = !referenceAcknowledged && unconfirmedReferenceWarning() != null

    /** The user chose "Save as unconfirmed" for the current occupation. */
    fun acknowledgeUnconfirmedReference() {
        if (!referenceAcknowledged) DiagnosticsLogger.i(DiagTags.CAPTURE, "Capture unconfirmed position reference acknowledged" +
            (captureDiag?.let { " capture=${it.id}" } ?: ""))
        referenceAcknowledged = true
        if (_saveError.value == REFERENCE_ACK_REQUIRED_ERROR) _saveError.value = null
    }

    /**
     * The receiver datum that normalised every fix of the current/completed external capture: the
     * saved `receiverFrame`. Null for internal captures, when no capture ran, or when the datum setting
     * couldn't be read at start (provenance unknown, as before; the fixes were still all normalised
     * under one datum).
     */
    suspend fun occupationReceiverDatum(): app.surrealar.settings.model.ReceiverDatum? =
        readOrNull("occupationDatum") { occupationDatum?.await()?.takeIf { it.fromSetting }?.datum }

    init {
        // Cancel an in-progress averaging session when the provider switches after it has accepted fixes.
        // A session collecting RS2+ fixes must not continue under Internal GPS and vice versa. Before the
        // first accepted fix nothing can be mixed (the session only ever sees its own mode's provider,
        // see start()), so e.g. the Internal → External flip when the receiver finishes connecting just
        // after the dialog started an external capture must not stop it; a Complete result is kept,
        // as for a receiver-datum change after completion.
        viewModelScope.launch {
            var previousProvider: app.surrealar.gnss.source.SourceSettings.ProviderChoice? = null
            sourceSettings.activeProvider.collect { provider ->
                if (previousProvider != null && previousProvider != provider) {
                    val st = _state.value
                    val acceptedFixes = (st as? ObservationSession.State.Capturing)?.samples ?: 0
                    if (session != null && acceptedFixes > 0) {
                        // cancel() writes the outcome line; the source switch itself is logged by GNSS.
                        DiagnosticsLogger.d(DiagTags.CAPTURE) { "Capture cancelled: provider switched $previousProvider → $provider" }
                        app.surrealar.util.diagnostics.CaptureInterruptions.record(INTERRUPT_SOURCE)
                        cancel()
                        _interruption.value = SOURCE_CHANGED_MESSAGE
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
            val settings = readOrNull("captureSettings") { settingsRepository.gnssCaptureSettings.first() }
                ?: io.github.pennstatecs.geokit.gnss.capture.GnssCaptureSettings()
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
            DiagnosticsLogger.i(DiagTags.CAPTURE, "Capture cancelled: new capture started")
            captureCancelled()
        }
        stopSession()
        _state.value = ObservationSession.State.Idle
        _interruption.value = null
        // Snapshot the settings that shape the saved point NOW (UNDISPATCHED: reads start immediately).
        startSnapshot = viewModelScope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { readSnapshot() }

        val provider = sourceSettings.activeProvider.value
        // The policy is in diag.startLine() below; this adds the live provider (debug builds).
        DiagnosticsLogger.d(DiagTags.CAPTURE) { "Capture provider mode=${if (internal) "INTERNAL" else "EXTERNAL"} activeProvider=$provider" }
        // Feed the session ONLY the selected mode's provider, so internal GPS fixes can never be
        // averaged into an external capture (even with quality lowered to Single/DGPS, or while
        // External is selected but the live provider is still Internal) and vice versa.
        // Also skip the fix replayed from before this session started (the fixes flow replays its
        // last value to new subscribers) — only fixes that arrive during the session are averaged.
        val replayedBeforeStart = fixSwitchboard.fixes.replayCache.lastOrNull()
        // NMEA replay fixes (Provider.REPLAY) are never averaged into a saved point.
        // Height quality (CaptureHeightGate): epochs with an invalid vertical accuracy (e.g. 999 m) are not averaged.
        val heightGate = CaptureHeightGate { DiagnosticsLogger.w(DiagTags.CAPTURE, it) }
        _heightInvalidNow.value = false
        _heightExcludedEpochs.value = 0
        val modeFixes = fixSwitchboard.fixes.filter {
            it !== replayedBeforeStart &&
                it.provider != io.github.pennstatecs.geokit.gnss.model.Provider.REPLAY &&
                (it.provider == io.github.pennstatecs.geokit.gnss.model.Provider.INTERNAL) == internal
        }
        // The height gate runs once per epoch, on the session chain only (the diagnostics collector below reads
        // modeFixes separately and must not count epochs twice).
        val heightGatedFixes = modeFixes.filter {
            val ok = heightGate.admit(it)
            _heightInvalidNow.value = heightGate.invalidNow
            _heightExcludedEpochs.value = heightGate.excludedEpochs
            ok
        }
        // External: bind the occupation to one receiver datum (see occupationDatum / admitExternalFix).
        val snapForDatum = startSnapshot
        val datum = if (internal) null else viewModelScope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val fromSettings = try { snapForDatum?.await()?.coordinateSystemSettings?.externalReceiverDatum }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (fromSettings != null) OccupationDatum(fromSettings, fromSetting = true)
            else OccupationDatum(feedDatum.datum.value, fromSetting = false)
        }
        occupationDatum = datum
        // RS4 / RS4 Pro: per-epoch position reference (antenna / receiver pole tip / uncertain), see admitReference.
        val gate = if (internal) null else app.surrealar.receiver.tilt.CaptureReferenceGate(referenceNoValidEpochsMs)
        referenceGate = gate
        occupationStation = null
        pendingStation = null
        pendingStationEpochs = 0
        occupationFirmware = if (internal) null else tiltMonitor?.currentFirmware()
        _positionReference.value = null
        _referenceWarning.value = null
        referenceAcknowledged = false
        unknownProfileEtcSeen = false
        if (!internal) viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val snap = try { snapForDatum?.await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (startSnapshot !== snapForDatum) return@launch   // a newer capture started meanwhile
            val warning = referenceWarningFor(snap)
            _referenceWarning.value = warning
            if (warning != null) DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture position reference unconfirmed: " +
                (snap?.externalProfile?.let { "profile=${it.prefKey} output=unknown" } ?: "receiver profile unreadable") +
                "; antenna epochs use today's reduction")
        }
        var sessionRef: ObservationSession? = null
        val sessionFixes = if (datum == null) heightGatedFixes
            else heightGatedFixes
                .filter { admitExternalFix(it, datum.await().datum, sessionRef) }
                .filter { admitSetup(it, sessionRef) }
                .filter { admitReference(it, gate!!, snapForDatum, sessionRef) }
        val s = ObservationSession(viewModelScope, sessionFixes, policy)
        sessionRef = s
        session = s
        if (datum != null) datumWatchJob = viewModelScope.launch { watchReceiverDatum(datum.await().datum, s) }
        if (!internal) setupWatchJob = viewModelScope.launch { watchExternalSetup(snapForDatum, s) }
        // Diagnostics only: a separate read-only collector (the session's own flow is untouched).
        resetCaptureDiag()
        val diag = app.surrealar.util.diagnostics.CaptureDiagnostics(policy, if (internal) "INTERNAL" else "EXTERNAL")
        captureDiag = diag
        DiagnosticsLogger.i(DiagTags.CAPTURE, diag.startLine())
        captureDiagJob = viewModelScope.launch { modeFixes.collect { diag.onFix(it) } }
        // Diagnostics only: flag an antenna height setting that will make the saved height wrong.
        val snapshotAtStart = startSnapshot
        viewModelScope.launch {
            val snap = try { snapshotAtStart?.await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (snap?.locationSource == LocationSourceType.SIMULATOR) return@launch
            diag.antennaHeightLine(snap?.receiver, external = !internal)?.let { DiagnosticsLogger.w(DiagTags.GNSS, it) }
        }
        sessionForwardJob = viewModelScope.launch {
            s.state.collect { st ->
                _state.value = st
                diag.onState(st)
                // One outcome line (completed / timed out) is written by captureFinished.
                if (st is ObservationSession.State.Complete) captureFinished(diag, st)
            }
        }
        s.start()
    }

    private var lastDatumDiscardLogMs = 0L

    /**
     * Gate for an external occupation bound to [expected]: a fix is averaged only when the feed
     * normalised it under [expected] ([ReceiverFeedDatum.datumOf]).
     *
     * - Before the first accepted fix, fixes normalised under another datum are discarded: they are
     *   the ones still in flight from before a datum change made just before the capture started (the
     *   feed switches a moment after the setting, and buffered fixes keep their old normalisation).
     * - Once the occupation has accepted a fix, a fix normalised under another datum means the datum
     *   changed mid-occupation: the capture is stopped (never saved), so one point never mixes frames.
     * - A fix with no recorded datum (e.g. published untransformed after a transform error) is never
     *   averaged.
     */
    private fun admitExternalFix(
        fix: Fix,
        expected: app.surrealar.settings.model.ReceiverDatum,
        owner: ObservationSession?,
    ): Boolean {
        val used = feedDatum.datumOf(fix)
        if (used == expected) return true
        val occupied = owner != null && owner.state.value is ObservationSession.State.Capturing
        if (used != null && occupied && owner === session) {
            interruptForDatumChange("fix normalised as ${used.name}, occupation bound to ${expected.name}")
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastDatumDiscardLogMs >= 10_000L) {
            lastDatumDiscardLogMs = now
            DiagnosticsLogger.d(DiagTags.CAPTURE) {
                "Capture skipped fix: receiver datum ${used?.name ?: "unknown"}, occupation bound to ${expected.name}"
            }
        }
        return false
    }

    /**
     * Stops [owner]'s occupation when Settings → External receiver datum changes away from
     * [expected] while it is running (before it completes). A completed capture is left alone: all of
     * its fixes were normalised under [expected]. Settings that can't be read leave the per-fix gate
     * ([admitExternalFix]) as the guard.
     */
    private suspend fun watchReceiverDatum(expected: app.surrealar.settings.model.ReceiverDatum, owner: ObservationSession) {
        try {
            settingsRepository.coordinateSystemSettings
                .map { it.externalReceiverDatum }
                .distinctUntilChanged()
                .collect { now ->
                    if (now == expected || owner !== session) return@collect
                    val st = owner.state.value
                    if (st is ObservationSession.State.Complete || st is ObservationSession.State.Paused) return@collect
                    interruptForDatumChange("setting changed ${expected.name} → ${now.name}")
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture receiver datum watch unavailable: per-fix datum check only", e)
        }
    }

    private var lastReferenceExcludeLogMs = 0L

    /**
     * Correction base gate for an external occupation (all receivers, RS2+ included): a differential base station
     * id that differs from the occupation's means the RTK solution may now refer to another base (a different base
     * can shift every coordinate by its own coordinate error).
     *
     * - Empty and all-zero ids ("0", "0000": what receivers send when they report no station) are ignored, as are
     *   fixes without an id.
     * - A different id must persist for [STATION_CHANGE_MIN_EPOCHS] epochs carrying it, or for
     *   [STATION_CHANGE_MIN_MS] of GNSS time, before it counts as a change; until then its epochs are excluded (never
     *   averaged) and an epoch with the bound id again clears it (a transient id, logged).
     * - A persistent change before anything was averaged rebinds the occupation; after epochs were averaged it
     *   stops the occupation.
     */
    private fun admitSetup(fix: Fix, owner: ObservationSession?): Boolean {
        val station = normalizedStationId(fix.correctionStationId) ?: return true
        val bound = occupationStation
        if (bound == null || bound == station) {
            if (pendingStation != null) {
                DiagnosticsLogger.i(DiagTags.CAPTURE, "Capture correction base id changed briefly and returned: ignored " +
                    "epochs=$pendingStationEpochs")
                pendingStation = null
                pendingStationEpochs = 0
            }
            occupationStation = station
            return true
        }
        val atMs = fix.timeUtc.toEpochMilli()
        if (station != pendingStation) {
            if (pendingStation != null) DiagnosticsLogger.i(DiagTags.CAPTURE,
                "Capture correction base id changed briefly: ignored epochs=$pendingStationEpochs")
            pendingStation = station
            pendingStationEpochs = 0
            pendingStationFirstMs = atMs
        }
        pendingStationEpochs++
        val persistent = pendingStationEpochs >= STATION_CHANGE_MIN_EPOCHS ||
            atMs - pendingStationFirstMs >= STATION_CHANGE_MIN_MS
        if (!persistent) return false   // not averaged while it might be another base
        pendingStation = null
        pendingStationEpochs = 0
        val averaged = (owner?.state?.value as? ObservationSession.State.Capturing)?.samples ?: 0
        if (averaged == 0 || owner !== session) {
            DiagnosticsLogger.i(DiagTags.CAPTURE, "Capture correction base changed before averaging: occupation rebound")
            occupationStation = station
            return true
        }
        interrupt(INTERRUPT_CORRECTION_BASE, CORRECTION_BASE_CHANGED_MESSAGE, "correction base station changed")
        return false
    }

    /**
     * RS4 / RS4 Pro position-reference gate ([app.surrealar.receiver.tilt.CaptureReferenceGate]): each epoch is
     * classified (its own ETC + the profile / compensated-output setting read at start + firmware); only epochs of
     * the occupation's reference are averaged, possibly-compensated epochs never are, and a reference change or a
     * loss of tilt compensation stops the occupation. Other profiles (RS2+, generic) pass straight through, with no
     * wait, exactly as before.
     */
    private suspend fun admitReference(
        fix: Fix,
        gate: app.surrealar.receiver.tilt.CaptureReferenceGate,
        snap: kotlinx.coroutines.Deferred<CaptureSettingsSnapshot>?,
        owner: ObservationSession?,
    ): Boolean {
        val monitor = tiltMonitor ?: return true
        val startSettings = try { snap?.await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val profile = startSettings?.externalProfile
        // Unreadable profile: the conservative UNKNOWN-output rules (never a pole tip), not a pass-through.
        val profileKnown = profile != null
        if (profileKnown && !app.surrealar.receiver.tilt.PositionReferencePolicy.appliesTo(profile)) return true
        val output = startSettings?.compensatedOutput ?: app.surrealar.settings.model.CompensatedOutputSetting.DEFAULT
        // The fix's arrival time (recorded by the feed) pairs it with its ETC, so this chain's processing lag never
        // accumulates; no wait where the ETC cannot change the reference. Firmware: as at the occupation start
        // (a failed device-info poll mid-occupation must not change the interpretation), else the live value.
        val c = monitor.classify(fix, profile, output, firmware = occupationFirmware ?: monitor.currentFirmware(),
            profileKnown = profileKnown, waitOnlyWhenNeeded = true)
        if (owner == null || owner !== session) return false
        // Unreadable profile: an ETC in the stream shows an IMU receiver, so the reference is genuinely uncertain.
        if (!profileKnown && !unknownProfileEtcSeen &&
            c.tilt?.match?.let { it != io.github.pennstatecs.geokit.receiver.emlid.tilt.TiltMatch.NO_ETC_SEEN &&
                it != io.github.pennstatecs.geokit.receiver.emlid.tilt.TiltMatch.NO_FIX_TIME } == true) {
            unknownProfileEtcSeen = true
            _referenceWarning.value = PROFILE_UNREADABLE_WARNING
            DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture position reference unconfirmed: receiver profile unreadable and ETC seen")
        }
        // A finished occupation keeps the reference it was bound to (saving reads it): late epochs never rebind it.
        val st = owner.state.value
        if (st is ObservationSession.State.Complete || st is ObservationSession.State.Paused) return false
        val averaged = (st as? ObservationSession.State.Capturing)?.samples ?: 0
        return when (val d = gate.decide(c, averaged, System.nanoTime() / 1_000_000L)) {
            is app.surrealar.receiver.tilt.CaptureReferenceGate.Decision.Accept -> {
                if (_positionReference.value != gate.bound) {
                    _positionReference.value = gate.bound
                    DiagnosticsLogger.i(DiagTags.CAPTURE, "Capture position reference bound reference=${gate.bound} " +
                        "epoch=${c.reference} reason=${c.result?.reason} profile=${profile?.prefKey} output=${output.prefKey}")
                }
                true
            }
            is app.surrealar.receiver.tilt.CaptureReferenceGate.Decision.Exclude -> {
                val now = System.currentTimeMillis()
                if (now - lastReferenceExcludeLogMs >= 10_000L) {
                    lastReferenceExcludeLogMs = now
                    DiagnosticsLogger.i(DiagTags.CAPTURE, "Capture excluded epoch: ${d.why} reason=${c.result?.reason} excluded=${gate.excluded}")
                }
                false
            }
            is app.surrealar.receiver.tilt.CaptureReferenceGate.Decision.Stop -> {
                val message = when (d.reason) {
                    app.surrealar.receiver.tilt.CaptureReferenceGate.StopReason.REFERENCE_CHANGED -> REFERENCE_CHANGED_MESSAGE
                    app.surrealar.receiver.tilt.CaptureReferenceGate.StopReason.TILT_LOST -> TILT_LOST_MESSAGE
                    app.surrealar.receiver.tilt.CaptureReferenceGate.StopReason.MAY_BE_COMPENSATED -> MAY_BE_COMPENSATED_MESSAGE
                    app.surrealar.receiver.tilt.CaptureReferenceGate.StopReason.FIRMWARE_UNKNOWN -> FIRMWARE_UNKNOWN_MESSAGE
                }
                interrupt(d.reason.code, message, d.detail)
                false
            }
        }
    }

    /**
     * Stops [owner]'s external occupation (before it completes) when a setting that changes how its epochs are
     * interpreted changes: the receiver profile (antenna offset, tilt rules), the receiver host (another
     * receiver), or "Compensated NMEA output". Antenna / pole-height edits do NOT stop it: the reduction is bound
     * to the start snapshot and the streamed epochs do not change; they are logged only.
     */
    private suspend fun watchExternalSetup(snap: kotlinx.coroutines.Deferred<CaptureSettingsSnapshot>?, owner: ObservationSession) {
        val start = try { snap?.await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } ?: return
        fun running() = owner === session && owner.state.value.let {
            it !is ObservationSession.State.Complete && it !is ObservationSession.State.Paused
        }
        coroutineScopeLaunch {
            start.externalProfile?.let { p0 ->
                launch {
                    safeWatch("profile") {
                        settingsRepository.externalReceiverProfile.distinctUntilChanged().collect { p ->
                            if (p != p0 && running()) interrupt(INTERRUPT_PROFILE, PROFILE_CHANGED_MESSAGE, "profile ${p0.prefKey} → ${p.prefKey}")
                        }
                    }
                }
            }
            start.externalTcpHost?.let { h0 ->
                launch {
                    safeWatch("host") {
                        settingsRepository.externalTcpHost.distinctUntilChanged().collect { h ->
                            if (h != null && h != h0 && running()) interrupt(INTERRUPT_RECEIVER, RECEIVER_CHANGED_MESSAGE, "receiver host changed")
                        }
                    }
                }
            }
            start.compensatedOutput?.let { o0 ->
                launch {
                    safeWatch("compensatedOutput") {
                        settingsRepository.compensatedNmeaOutput.distinctUntilChanged().collect { o ->
                            if (o != o0 && running()) interrupt(INTERRUPT_OUTPUT_SETTING, OUTPUT_SETTING_CHANGED_MESSAGE, "compensated output ${o0.prefKey} → ${o.prefKey}")
                        }
                    }
                }
            }
            start.receiver?.let { r0 ->
                launch {
                    safeWatch("antenna") {
                        settingsRepository.gnssReceiverSettings.distinctUntilChanged().collect { r ->
                            if (r != r0 && running()) DiagnosticsLogger.i(DiagTags.CAPTURE,
                                "Capture continues: antenna settings changed mid-occupation; the saved point uses the settings from the start")
                        }
                    }
                }
            }
        }
    }

    private suspend fun coroutineScopeLaunch(block: kotlinx.coroutines.CoroutineScope.() -> Unit) =
        kotlinx.coroutines.coroutineScope { block() }

    private suspend inline fun safeWatch(what: String, watch: () -> Unit) {
        try { watch() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture setup watch unavailable what=$what", e)
        }
    }

    /** Stops the current capture with [message] (nothing saved; a completed result is never reached here). */
    private fun interrupt(code: String, message: String, detail: String) {
        if (session == null) return
        DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture stopped: $code ($detail)")
        app.surrealar.util.diagnostics.CaptureInterruptions.record(code)
        cancel()
        _interruption.value = message
    }

    private fun interruptForDatumChange(detail: String) {
        if (session == null) return
        DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture stopped: receiver datum changed during the occupation ($detail)")
        app.surrealar.util.diagnostics.CaptureInterruptions.record(INTERRUPT_DATUM)
        cancel()
        _interruption.value = DATUM_CHANGED_MESSAGE
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
        DiagnosticsLogger.i(DiagTags.CAPTURE, diag.rejectedSummary(r.samples))
        val line = diag.outcomeLine(if (st.requirementsMet) "completed" else "timed out", r.samples,
            java.time.Duration.between(r.startedAt, r.endedAt).seconds, r.rtkStatus, r.hAccM, r.vAccM)
        if (st.requirementsMet) DiagnosticsLogger.i(DiagTags.CAPTURE, line) else DiagnosticsLogger.w(DiagTags.CAPTURE, line)
        if (st.requirementsMet) completedNotSavedLine = line.replace("Capture completed", "Capture completed, not saved")
        else app.surrealar.util.diagnostics.CaptureLog.append(line)
    }

    private fun captureCancelled() {
        val diag = captureDiag ?: return
        if (!captureOutcomeLogged) {
            val cap = state.value as? ObservationSession.State.Capturing
            DiagnosticsLogger.i(DiagTags.CAPTURE, diag.rejectedSummary(cap?.samples))
            val line = diag.outcomeLine("cancelled", cap?.samples, cap?.elapsedSec?.toLong(),
                cap?.rtkStatus?.let { io.github.pennstatecs.geokit.gnss.model.RtkStatus.fromPrefKey(it) }, cap?.hAccM, cap?.vAccM)
            DiagnosticsLogger.i(DiagTags.CAPTURE, line)
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

    /**
     * Notes in diagnostics (event log + captures.log, no coordinates) that point [pointId] was saved while
     * [referenceWarning] was shown, so its unconfirmed position reference can be traced later. No-op otherwise.
     */
    fun noteSavedPoint(pointId: Any) {
        val warning = _referenceWarning.value ?: return
        val why = if (warning == PROFILE_UNREADABLE_WARNING) "receiver profile unreadable" else "RS4 Compensated NMEA output unknown"
        val line = "Capture saved with unconfirmed position reference id=$pointId" +
            (captureDiag?.let { " capture=${it.id}" } ?: "") + " reason=\"$why\" heights=antenna reduction applied" +
            " acknowledged=$referenceAcknowledged captureMethod=$CAPTURE_METHOD_REFERENCE_UNCONFIRMED"
        DiagnosticsLogger.w(DiagTags.CAPTURE, line)
        app.surrealar.util.diagnostics.CaptureLog.append(line)
    }

    /** Clears a shown interruption message (e.g. the user switched to another way of saving). */
    fun clearInterruption() { _interruption.value = null }

    fun cancel() {
        // A running session with diagnostics gets its "Capture cancelled" outcome line from captureCancelled().
        if (session != null && (captureDiag == null || captureOutcomeLogged)) DiagnosticsLogger.i(DiagTags.CAPTURE, "Capture cancelled")
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
        datumWatchJob?.cancel()
        datumWatchJob = null
        setupWatchJob?.cancel()
        setupWatchJob = null
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
            DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture save blocked: requirements not met, capture timed out" +
                (captureDiag?.let { " capture=${it.id}" } ?: ""))
            return
        }
        // Unconfirmed position reference: never saved without the user's explicit acknowledgment (the dialog asks).
        if (referenceAcknowledgmentNeeded()) {
            DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture save blocked: unconfirmed position reference not acknowledged" +
                (captureDiag?.let { " capture=${it.id}" } ?: ""))
            _saveError.value = REFERENCE_ACK_REQUIRED_ERROR
            return
        }
        _saveError.value = null
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
        // sit at different heights); simulator/replay has none. If the offset can't be determined the
        // save is refused: a made-up 0 m would silently store the antenna height as the ground mark.
        val antennaHeightM = try {
            when (src) {
                LocationSourceType.SIMULATOR -> 0.0
                // Pole height to the bottom of the receiver + the receiver model's phase-centre offset.
                // Receiver-compensated pole tip: 0 (never subtracted twice). See externalAntennaOffsetM.
                LocationSourceType.EXTERNAL  -> externalAntennaOffsetM(snap)
                LocationSourceType.INTERNAL  -> (snap?.receiver ?: error("receiver settings unavailable")).internalAntennaHeightM
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogger.e(DiagTags.CAPTURE, "Capture save blocked: antenna offset unavailable source=${src.name}" +
                (captureDiag?.let { " capture=${it.id}" } ?: ""), e)
            _saveError.value = ANTENNA_OFFSET_ERROR
            return
        }

        // Frame the external receiver delivered: the receiver datum that normalised every averaged fix
        // (the occupation is bound to one datum and stopped if it changes). Provenance only: the feed
        // already normalised the fixes to WGS84. Null when not an external receiver capture.
        val receiverFrame = if (src == LocationSourceType.EXTERNAL) occupationReceiverDatum()?.frameToken() else null

        // Build → validate → save is centralized in the use case (it rejects an invalid coordinate
        // rather than persisting it). The source/provider/device mapping above stays here because it
        // reads UI/settings state.
        val saved = captureCoordinate(
            name           = name,
            note           = note,
            color          = color,
            iconId         = iconId,
            provider       = provider,
            result         = finished,
            captureMethod  = if (src == LocationSourceType.EXTERNAL) externalCaptureMethod(captureMethod) else captureMethod,
            sourceDevice   = sourceDevice,
            antennaHeightM = antennaHeightM,
            receiverFrame  = receiverFrame,
        )

        // Id only: point names stay out of the event log.
        DiagnosticsLogger.i(DiagTags.CAPTURE, "Coordinate saved id=${saved.id}" +
            (captureDiag?.let { " capture=${it.id}" } ?: "") + " source=${provider.name} " +
            "samples=${finished.samples} hAccM=${finished.hAccM?.let { "%.3f".format(java.util.Locale.US, it) } ?: "unknown"} status=${finished.rtkStatus}")
        captureDiag?.let { d ->
            app.surrealar.util.diagnostics.CaptureLog.append(d.outcomeLine("saved", finished.samples,
                java.time.Duration.between(finished.startedAt, finished.endedAt).seconds, finished.rtkStatus,
                finished.hAccM, finished.vAccM, pointId = saved.id))
        }
        noteSavedPoint(saved.id)
        resetCaptureDiag()
    }

    companion object {
        /** [saveError] when the antenna / phase-centre offset can't be read (same text as R.string.capture_antenna_offset_unavailable). */
        const val ANTENNA_OFFSET_ERROR = "Can't save: the antenna height setting couldn't be read. Try again."

        /** [interruption] when Settings → External receiver datum changed during an external occupation. */
        const val DATUM_CHANGED_MESSAGE = "Capture stopped: the receiver datum changed during the occupation. Start the capture again."

        /** [interruption] when the GNSS source (provider) switched during a capture. */
        const val SOURCE_CHANGED_MESSAGE = "Capture stopped: the GNSS source changed during the occupation. Start the capture again."

        /** RS4 / RS4 Pro: the position reference changed (antenna ↔ pole tip ↔ uncertain) mid-occupation. */
        const val REFERENCE_CHANGED_MESSAGE = "Capture stopped: the receiver's position reference changed (antenna / pole tip) during the occupation. Restart the capture."

        /** RS4 / RS4 Pro: tilt compensation lost during a pole-tip occupation. */
        const val TILT_LOST_MESSAGE = "Capture stopped: tilt compensation was lost during the occupation. Wait for tilt to be active again, then restart the capture."

        /** RS4 / RS4 Pro: only possibly-compensated epochs arrived. */
        const val MAY_BE_COMPENSATED_MESSAGE = "Receiver may be sending tilt-compensated positions; set 'Compensated NMEA output' in Settings → Receiver."

        /** The receiver profile changed during an external occupation. */
        const val PROFILE_CHANGED_MESSAGE = "Capture stopped: the receiver profile changed during the occupation. Restart the capture."

        /** The receiver host changed during an external occupation. */
        const val RECEIVER_CHANGED_MESSAGE = "Capture stopped: the receiver connection changed during the occupation. Restart the capture."

        /** "Compensated NMEA output" changed during an RS4 / RS4 Pro occupation. */
        const val OUTPUT_SETTING_CHANGED_MESSAGE = "Capture stopped: 'Compensated NMEA output' changed during the occupation. Restart the capture."

        /** The correction base station changed during an external occupation. */
        const val CORRECTION_BASE_CHANGED_MESSAGE = "Capture stopped: the correction base station changed during the occupation. Restart the capture."

        /** RS4 / RS4 Pro pole-tip occupation: the firmware is no longer known to support compensated output (not a tilt loss). */
        const val FIRMWARE_UNKNOWN_MESSAGE = "Capture stopped: the receiver firmware could not be confirmed (34.7 or later is needed for compensated positions). Check the receiver connection, then restart the capture."

        /** [referenceWarning]: RS4 / RS4 Pro with "Compensated NMEA output" Unknown (heights keep today's reduction). */
        const val RS4_OUTPUT_UNKNOWN_WARNING = "RS4: set 'Compensated NMEA output' in Settings → Receiver — if Emlid Flow streams compensated positions, heights would be reduced twice."

        /** [referenceWarning]: the receiver profile could not be read at the start. */
        const val PROFILE_UNREADABLE_WARNING = "Receiver profile couldn't be read: heights use the antenna reduction. For an RS4, set 'Compensated NMEA output' in Settings → Receiver — if Emlid Flow streams compensated positions, heights would be reduced twice."

        /** A different correction base id must be carried by this many epochs before it counts as a base change. */
        const val STATION_CHANGE_MIN_EPOCHS = 3
        /** … or persist for this long (GNSS time), whichever comes first (1 Hz streams). */
        const val STATION_CHANGE_MIN_MS = 1_000L

        /** Trimmed station id; null for missing, empty or all-zero ids ("0", "0000": no station reported). */
        internal fun normalizedStationId(raw: String?): String? {
            val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (s.all { it == '0' }) null else s
        }

        /** The [referenceWarning] for an external capture started with [snap] (null snapshot = profile unreadable). */
        internal fun referenceWarningFor(snap: CaptureSettingsSnapshot?): String? {
            // Unreadable profile: no standing warning up front (RS2+ streams carry no ETC); admitReference raises
            // PROFILE_UNREADABLE_WARNING once ETC shows an IMU receiver.
            val profile = snap?.externalProfile ?: return null
            if (!app.surrealar.receiver.tilt.PositionReferencePolicy.appliesTo(profile)) return null
            val output = snap.compensatedOutput ?: app.surrealar.settings.model.CompensatedOutputSetting.UNKNOWN
            return if (output == app.surrealar.settings.model.CompensatedOutputSetting.UNKNOWN) RS4_OUTPUT_UNKNOWN_WARNING else null
        }

        /**
         * captureMethod stored for an external capture whose epochs were receiver-compensated pole-tip positions
         * (Phase 4 WP C). The pole height / phase-centre offset was not subtracted (antennaHeightM stays null).
         * [app.surrealar.domain.model.CaptureMethod.EXTERNAL_GNSS_POLE_TIP].
         */
        val CAPTURE_METHOD_POLE_TIP: String get() = app.surrealar.domain.model.CaptureMethod.EXTERNAL_GNSS_POLE_TIP.storageValue

        /**
         * captureMethod stored for an external capture saved with an unconfirmed position reference (Phase 4
         * stabilization WP A): antenna reduction applied, reference unconfirmed.
         * [app.surrealar.domain.model.CaptureMethod.EXTERNAL_GNSS_REFERENCE_UNCONFIRMED].
         */
        val CAPTURE_METHOD_REFERENCE_UNCONFIRMED: String
            get() = app.surrealar.domain.model.CaptureMethod.EXTERNAL_GNSS_REFERENCE_UNCONFIRMED.storageValue

        /** [saveError] when [save] was called for an unconfirmed reference that was not acknowledged. */
        const val REFERENCE_ACK_REQUIRED_ERROR = "Can't save yet: confirm how to save this point (position reference not confirmed)."

        /** Title of the acknowledgment dialog shown on Save for an unconfirmed reference. */
        const val REFERENCE_ACK_TITLE = "Position reference not confirmed"

        /**
         * Body of the acknowledgment dialog for [warning] (one of [RS4_OUTPUT_UNKNOWN_WARNING] /
         * [PROFILE_UNREADABLE_WARNING]). States what was applied, what could be wrong, how the point is marked, and
         * that a verified setting needs a new capture. Claims nothing about accuracy.
         */
        fun referenceAckMessage(warning: String): String {
            val why = if (warning == PROFILE_UNREADABLE_WARNING)
                "The receiver profile couldn't be read when this capture started, so the app can't confirm which point the receiver streamed."
            else
                "\"Compensated NMEA output\" is set to Unknown for this RS4 receiver, so the app can't confirm which point the receiver streamed."
            return why + "\n\n" +
                "This point is saved with the antenna reduction (pole height + antenna offset subtracted). " +
                "If Emlid Flow was streaming tilt-compensated positions, the position is the pole tip and the height " +
                "has been reduced twice (too low by about the pole height).\n\n" +
                "It will be marked \"Unconfirmed\" in Point Details and exports. To confirm, set \"Compensated NMEA output\" " +
                "in Settings → Receiver and capture again (opening the settings discards this result)."
        }

        // Interruption codes (diagnostics counts).
        const val INTERRUPT_SOURCE = "source_changed"
        const val INTERRUPT_DATUM = "receiver_datum_changed"
        const val INTERRUPT_PROFILE = "receiver_profile_changed"
        const val INTERRUPT_RECEIVER = "receiver_host_changed"
        const val INTERRUPT_OUTPUT_SETTING = "compensated_output_changed"
        const val INTERRUPT_CORRECTION_BASE = "correction_base_changed"
    }
}
