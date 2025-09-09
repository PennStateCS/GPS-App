package com.example.surveyingapp.gnss.bus.adapters

import com.example.surveyingapp.gnss.bus.SourceAdapter
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SkySnapshot
import com.example.surveyingapp.gnss.satellites.SatelliteInventory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Adapts the RS2+ NMEA pipeline into two streams:
 *  - fixes: normalized position observations
 *  - sky:   smoothed SNR and per-constellation tallies
 *
 * This class owns no UI and no settings. Start/stop are explicit so
 * the lifecycle can be tied to connection state.
 */
class ExternalAdapter(
    private val scope: CoroutineScope,
    private val nmea: NmeaSource,                 // bridge to your existing NMEA accumulator
    private val inv: SatelliteInventory           // shared inventory for SNR smoothing
) : SourceAdapter {

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    private val _sky = MutableStateFlow(SkySnapshot(emptyMap(), emptyMap(), emptyMap()))
    val sky: StateFlow<SkySnapshot> = _sky.asStateFlow()

    private var jobFixes: Job? = null
    private var jobGsv: Job? = null

    fun start() {
        if (jobFixes != null || jobGsv != null) return

        jobFixes = scope.launch {
            nmea.parsedFixes().collect { _fixes.emit(it) }
        }
        jobGsv = scope.launch {
            nmea.gsvStream().collect { gsv ->
                _sky.value = inv.consume(gsv)
            }
        }
        nmea.start() // no-op if already started
    }

    fun stop() {
        jobFixes?.cancel(); jobFixes = null
        jobGsv?.cancel(); jobGsv = null
        nmea.stop()
        _sky.value = SkySnapshot(emptyMap(), emptyMap(), emptyMap())
    }
}

/**
 * Small surface area for an RS2 NMEA source. This is implemented by NmeaSourceBridge
 * using your existing FixAccumulator/NmeaParser so we don't rewrite parsers here.
 */
interface NmeaSource {
    fun start()
    fun stop()
    fun parsedFixes(): SharedFlow<Fix>
    fun gsvStream(): SharedFlow<GsvMessage>
}

/** Minimal GSV message for the satellite inventory. */
data class GsvMessage(
    val constellation: String,
    val entries: List<GsvEntry>
)
data class GsvEntry(
    val svid: Int,
    val elevationDeg: Int?,
    val azimuthDeg: Int?,
    val snrDbHz: Double,
    val usedInFix: Boolean
)
