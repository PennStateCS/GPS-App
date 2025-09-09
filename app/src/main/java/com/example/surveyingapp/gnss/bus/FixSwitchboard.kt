package com.example.surveyingapp.gnss.bus

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SkySnapshot
import com.example.surveyingapp.gnss.settings.SourceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Chooses which adapter is active (INTERNAL or RS2) based on SourceSettings.
 * Ensures there's only one publisher into the public bus at a time.
 */
class FixSwitchboard(
    private val scope: CoroutineScope,
    private val sourceSettings: SourceSettings,
    private val internalAdapter: SourceAdapter,
    private val externalAdapter: SourceAdapter,
    private var skyJob: kotlinx.coroutines.Job? = null

) : FixBus, SkyBus {

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    private val _sky = MutableStateFlow(SkySnapshot(emptyMap(), emptyMap(), emptyMap(), emptyList()))
    override val sky: StateFlow<SkySnapshot> = _sky.asStateFlow()

    private var currentJob: Job? = null

    /** Call when settings change or at app start. */
    fun refreshRouting() {
        currentJob?.cancel()
        currentJob = scope.launch {
            when (sourceSettings.activeProvider.value) {
                SourceSettings.ProviderChoice.INTERNAL     -> bind(internalAdapter)
                SourceSettings.ProviderChoice.RS2_EXTERNAL -> bind(externalAdapter)
            }
        }
    }

    private suspend fun bind(adapter: SourceAdapter) {
        // Re-expose adapter streams on the bus
        adapter.fixes.collect { _fixes.emit(it) }
    }



    fun attachSkyFlow(skyFlow: StateFlow<SkySnapshot>) {
        skyJob?.cancel()
        skyJob = scope.launch {
            skyFlow.collect { _sky.value = it }
        }
    }
}

/** Minimal surface area required from any GNSS source adapter. */
interface SourceAdapter {
    val fixes: SharedFlow<Fix>
}
