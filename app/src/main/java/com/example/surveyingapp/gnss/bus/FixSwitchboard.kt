package com.example.surveyingapp.gnss.bus

import com.example.surveyingapp.gnss.bus.adapters.RawNmeaProvider
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.SkySnapshot
import com.example.surveyingapp.gnss.settings.SourceSettings
import com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice
import com.example.surveyingapp.util.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Routes GNSS fixes and sky snapshots from the currently selected source.
 *
 * The rest of the app should collect from this switchboard instead of talking
 * directly to internal GNSS, TCP, Bluetooth, or any other source adapter. When
 * the active provider changes, the switchboard stops the current adapter, starts
 * the new adapter, and republishes its streams through the shared buses.
 *
 * To add another provider, add a new ProviderChoice, implement SourceAdapter,
 * and include it in the adapters map.
 */
class FixSwitchboard(
    private val scope: CoroutineScope,
    private val sourceSettings: SourceSettings,
    private val adapters: Map<ProviderChoice, SourceAdapter>
) : FixBus, SkyBus {

    private val EmptySky = SkySnapshot(
        satellites = emptyList(),
        epoch = Instant.EPOCH,
        source = com.example.surveyingapp.gnss.model.SkySource.UNKNOWN,
        smoothWindowMs = null
    )

    /*
     * Keep the most recent fix available for late collectors. The extra buffer
     * gives short UI stalls some breathing room without blocking the GNSS source.
     */
    private val _fixes = MutableSharedFlow<Fix>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    private val _sky = MutableStateFlow(EmptySky)
    override val sky: StateFlow<SkySnapshot> = _sky.asStateFlow()

    /*
     * Nullable snapshot of the most recent fix from the CURRENTLY bound provider.
     *
     * Unlike [fixes] (a replay=1 SharedFlow that holds its last value until a new one
     * arrives), this is reset to null the instant a provider switch begins. UI code that
     * needs a guaranteed "current provider only, no stale carry-over" view of the live fix
     * should read this instead of the replay buffer: after a switch it reads null until the
     * new provider actually emits, so the toolbar/map/capture never show the old source's fix.
     */
    private val _currentFix = MutableStateFlow<Fix?>(null)
    val currentFix: StateFlow<Fix?> = _currentFix.asStateFlow()

    /** Emits once for every raw NMEA sentence from the active external adapter. */
    private val _rawNmea = MutableSharedFlow<Unit>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val rawNmea: SharedFlow<Unit> = _rawNmea.asSharedFlow()

    private var providerCollectJob: Job? = null
    private var currentFixCollector: Job? = null
    private var skyJob: Job? = null
    private var rawNmeaJob: Job? = null
    private var currentAdapter: SourceAdapter? = null

    /*
     * Monotonic token bumped on every rebind. Each fix collector captures the generation it
     * was launched under and drops any emission once the token has advanced. This is a
     * belt-and-suspenders guard on top of job cancellation: coroutine cancellation is
     * cooperative, so a just-stopped old-provider collector could otherwise slip one late
     * emission into currentFix after the switch. The generation check makes that impossible.
     */
    @Volatile private var bindGeneration = 0

    /** Wall-clock time of the most recent provider transition (0 = none yet). For diagnostics. */
    @Volatile var lastProviderSwitchAtMs: Long = 0L
        private set

    /**
     * Starts watching the selected provider and binds to the matching adapter.
     *
     * This is expected to be called once during app startup. Calling it again
     * replaces the provider watcher instead of creating a second collector.
     */
    fun start() {
        android.util.Log.d("FixSwitchboard", "Starting switchboard")

        providerCollectJob?.cancel()
        providerCollectJob = scope.launch {
            var previousChoice: ProviderChoice? = null
            sourceSettings.activeProvider.collect { choice ->
                android.util.Log.d("FixSwitchboard", "Provider changed: $choice")
                if (previousChoice != null && previousChoice != choice) {
                    lastProviderSwitchAtMs = System.currentTimeMillis()
                    DiagnosticsLogger.i(
                        "GNSS",
                        "Provider changed $previousChoice -> $choice; cleared current fix and sky"
                    )
                }
                previousChoice = choice

                val adapter = adapters[choice]
                if (adapter == null) {
                    android.util.Log.w(
                        "FixSwitchboard",
                        "No adapter registered for $choice. GNSS stream will be empty."
                    )
                    rebindTo(null)
                } else {
                    bind(adapter)
                }
            }
        }
    }

    /**
     * Stops provider observation and disconnects from the active adapter.
     */
    fun stop() {
        android.util.Log.d("FixSwitchboard", "Stopping switchboard")

        providerCollectJob?.cancel()
        providerCollectJob = null

        rebindTo(null)
    }

    private fun bind(adapter: SourceAdapter) {
        if (currentAdapter === adapter) return
        rebindTo(adapter)
    }

    private fun rebindTo(next: SourceAdapter?) {
        android.util.Log.d(
            "FixSwitchboard",
            "Rebinding source: ${currentAdapter?.let { it::class.simpleName }} -> ${next?.let { it::class.simpleName }}"
        )

        /*
         * Stop collectors before stopping the adapter. That prevents old source
         * emissions from leaking through while the provider switch is in progress.
         */
        currentFixCollector?.cancel()
        currentFixCollector = null

        skyJob?.cancel()
        skyJob = null

        rawNmeaJob?.cancel()
        rawNmeaJob = null

        (currentAdapter as? Startable)?.stop()
        currentAdapter = next

        // Advance the generation so any in-flight emission from the old provider's
        // (now-cancelled) collector is dropped instead of updating currentFix/fixes.
        val generation = ++bindGeneration

        /*
         * Clear all live state from the OUTGOING provider:
         *  - currentFix → null, so a guaranteed-current-only consumer reads "no fix yet"
         *    until the new provider emits (never the previous source's coordinates).
         *  - the replay=1 fix buffer, so late SharedFlow subscribers don't replay the old fix.
         *  - sky → EmptySky, so satellite counts don't carry over.
         */
        _currentFix.value = null
        _fixes.resetReplayCache()
        _sky.value = EmptySky

        if (next == null) {
            return
        }

        (next as? Startable)?.start()

        currentFixCollector = scope.launch {
            /*
             * Only the latest fix matters for the live UI. Conflation prevents slow
             * collectors from building a backlog during high-rate GNSS updates.
             */
            next.fixes.conflate().collect { fix ->
                // Drop emissions from a superseded binding (see bindGeneration).
                if (generation != bindGeneration) return@collect
                _currentFix.value = fix
                _fixes.emit(fix)
            }
        }

        val skyFlow = (next as? SkyProvider)?.sky
        skyJob = if (skyFlow != null) {
            scope.launch {
                skyFlow.collect { snapshot ->
                    _sky.value = snapshot
                }
            }
        } else {
            _sky.value = EmptySky
            null
        }

        rawNmeaJob = (next as? RawNmeaProvider)?.let { provider ->
            scope.launch { provider.rawNmea.collect { _rawNmea.emit(Unit) } }
        }
    }
}

/**
 * Minimum contract for a source that can publish GNSS fixes.
 */
interface SourceAdapter {
    val fixes: SharedFlow<Fix>
}

/**
 * Optional contract for adapters that publish satellite sky state.
 */
interface SkyProvider {
    val sky: StateFlow<SkySnapshot>
}

/**
 * Optional contract for adapters that need explicit lifecycle control.
 */
interface Startable {
    fun start()
    fun stop()
}