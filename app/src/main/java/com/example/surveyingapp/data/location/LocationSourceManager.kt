package com.example.surveyingapp.data.location

import com.example.surveyingapp.data.location.fused.FusedSource
import com.example.surveyingapp.data.location.nmea.NmeaSource
import com.example.surveyingapp.data.settings.LocationSettings
import com.example.surveyingapp.data.settings.LocationSourceType
import com.example.surveyingapp.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Manages active location source based on DataStore settings and exposes unified fix & status flows.
 * Reacts immediately to:
 *  - Source change (internal <-> external)
 *  - External connection parameter changes (BT/TCP host/port/device)
 */
class LocationSourceManager(
    private val settings: SettingsRepository,
    private val fused: FusedSource,
    private val nmea: NmeaSource,
    private val scope: CoroutineScope
) {
    private val _fixes = kotlinx.coroutines.flow.MutableSharedFlow<Fix>(replay = 1, extraBufferCapacity = 32)
    val fixes: kotlinx.coroutines.flow.Flow<Fix> = _fixes

    private val _status = kotlinx.coroutines.flow.MutableStateFlow<LocationStatus>(LocationStatus.Idle)
    val status: kotlinx.coroutines.flow.StateFlow<LocationStatus> = _status

    private var activeJob: Job? = null
    private var nmeaStatusJob: Job? = null
    private var lastSettings: LocationSettings? = null

    init { observeSettings() }

    private fun observeSettings() {
        scope.launch(Dispatchers.Default) {
            settings.locationSettings.collectLatest { newCfg ->
                val old = lastSettings
                lastSettings = newCfg
                if (old == null) {
                    // First load
                    if (newCfg.source == LocationSourceType.EXTERNAL) startExternal() else startInternal()
                    return@collectLatest
                }
                // Source switched
                if (old.source != newCfg.source) {
                    if (newCfg.source == LocationSourceType.EXTERNAL) startExternal() else startInternal()
                    return@collectLatest
                }
                // If external and connection parameters changed, restart external stream
                if (newCfg.source == LocationSourceType.EXTERNAL && externalParamsChanged(old, newCfg)) {
                    restartExternal()
                }
            }
        }
    }

    private fun externalParamsChanged(a: LocationSettings, b: LocationSettings): Boolean =
        a.connectionType != b.connectionType ||
        a.btDeviceAddress != b.btDeviceAddress ||
        a.tcpHost != b.tcpHost ||
        a.tcpPort != b.tcpPort

    private fun restartExternal() {
        activeJob?.cancel()
        nmeaStatusJob?.cancel()
        startExternal()
    }

    private fun startInternal() {
        activeJob?.cancel(); nmeaStatusJob?.cancel()
        _status.value = LocationStatus.Streaming
        activeJob = scope.launch(Dispatchers.IO) {
            fused.fixes().collect { _fixes.emit(it) }
        }
    }

    private fun startExternal() {
        activeJob?.cancel(); nmeaStatusJob?.cancel()
        _status.value = LocationStatus.Connecting(1)
        nmeaStatusJob = scope.launch { nmea.status.collect { _status.value = it } }
        activeJob = scope.launch(Dispatchers.IO) {
            nmea.fixes().collect { _fixes.emit(it) }
        }
    }
}
