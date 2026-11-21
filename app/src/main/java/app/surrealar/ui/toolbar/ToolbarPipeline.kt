package app.surrealar.ui.toolbar

import app.surrealar.coords.ConvertedPosition
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.settings.model.CoordinateSystemSettings
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.sample
import kotlin.math.min

/** Raw toolbar inputs, before coordinate conversion and mapping. */
data class ToolbarInputs(
    val source: LocationSourceType,
    val fix: Fix?,
    val sky: SkySnapshot,
    val coordSettings: CoordinateSystemSettings,
    /** Short label of the active external receiver profile ("RS2+", "RS4", "EXT"), or "External". */
    val externalLabel: String,
)

/** One toolbar update: the inputs it came from, the converted position, and what to show. */
data class ToolbarFrame(
    val inputs: ToolbarInputs,
    val coords: ConvertedPosition?,
    val result: ToolbarMapResult,
)

/**
 * The toolbar's data pipeline, kept free of Android so its threading and error handling can be
 * unit-tested:
 *
 *   raw inputs (hot StateFlows, combined by the caller)
 *     → sample(refreshMs)                          — at most one update per 250 ms
 *     → map { convert + GnssToolbarStateMapper }   — on [computeDispatcher] (Default), never Main
 *     → caller renders on Main
 *
 * Conversion (datum, projection, geoid) runs only for sampled inputs, not for every 5–10 Hz fix and
 * sky update.
 */
object ToolbarPipeline {

    const val REFRESH_MS: Long = 250L

    @OptIn(FlowPreview::class)
    fun frames(
        inputs: Flow<ToolbarInputs>,
        convert: (Fix, CoordinateSystemSettings) -> ConvertedPosition?,
        refreshMs: Long = REFRESH_MS,
        computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): Flow<ToolbarFrame> =
        inputs
            .sample(refreshMs)
            .map { i ->
                // A conversion failure only drops the converted display (lat/lon + MSL is shown instead).
                val coords = i.fix?.let { runCatching { convert(it, i.coordSettings) }.getOrNull() }
                ToolbarFrame(i, coords, GnssToolbarStateMapper.map(i.source, i.fix, i.sky, i.externalLabel, coords))
            }
            .flowOn(computeDispatcher)

    /** Wait before resubscribing after the [attempt]-th consecutive error (0-based): 1 s, 2 s, 4 s, then 5 s. */
    fun backoffMs(attempt: Long): Long = min(1_000L shl attempt.coerceIn(0, 3).toInt(), 5_000L)

    /**
     * Keeps a toolbar flow alive: on any error, report it ([onError] — log and show the error state),
     * wait [backoffMs], then resubscribe. Unlike `catch`, the flow never ends, so one bad update can't
     * freeze the toolbar until the activity restarts. Cancellation is never retried.
     */
    fun <T> Flow<T>.retryForever(
        backoff: (Long) -> Long = ::backoffMs,
        onError: suspend (Throwable, Long) -> Unit,
    ): Flow<T> = retryWhen { cause, attempt ->
        if (cause is CancellationException) return@retryWhen false
        onError(cause, attempt)
        delay(backoff(attempt))
        true
    }
}
