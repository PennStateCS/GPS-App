package app.surrealar.gnss.replay

import app.surrealar.gnss.bus.SkyProvider
import app.surrealar.gnss.bus.SourceAdapter
import io.github.pennstatecs.geokit.gnss.bus.adapters.Startable
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import io.github.pennstatecs.geokit.gnss.satellites.SatelliteInventory
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
 * Fixes carry [Provider.REPLAY] so they are never averaged into a capture (see CaptureViewModel).
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
        provider = Provider.REPLAY,
        registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = false),
        onFix = { _fixes.tryEmit(it) },
        onGsv = { gsv ->
            runCatching { inventory.consume(gsv) }
                .onSuccess { _sky.value = it }
                .onFailure { DiagnosticsLogger.e("GNSS", "replay GSV update failed; message skipped", it) }
        },
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
        DiagnosticsLogger.i("GNSS", "replay started: ${src.name}")
        // Same 300 ms GST-hold cap as the live sources — only while the file plays. Started first so a
        // file that ends at once still finds it to cancel.
        val tick = scope.launch(dispatcher) {
            while (isActive) { delay(HOLD_TICK_MS); fuser.flushIfStale() }
        }
        tickJob = tick
        playJob = scope.launch(dispatcher) {
            try {
                src.lines().collect { line ->
                    if (line.isNotEmpty() && line[0] == '$') {
                        runCatching { fuser.accept(line) }
                            .onFailure { DiagnosticsLogger.e("GNSS", "replay line rejected by the fuser", it) }
                    }
                }
            } finally {
                fuser.flush()   // end of input: emit a fix still held for its GST
                tick.cancel()   // nothing more can be held: stop the 10 Hz ticker
            }
        }
    }

    override fun stop() {
        tickJob?.cancel(); tickJob = null
        playJob?.cancel(); playJob = null
        fuser.flush()
        fuser.reset()
        _fixes.resetReplayCache()
        _sky.value = SkySnapshot()
        DiagnosticsLogger.i("GNSS", "replay stopped")
    }

    /** True while the GST-hold ticker runs (tests: it must stop when the file ends). */
    internal val isTicking: Boolean get() = tickJob?.isActive == true

    private companion object { const val HOLD_TICK_MS = 100L }
}
