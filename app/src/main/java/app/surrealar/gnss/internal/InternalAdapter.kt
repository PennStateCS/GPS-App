package app.surrealar.gnss.internal

import app.surrealar.gnss.bus.adapters.NmeaSource
import app.surrealar.gnss.bus.SourceAdapter
import app.surrealar.gnss.bus.SkyProvider
import app.surrealar.gnss.bus.Startable
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.SkySnapshot
import app.surrealar.gnss.satellites.SatelliteInventory
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
import app.surrealar.util.DiagnosticsLogger

/**
 * Connects Android's internal GNSS NMEA stream to the app's fix and sky buses.
 *
 * The adapter keeps the internal receiver behind the same SourceAdapter and
 * SkyProvider contracts used by external receivers. That lets the switchboard
 * treat internal and external GNSS sources the same way.
 */
class InternalAdapter(
    private val scope: CoroutineScope,
    private val nmea: NmeaSource,
    private val inv: SatelliteInventory
) : SourceAdapter, SkyProvider, Startable {

    private companion object {
        const val TAG = "InternalAdapter"
    }

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    private val _sky = MutableStateFlow(SkySnapshot())
    override val sky: StateFlow<SkySnapshot> = _sky.asStateFlow()

    private var collectJob: Job? = null
    private var skyJob: Job? = null

    override fun start() {
        if (collectJob != null) return

        android.util.Log.d(TAG, "Starting internal GNSS adapter")
        DiagnosticsLogger.i("SOURCE", "internal GNSS adapter started")

        /*
         * Clear the inventory before starting collection so satellites from a
         * previous provider or previous run are not shown as current sky state.
         */
        inv.reset()

        nmea.start()

        collectJob = scope.launch {
            /*
             * The live UI only needs the newest fix. Conflation avoids building a
             * backlog if the receiver emits faster than the UI can consume.
             */
            nmea.parsedFixes()
                .conflate()
                .collect { fix -> _fixes.emit(fix) }
        }

        skyJob = scope.launch {
            /*
             * GSV messages update the satellite inventory, which owns smoothing and
             * stale-satellite handling for the sky view.
             */
            nmea.gsvStream()
                .conflate()
                .collect { gsv ->
                    _sky.value = inv.consume(gsv)
                }
        }
    }

    override fun stop() {
        android.util.Log.d(TAG, "Stopping internal GNSS adapter")
        DiagnosticsLogger.i("SOURCE", "internal GNSS adapter stopped")

        collectJob?.cancel()
        collectJob = null

        skyJob?.cancel()
        skyJob = null

        nmea.stop()
        _sky.value = SkySnapshot()
    }
}