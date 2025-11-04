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
        android.util.Log.d("FixSwitchboard", "Starting switchboard")
        providerCollectJob?.cancel()
        providerCollectJob = scope.launch {
            sourceSettings.activeProvider
                .collect { choice ->
                    android.util.Log.d("FixSwitchboard", "Provider choice changed to: $choice")
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
        android.util.Log.d("FixSwitchboard", "Stopping switchboard")
        providerCollectJob?.cancel(); providerCollectJob = null
        rebindTo(null) // cancels collectors & stops current adapter
    }

    /** Switch to a specific adapter, cancelling previous collectors, and wiring sky/fixes. */
    private fun bind(adapter: SourceAdapter) {
        android.util.Log.d("FixSwitchboard", "bind() called for adapter: ${adapter::class.simpleName}")
        if (currentAdapter === adapter) {
            android.util.Log.d("FixSwitchboard", "Adapter already active, skipping rebind")
            return // already active
        }
        rebindTo(adapter)
    }

    /** Internal rebind that also handles stopping/starting adapters and wiring flows. */
    private fun rebindTo(next: SourceAdapter?) {
        android.util.Log.d("FixSwitchboard", "rebindTo() called: current=${currentAdapter?.let { it::class.simpleName }}, next=${next?.let { it::class.simpleName }}")

        // Stop collecting from previous adapter flows
        currentFixCollector?.cancel(); currentFixCollector = null
        skyJob?.cancel(); skyJob = null

        // Stop old adapter if it supported start/stop
        (currentAdapter as? Startable)?.stop()
        currentAdapter = next

        if (next == null) {
            _sky.value = EmptySky
            android.util.Log.d("FixSwitchboard", "Next adapter is null, clearing sky")
            return
        }

        // Start new adapter if needed
        android.util.Log.d("FixSwitchboard", "Starting adapter: ${next::class.simpleName}")
        (next as? Startable)?.start()

        // Wire fixes (use conflate to prefer latest under pressure)
        currentFixCollector = scope.launch {
            android.util.Log.d("FixSwitchboard", "Started collecting fixes from ${next::class.simpleName}")
            next.fixes
                .conflate()
                .collect { fix ->
                    android.util.Log.d("FixSwitchboard", "Received fix from adapter, emitting: lat=${fix.latDeg}, lon=${fix.lonDeg}")
                    _fixes.emit(fix)
                }
        }

        // Wire sky if available; otherwise reset to empty
        val skyFlow = (next as? SkyProvider)?.sky
        skyJob = if (skyFlow != null) {
            scope.launch { skyFlow.collect { snap -> _sky.value = snap } }
        } else {
            _sky.value = EmptySky
            null
        }
        android.util.Log.d("FixSwitchboard", "Adapter wired successfully")
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
