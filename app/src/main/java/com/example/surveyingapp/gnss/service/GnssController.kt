package com.example.surveyingapp.gnss.service

import com.example.surveyingapp.gnss.accumulator.FixAccumulator
import com.example.surveyingapp.gnss.nmea.parse.NmeaRegistry
import com.example.surveyingapp.gnss.nmea.sentence.NmeaSentence
import com.example.surveyingapp.gnss.source.GnssSource
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
 * Orchestrates: GnssSource.lines() -> NmeaRegistry.parse() -> FixAccumulator.accept()
 * - Buffers the stream to tolerate bursts (DROP_OLDEST to avoid stalls)
 * - Never throws on bad input
 * - Optional lightweight stats via callbacks
 */
class GnssController(
    private val scope: CoroutineScope,
    private val source: GnssSource,
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
                    // Fuse into current fix; accumulator must never throw.
                    try {
                        accumulator.accept(sentence)
                    } catch (t: Throwable) {
                        // Extremely defensive: if fusion fails, count as dropped and continue.
                        dropped++
                        onError?.invoke(t)
                    }
                }
                .catch { e ->
                    // Source/flow level failure: report and keep the coroutine alive (if desired).
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
