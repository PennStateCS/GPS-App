package com.example.surveyingapp.gnss.bus

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SatInfo
import com.example.surveyingapp.gnss.model.SkySnapshot
import com.example.surveyingapp.gnss.model.SkySource
import com.example.surveyingapp.gnss.settings.SourceSettings
import com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Chooses which adapter is active (INTERNAL or RS2) based on SourceSettings.
 * Ensures there's only one publisher into the public bus at a time.
 */
class FixSwitchboard(
    private val scope: CoroutineScope,
    private val sourceSettings: SourceSettings,
    private val internalAdapter: SourceAdapter,
    private val externalAdapter: SourceAdapter
) : FixBus, SkyBus {

    private val EmptySky = SkySnapshot(
        satellites = emptyList(),
        epoch = Instant.EPOCH,
        source = SkySource.UNKNOWN,
        smoothWindowMs = null
    )

    // replay(1) so new collectors immediately see the latest fix
    private val _fixes = MutableSharedFlow<Fix>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    private val _sky = MutableStateFlow(EmptySky)
    override val sky: StateFlow<SkySnapshot> = _sky.asStateFlow()

    private var providerCollectJob: Job? = null
    private var currentFixCollector: Job? = null
    private var skyJob: Job? = null
    private var currentAdapter: SourceAdapter? = null

    /** Begin observing provider choice and route streams accordingly. Call once at app start. */
    fun start() {
        providerCollectJob?.cancel()
        providerCollectJob = scope.launch {
            sourceSettings.activeProvider
                .collect { choice ->
                    val adapter = when (choice) {
                        ProviderChoice.INTERNAL     -> internalAdapter
                        ProviderChoice.RS2_EXTERNAL -> externalAdapter
                    }
                    bind(adapter)
                }
        }
    }

    /** Public stop for lifecycle symmetry */
    fun stop() {
        providerCollectJob?.cancel(); providerCollectJob = null
        rebindTo(null) // cancels collectors & stops current adapter
    }

    /** Switch to a specific adapter, cancelling previous collectors, and wiring sky/fixes. */
    private fun bind(adapter: SourceAdapter) {
        if (currentAdapter === adapter) return // already active
        rebindTo(adapter)
    }

    /** Internal rebind that also handles stopping/starting adapters and wiring flows. */
    private fun rebindTo(next: SourceAdapter?) {
        // Stop collecting from previous adapter flows
        currentFixCollector?.cancel(); currentFixCollector = null
        skyJob?.cancel(); skyJob = null

        // Stop old adapter if it supported start/stop
        (currentAdapter as? Startable)?.stop()
        currentAdapter = next

        if (next == null) {
            _sky.value = EmptySky
            return
        }

        // Start new adapter if needed
        (next as? Startable)?.start()

        // Wire fixes (use conflate to prefer latest under pressure)
        currentFixCollector = scope.launch {
            next.fixes
                .conflate()
                .collect { fix -> _fixes.emit(fix) }
        }

        // Wire sky if available; otherwise reset to empty
        val skyFlow = (next as? SkyProvider)?.sky
        skyJob = if (skyFlow != null) {
            scope.launch { skyFlow.collect { snap -> _sky.value = snap } }
        } else {
            _sky.value = EmptySky
            null
        }
    }
}

/** Minimal surface area required from any GNSS source adapter. */
interface SourceAdapter {
    val fixes: SharedFlow<Fix>
}

/** Implement if the adapter exposes sky snapshots. */
interface SkyProvider {
    val sky: StateFlow<SkySnapshot>
}

/** Implement if the adapter requires lifecycle (start/stop). */
interface Startable {
    fun start()
    fun stop()
}
