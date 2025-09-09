package com.example.surveyingapp.gnss.bus.adapters

import com.example.surveyingapp.gnss.bus.SourceAdapter
import com.example.surveyingapp.gnss.model.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Adapts Fused/GnssStatus into normalized Fix objects.
 * This class contains no UI code and no settings persistence.
 */
class InternalAdapter(
    private val scope: CoroutineScope,
    private val fusedSource: FusedSource   // Your existing Fused provider wrapper
) : SourceAdapter {

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val fixes: SharedFlow<Fix> = _fixes

    fun start() {
        scope.launch {
            fusedSource.fixes().collect { fix -> _fixes.emit(fix) }
        }
    }

    fun stop() {
        fusedSource.stop()
    }
}

/** Define a small interface over your existing fused provider to keep this adapter testable. */
interface FusedSource {
    fun fixes(): SharedFlow<Fix>
    fun stop()
}
