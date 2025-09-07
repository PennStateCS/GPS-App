package com.example.surveyingapp.domain.location

import android.location.GnssStatus
import android.util.Log
import com.example.surveyingapp.data.location.fused.FusedSource
import com.example.surveyingapp.data.location.nmea.NmeaSource
import com.example.surveyingapp.domain.model.*
import com.example.surveyingapp.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LocationSourceManager
 *
 * Orchestrates internal (Fused) vs external (RS2+ via NMEA) sources, with seamless fallback to
 * the internal source while the external is connecting or temporarily unavailable.
 *
 * Key guarantees:
 * - At most ONE primary collector (internal OR external) at any time.
 * - Fallback fused collector is used ONLY while external is not yet streaming.
 * - Source transitions are serialized via a Mutex to prevent race conditions.
 * - Public `statusFlow` maintains existing semantics (Streaming/Connecting/Error/Idle).
 * - New `activeSourceFlow` tells the UI which source is currently driving emitted Fixes.
 *
 */
class LocationSourceManager(
    private val settings: SettingsRepository,
    private val fused: FusedSource,
    private val nmea: NmeaSource,
    private val scope: CoroutineScope
) {
    private val managerTag = "LocationSourceManager"

    // Emits latest fix (external preferred when available).
    private val _fixes = MutableSharedFlow<Fix>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val fixFlow: Flow<Fix> = _fixes

    // Overall stream state (kept backward compatible).
    private val _status = MutableStateFlow<LocationStatus>(LocationStatus.Idle)
    val statusFlow: StateFlow<LocationStatus> = _status

    // Optional GNSS status from internal provider only.
    private val _gnssStatus = MutableStateFlow<GnssStatus?>(null)
    val gnssStatusFlow: StateFlow<GnssStatus?> = _gnssStatus

    // External NMEA diagnostics (surfaced directly from NmeaSource).
    val skyFlow: StateFlow<NmeaSource.SkySnapshot> get() = nmea.skyFlow
    val externalAttemptCount: StateFlow<Int> get() = nmea.attemptCount
    val externalLastError: StateFlow<String?> get() = nmea.lastError
    val externalConnectionType: StateFlow<NmeaSource.ConnectionType> get() = nmea.currentType
    val externalRawLines: SharedFlow<String> get() = nmea.recentRaw

    // New: active source indicator for the UI (does NOT change LocationStatus API).
    private val _activeSource = MutableStateFlow<LocationSourceType?>(null)
    val activeSourceFlow: StateFlow<LocationSourceType?> = _activeSource.asStateFlow()

    // Jobs
    private var activeJob: Job? = null          // The *primary* stream (internal OR external)
    private var nmeaStatusJob: Job? = null      // Mirrors NMEA status & manages fallback
    private var fusedGnssJob: Job? = null       // Optional GNSS status collector for internal
    private var fallbackJob: Job? = null        // Fused fallback while waiting for external
    private var lastSettings: LocationSettings? = null

    // Whether we currently prefer external stream (after first external fix).
    private var preferExternal: Boolean = false

    // Serialize all start/stop/restart transitions to avoid races.
    private val transitionMutex = Mutex()

    init {
        observeSettings()
    }

    private fun observeSettings() {
        scope.launch(Dispatchers.Default) {
            settings.locationSettings
                .distinctUntilChanged()
                .collectLatest { newCfg ->
                    val old = lastSettings
                    lastSettings = newCfg

                    Log.d(managerTag, "settings changed: source=${newCfg.source} conn=${newCfg.connectionType} host=${newCfg.tcpHost}:${newCfg.tcpPort} bt=${newCfg.btDeviceAddress}")

                    if (old == null) {
                        when (newCfg.source) {
                            LocationSourceType.EXTERNAL -> startExternal()
                            LocationSourceType.INTERNAL -> startInternal()
                        }
                        return@collectLatest
                    }

                    if (old.source != newCfg.source) {
                        Log.d(managerTag, "switching source to ${newCfg.source}")
                        when (newCfg.source) {
                            LocationSourceType.EXTERNAL -> startExternal()
                            LocationSourceType.INTERNAL -> startInternal()
                        }
                        return@collectLatest
                    }

                    if (newCfg.source == LocationSourceType.EXTERNAL && externalParamsChanged(old, newCfg)) {
                        Log.d(managerTag, "external params changed; restarting external")
                        restartExternal()
                    }
                }
        }
    }

    private fun externalParamsChanged(a: LocationSettings, b: LocationSettings): Boolean =
        a.connectionType != b.connectionType ||
                (a.btDeviceAddress ?: "") != (b.btDeviceAddress ?: "") ||
                (a.tcpHost?.trim()?.lowercase() ?: "") != (b.tcpHost?.trim()?.lowercase() ?: "") ||
                (a.tcpPort ?: -1) != (b.tcpPort ?: -1)

    private fun restartExternal() {
        scope.launch {
            transitionMutex.withLock {
                cancelJobSafely(activeJob); activeJob = null
                cancelJobSafely(nmeaStatusJob); nmeaStatusJob = null
                // Keep fallback running if it is, we'll manage inside startExternal
                internalStartExternal()
            }
        }
    }

    private fun startInternal() {
        scope.launch {
            transitionMutex.withLock {
                // Stop everything external-related first
                cancelJobSafely(activeJob); activeJob = null
                cancelJobSafely(nmeaStatusJob); nmeaStatusJob = null
                cancelJobSafely(fallbackJob);  fallbackJob  = null
                cancelJobSafely(fusedGnssJob); fusedGnssJob = null
                preferExternal = false
                _activeSource.value = LocationSourceType.INTERNAL

                _status.value = LocationStatus.Streaming
                Log.d(managerTag, "startInternal: Streaming from fused")

                // If FusedSource exposes GNSS status, collect it here.
                fusedGnssJob = scope.launch(Dispatchers.IO) {
                    // Example placeholder:
                    // fused.gnssStatus().collect { _gnssStatus.value = it }
                }

                // Primary fused fixes
                activeJob = scope.launch(Dispatchers.IO) {
                    runCatching {
                        fused.fixes().collect { fix ->
                            _fixes.tryEmit(fix)
                        }
                    }.onFailure { t ->
                        if (t is CancellationException) return@onFailure
                        Log.w(managerTag, "startInternal error: ${t.message}")
                        _status.value = LocationStatus.Error(t.message ?: "internal source error")
                    }
                }
            }
        }
    }

    private fun startExternal() {
        scope.launch {
            transitionMutex.withLock {
                internalStartExternal()
            }
        }
    }

    /**
     * Must be called inside transitionMutex.
     * Sets up external stream + status mirror + fallback.
     */
    private fun internalStartExternal() {
        Log.d(managerTag, "startExternal: begin")

        // Stop primary and internal-only GNSS status; let fallback continue or (re)start below.
        cancelJobSafely(activeJob); activeJob = null
        cancelJobSafely(fusedGnssJob); fusedGnssJob = null

        _gnssStatus.value = null // external mode: internal-only GNSS status cleared
        _status.value = LocationStatus.Connecting(1)
        _activeSource.value = null               // unknown until first external fix or fallback engaged
        preferExternal = false

        // Mirror NMEA status to UI and control fallback
        cancelJobSafely(nmeaStatusJob)
        nmeaStatusJob = scope.launch(Dispatchers.Default) {
            nmea.status.collect { s ->
                Log.d(managerTag, "NMEA status: $s (preferExternal=$preferExternal)")
                when (s) {
                    is LocationStatus.Streaming -> {
                        // External transport is healthy; once first fix arrives we'll promote.
                        // Keep status as Streaming to avoid UI flicker.
                        _status.value = LocationStatus.Streaming
                    }
                    is LocationStatus.Connecting -> {
                        // While connecting, ensure fallback is active and report Streaming (from fallback).
                        ensureFallbackRunning()
                        _status.value = LocationStatus.Streaming
                    }
                    is LocationStatus.Error -> {
                        // Keep fallback streaming; surface error if you want via a separate UI element.
                        ensureFallbackRunning()
                        // Do NOT flip _status to Error, to avoid constant flicker while we have fallback fixes.
                        // Optionally, you could set Error when no fallback is available.
                    }
                    LocationStatus.Idle -> {
                        ensureFallbackRunning()
                        _status.value = LocationStatus.Streaming
                    }
                }
            }
        }

        // Primary external fixes (promote on first external fix)
        cancelJobSafely(activeJob)
        activeJob = scope.launch(Dispatchers.IO) {
            runCatching {
                nmea.fixes().collect { fix ->
                    if (!preferExternal) {
                        Log.d(managerTag, "Promoting external on first fix; stopping fallback")
                        preferExternal = true
                        _activeSource.value = LocationSourceType.EXTERNAL
                        cancelJobSafely(fallbackJob); fallbackJob = null
                        _status.value = LocationStatus.Streaming
                    }
                    _fixes.tryEmit(fix)
                }
            }.onFailure { t ->
                if (t is CancellationException) return@onFailure
                Log.w(managerTag, "external stream error: ${t.message}")
                preferExternal = false
                _activeSource.value = LocationSourceType.INTERNAL // we’ll keep fallback serving
                ensureFallbackRunning()
                _status.value = LocationStatus.Streaming
            }
        }

        // Start initial fallback while waiting for external
        ensureFallbackRunning()
    }

    /** Ensure fused fallback is running while preferExternal is false. */
    private fun ensureFallbackRunning() {
        if (preferExternal) return
        if (fallbackJob?.isActive == true) return

        Log.d(managerTag, "Starting fused fallback (external not yet streaming)")
        cancelJobSafely(fallbackJob)
        fallbackJob = scope.launch(Dispatchers.IO) {
            runCatching {
                fused.fixes().collect { fix ->
                    // Only emit from fallback while we have not promoted the external
                    if (!preferExternal) {
                        _activeSource.value = LocationSourceType.INTERNAL
                        _fixes.tryEmit(fix)
                    }
                }
            }.onFailure { t ->
                if (t is CancellationException) return@onFailure
                Log.w(managerTag, "fallback fused error: ${t.message}")
                // If fallback fails too, we can finally expose an Error
                _status.value = LocationStatus.Error(t.message ?: "fallback internal error")
            }
        }
    }

    /** Cancel a Job without throwing if already cancelled. */
    private fun cancelJobSafely(job: Job?) {
        try {
            job?.cancel()
        } catch (_: CancellationException) {
            // Normal during transitions
        } catch (t: Throwable) {
            Log.w(managerTag, "cancelJobSafely non-fatal: ${t.message}")
        }
    }
}

