package com.example.surveyingapp.gnss.bus

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SkySnapshot
import com.example.surveyingapp.gnss.settings.SourceSettings
import com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Routes fix and sky streams from whichever [SourceAdapter] is currently selected.
 *
 * Provider switching is driven by [SourceSettings.activeProvider]. When the active provider
 * changes, the old adapter is stopped and the new one is started automatically.
 *
 * **Extensibility**: to add a new provider, add a value to [ProviderChoice], implement a
 * [SourceAdapter], and register it in the [adapters] map passed at construction time.
 * No changes to [FixSwitchboard] itself are required.
 *
 * @param adapters  Map from every supported [ProviderChoice] to its [SourceAdapter].
 *                  An unknown choice is logged as a warning and results in an empty stream.
 */
class FixSwitchboard(
    private val scope: CoroutineScope,
    private val sourceSettings: SourceSettings,
    private val adapters: Map<ProviderChoice, SourceAdapter>
) : FixBus, SkyBus {

    private val EmptySky = SkySnapshot(
        satellites   = emptyList(),
        epoch        = Instant.EPOCH,
        source       = com.example.surveyingapp.gnss.model.SkySource.UNKNOWN,
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
            sourceSettings.activeProvider.collect { choice ->
                android.util.Log.d("FixSwitchboard", "Provider changed → $choice")
                val adapter = adapters[choice]
                if (adapter == null) {
                    android.util.Log.w("FixSwitchboard", "No adapter registered for $choice — stream will be empty")
                    rebindTo(null)
                } else {
                    bind(adapter)
                }
            }
        }
    }

    /** Public stop for lifecycle symmetry. */
    fun stop() {
        android.util.Log.d("FixSwitchboard", "Stopping switchboard")
        providerCollectJob?.cancel(); providerCollectJob = null
        rebindTo(null)
    }

    private fun bind(adapter: SourceAdapter) {
        if (currentAdapter === adapter) return // already active
        rebindTo(adapter)
    }

    private fun rebindTo(next: SourceAdapter?) {
        android.util.Log.d(
            "FixSwitchboard",
            "rebindTo: ${currentAdapter?.let { it::class.simpleName }} → ${next?.let { it::class.simpleName }}"
        )

        currentFixCollector?.cancel(); currentFixCollector = null
        skyJob?.cancel();              skyJob              = null

        (currentAdapter as? Startable)?.stop()
        currentAdapter = next

        if (next == null) {
            _sky.value = EmptySky
            return
        }

        (next as? Startable)?.start()

        currentFixCollector = scope.launch {
            next.fixes.conflate().collect { fix ->
                _fixes.emit(fix)
            }
        }

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

/** Implement if the adapter exposes sky/satellite snapshots. */
interface SkyProvider {
    val sky: StateFlow<SkySnapshot>
}

/** Implement if the adapter requires explicit lifecycle management (start/stop). */
interface Startable {
    fun start()
    fun stop()
}
