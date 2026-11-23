package io.github.pennstatecs.geokit.gnss.replay

import io.github.pennstatecs.geokit.gnss.bus.adapters.SkyProvider
import io.github.pennstatecs.geokit.gnss.bus.adapters.SourceAdapter
import io.github.pennstatecs.geokit.gnss.bus.adapters.Startable
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.link.GnssLinkLog
import io.github.pennstatecs.geokit.gnss.link.LinkLog
import io.github.pennstatecs.geokit.gnss.link.LinkLogLevel
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import io.github.pennstatecs.geokit.gnss.satellites.SatelliteInventory
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
 * through the host's switchboard like any live adapter, so everything that shows live data shows the
 * replayed data too.
 *
 * Fixes carry [Provider.REPLAY] so a host can refuse to average them into a capture.
 *
 * @param log where the started/stopped and error lines go (tag `GNSS`).
 */
public class ReplayAdapter(
    private val scope: CoroutineScope,
    private val inventory: SatelliteInventory = SatelliteInventory(debugName = "replay"),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val log: LinkLog = GnssLinkLog(),
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
                .onFailure { log.every(TAG, "replay.gsv", 60_000L, LinkLogLevel.ERROR, throwable = it) { "NMEA replay satellite update failed: GSV message skipped" } }
        },
    )

    @Volatile private var source: NmeaLineSource? = null
    private var playJob: Job? = null
    private var tickJob: Job? = null

    /** Sets what [start] plays. Call before switching the active provider to REPLAY. */
    public fun load(lineSource: NmeaLineSource) { source = lineSource }

    /** Name of the loaded source, or null when none is loaded. */
    public val sourceName: String? get() = source?.name

    override fun start() {
        val src = source ?: return
        if (playJob != null) return
        inventory.reset()
        log.i(TAG, "NMEA replay started source=${src.name}")
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
                            .onFailure { log.every(TAG, "replay.line", 60_000L, LinkLogLevel.ERROR, throwable = it) { "NMEA replay line rejected: fuser error" } }
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
        log.i(TAG, "NMEA replay stopped")
    }

    /** True while the GST-hold ticker runs (tests: it must stop when the file ends). */
    internal val isTicking: Boolean get() = tickJob?.isActive == true

    private companion object {
        const val HOLD_TICK_MS = 100L
        const val TAG = "GNSS"
    }
}
