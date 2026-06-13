package com.example.surveyingapp.gnss.bus.adapters

import com.example.surveyingapp.gnss.bus.SourceAdapter
import com.example.surveyingapp.gnss.bus.SkyProvider
import com.example.surveyingapp.gnss.bus.Startable
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SkySnapshot
import com.example.surveyingapp.gnss.satellites.SatelliteInventory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Adapts the internal GPS [NmeaSource] into normalized [Fix] objects and a satellite sky feed.
 *
 * Implements [SkyProvider] so the satellite sky view works the same way as with [ExternalAdapter].
 */
class InternalAdapter(
    private val scope: CoroutineScope,
    private val nmea: NmeaSource,
    private val inv: SatelliteInventory
) : SourceAdapter, SkyProvider, Startable {

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    private val _sky = MutableStateFlow(SkySnapshot())
    override val sky: StateFlow<SkySnapshot> = _sky.asStateFlow()

    private var collectJob: Job? = null
    private var skyJob: Job? = null

    override fun start() {
        if (collectJob != null) return // Already started
        android.util.Log.d("InternalAdapter", "Starting internal GPS adapter")

        // Clear stale satellites from previous provider
        inv.reset()

        nmea.start()

        collectJob = scope.launch {
            nmea.parsedFixes().conflate().collect { fix ->
                android.util.Log.v("InternalAdapter", "fix: lat=${fix.latDeg}, lon=${fix.lonDeg}")
                _fixes.emit(fix)
            }
        }

        skyJob = scope.launch {
            nmea.gsvStream().conflate().collect { gsv ->
                _sky.value = inv.consume(gsv)
            }
        }
    }

    override fun stop() {
        android.util.Log.d("InternalAdapter", "Stopping internal GPS adapter")
        collectJob?.cancel(); collectJob = null
        skyJob?.cancel();     skyJob     = null
        nmea.stop()
        _sky.value = SkySnapshot()
    }
}
