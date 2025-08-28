package com.example.surveyingapp.data.location

import com.example.surveyingapp.data.location.fused.FusedSource
import com.example.surveyingapp.data.location.nmea.NmeaSource
import com.example.surveyingapp.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Manages active location source based on DataStore settings and exposes unified fix & status flows. */
class LocationSourceManager(
    private val settings: SettingsRepository,
    private val fused: FusedSource,
    private val nmea: NmeaSource,
    private val scope: CoroutineScope
) {
    private val _fixes = MutableSharedFlow<Fix>(replay = 1, extraBufferCapacity = 32)
    val fixes: Flow<Fix> = _fixes.asSharedFlow()

    private val _status = MutableStateFlow<LocationStatus>(LocationStatus.Idle)
    val status: StateFlow<LocationStatus> = _status

    private var activeJob: Job? = null
    private var nmeaStatusJob: Job? = null
    private var lastSource: String = "internal"

    init { scope.launch { monitorSettings() } }

    private suspend fun monitorSettings() {
        combine(
            settings.locationSource,
            settings.externalConnType,
            settings.externalBtAddress,
            settings.externalTcpHost,
            settings.externalTcpPort
        ) { src, _, _, _, _ -> src }
            .distinctUntilChanged()
            .collect { src ->
                if (src != lastSource) switchSource(src)
            }
    }

    private fun switchSource(source: String) {
        activeJob?.cancel()
        if (source == "external") startExternal() else startInternal()
        lastSource = source
    }

    private fun startInternal() {
        nmeaStatusJob?.cancel()
        _status.value = LocationStatus.Streaming
        activeJob = scope.launch(Dispatchers.IO) {
            fused.fixes().collect { _fixes.emit(it) }
        }
    }

    private fun startExternal() {
        nmeaStatusJob?.cancel()
        nmeaStatusJob = scope.launch { nmea.status.collect { _status.value = it } }
        _status.value = LocationStatus.Connecting(1)
        activeJob = scope.launch(Dispatchers.IO) {
            nmea.fixes().collect { _fixes.emit(it) }
        }
    }
}
