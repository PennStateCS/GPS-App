package com.example.surveyingapp.gnss.bus.adapters

import com.example.surveyingapp.gnss.bus.SourceAdapter
import com.example.surveyingapp.gnss.bus.Startable
import com.example.surveyingapp.gnss.model.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Adapts internal GPS NMEA source into normalized Fix objects.
 * This class contains no UI code and no settings persistence.
 */
class InternalAdapter(
    private val scope: CoroutineScope,
    private val fusedSource: FusedSource   // Internal GPS NMEA source wrapper
) : SourceAdapter, Startable {

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    private var collectJob: Job? = null

    override fun start() {
        if (collectJob != null) return // Already started
        android.util.Log.d("InternalAdapter", "Starting internal GPS adapter")

        // Start the underlying NMEA source
        (fusedSource as? Startable)?.start()

        // Start collecting fixes
        collectJob = scope.launch {
            fusedSource.fixes().conflate().collect { fix ->
                android.util.Log.d("InternalAdapter", "Collected fix from source, emitting: lat=${fix.latDeg}, lon=${fix.lonDeg}")
                _fixes.emit(fix)
            }
        }
    }

    override fun stop() {
        android.util.Log.d("InternalAdapter", "Stopping internal GPS adapter")
        collectJob?.cancel()
        collectJob = null
        fusedSource.stop()
    }
}

/** Define a small interface over your existing fused provider to keep this adapter testable. */
interface FusedSource {
    fun fixes(): SharedFlow<Fix>
    fun stop()
}
