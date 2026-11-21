package app.surrealar.gnss.replay

import app.surrealar.gnss.bus.SkyProvider
import app.surrealar.gnss.bus.SourceAdapter
import app.surrealar.gnss.bus.adapters.Startable
import app.surrealar.gnss.bus.adapters.NmeaFuser
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.SkySnapshot
import app.surrealar.gnss.nmea.parse.DefaultNmeaRegistry
import app.surrealar.gnss.satellites.SatelliteInventory
import app.surrealar.util.DiagnosticsLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * NMEA replay as a regular GNSS source: lines from an [NmeaLineSource] go through the same
 * [NmeaFuser] the live sources use (lenient registry — replay logs may omit checksums) and out
 * through [FixSwitchboard][app.surrealar.gnss.bus.FixSwitchboard] like any live adapter, so the map,
 * toolbar, sky view and Developer page all show the replayed data. (The old FixAccumulator path fed
 * a StateFlow nothing observed, so replay displayed nothing.)
 *
 * Fixes carry [Provider.OTHER] so they are never averaged into a capture (see CaptureViewModel).
 */
class ReplayAdapter(
    private val scope: CoroutineScope,
    private val inventory: SatelliteInventory = SatelliteInventory(debugName = "replay"),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SourceAdapter, SkyProvider, Startable {

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    private val _sky = MutableStateFlow(SkySnapshot())
    override val sky: StateFlow<SkySnapshot> = _sky.asStateFlow()

    private val fuser = NmeaFuser(
        provider = Provider.OTHER,
        registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = false),
        onFix = { _fixes.tryEmit(it) },
        onGsv = { _sky.value = inventory.consume(it) },
    )

    @Volatile private var source: NmeaLineSource? = null
    private var playJob: Job? = null
    private var tickJob: Job? = null

    /** Sets what [start] plays. Call before switching the active provider to REPLAY. */
    fun load(lineSource: NmeaLineSource) { source = lineSource }

    val sourceName: String? get() = source?.name

    override fun start() {
        val src = source ?: return
        if (playJob != null) return
        inventory.reset()
        DiagnosticsLogger.i("SOURCE", "replay started: ${src.name}")
        playJob = scope.launch(dispatcher) {
            try {
                src.lines().collect { line -> if (line.isNotEmpty() && line[0] == '$') fuser.accept(line) }
            } finally {
                fuser.flush()   // end of input: emit a fix still held for its GST
            }
        }
        // Same 300 ms GST-hold cap as the live sources.
        tickJob = scope.launch(dispatcher) {
            while (isActive) { delay(HOLD_TICK_MS); fuser.flushIfStale() }
        }
    }

    override fun stop() {
        tickJob?.cancel(); tickJob = null
        playJob?.cancel(); playJob = null
        fuser.flush()
        fuser.reset()
        _fixes.resetReplayCache()
        _sky.value = SkySnapshot()
        DiagnosticsLogger.i("SOURCE", "replay stopped")
    }

    private companion object { const val HOLD_TICK_MS = 100L }
}
