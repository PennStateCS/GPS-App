package app.surrealar.gnss.replay

import app.surrealar.gnss.accumulator.FixAccumulator
import app.surrealar.gnss.nmea.parse.NmeaRegistry
import app.surrealar.gnss.nmea.sentence.NmeaSentence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch

/**
 * Wires a replay [NmeaLineSource] to a [FixAccumulator] through the [NmeaRegistry] (demo/replay
 * pipeline — NOT the live source-switching path, which goes through the adapters/`FixSwitchboard`).
 *
 * The pipeline is: source.lines() → checksum + registry.parse() → accumulator.accept()
 *
 * Buffering absorbs bursts from high-rate receivers. Lines that can't be parsed are
 * counted as dropped rather than crashing the pipeline. The optional [onStats] and
 * [onError] callbacks let callers observe throughput or react to source-level failures
 * without coupling this class to any specific UI or logging framework.
 */
class NmeaReplayController(
    private val scope: CoroutineScope,
    private val source: NmeaLineSource,
    private val registry: NmeaRegistry,
    private val accumulator: FixAccumulator,
    private val onStats: ((lines: Int, parsed: Int, dropped: Int) -> Unit)? = null,
    private val onError: ((Throwable) -> Unit)? = null
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) {
            var lines = 0
            var parsed = 0
            var dropped = 0
            val reportEvery = 200

            source.lines()
                .buffer(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                .filter { it.isNotEmpty() && it[0] == '$' } // fast-path
                .onEach {
                    lines++
                    if (onStats != null && (lines % reportEvery == 0)) {
                        onStats.invoke(lines, parsed, dropped)
                    }
                }
                .mapNotNull { line ->
                    try {
                        val s: NmeaSentence? = registry.parse(line)
                        if (s == null) dropped++ else parsed++
                        s
                    } catch (t: Throwable) {
                        dropped++
                        onError?.invoke(t)
                        null
                    }
                }
                .onEach { sentence ->
                    try {
                        accumulator.accept(sentence)
                    } catch (t: Throwable) {
                        // Defensive catch: accumulator should never throw, but if it does
                        // we count the sentence as dropped and keep the pipeline running.
                        dropped++
                        onError?.invoke(t)
                    }
                }
                .catch { e ->
                    // A failure at the source level ends the stream. Report it and stop.
                    onError?.invoke(e)
                }
                .flowOn(Dispatchers.Default)
                .collect { /* terminal by cancellation */ }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun cancelScope() {
        scope.cancel()
    }
}
