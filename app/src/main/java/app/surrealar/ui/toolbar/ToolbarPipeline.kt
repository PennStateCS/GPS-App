package app.surrealar.ui.toolbar

import app.surrealar.coords.ConvertedPosition
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.status.CorrectionHealth
import app.surrealar.gnss.status.CorrectionHealthTracker
import app.surrealar.gnss.status.GnssStatusInterpreter
import app.surrealar.receiver.selection.SourceTransition
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.capture.resolvePolicy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.runningFold
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
    /** Link state (connection phase, last raw NMEA, location on/off) to explain a missing fix. */
    val link: ToolbarLinkInputs = ToolbarLinkInputs(),
    /** Identifies the external receiver (profile + host); a change forgets the last fix. */
    val receiverKey: String = "",
    /** Changes once a second so ages ("No data 12s", "lost 12s") keep counting with no other input. */
    val tick: Long = 0L,
    /** Toolbar-only display override: lat/lon instead of the configured grid. */
    val preferLatLon: Boolean = false,
    /** The active provider is NMEA replay. */
    val replayActive: Boolean = false,
    /** The selection core's live source (kind + transition); null = provider-based rules only. */
    val live: ToolbarLiveSource? = null,
    /** RS4 / RS4 Pro position reference from the tilt monitor; null = not applicable. */
    val reference: GnssStatusInterpreter.Reference? = null,
    /**
     * "Corrections aging" threshold (s): the active capture policy's `maxDiffAgeSec` (external receiver), so the
     * toolbar warns exactly when capture would start rejecting fixes for old corrections.
     */
    val correctionAgingS: Double = DEFAULT_CORRECTION_AGING_S,
) {
    companion object {
        /** The default capture settings' external `maxDiffAgeSec` (10 s), until the settings load. */
        val DEFAULT_CORRECTION_AGING_S: Double =
            io.github.pennstatecs.geokit.gnss.capture.GnssCaptureSettings().toolbarAgingThresholdS()
    }

    /** A live fix from the selected external receiver that the selection core attributes to it (not replay). */
    val liveExternalFix: Boolean
        get() {
            val f = fix ?: return false
            return source != LocationSourceType.INTERNAL && !replayActive &&
                (f.provider == io.github.pennstatecs.geokit.gnss.model.Provider.EXTERNAL_TCP ||
                    f.provider == io.github.pennstatecs.geokit.gnss.model.Provider.EXTERNAL_BT) &&
                (live == null || live.kind == app.surrealar.receiver.selection.LiveSourceKind.EXTERNAL)
        }
}

/**
 * The toolbar's "Corrections aging" threshold (s): the external-receiver capture policy's correction-age limit
 * (`maxDiffAgeSec` of the selected preset, or of Custom), i.e. the age above which a capture stops accepting fixes.
 */
fun io.github.pennstatecs.geokit.gnss.capture.GnssCaptureSettings.toolbarAgingThresholdS(): Double =
    resolvePolicy(internal = false).maxDiffAgeSec.toDouble()

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
        /** Monotonic clock (ms) for link and last-fix ages. */
        nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    ): Flow<ToolbarFrame> = flow {
        // Correction-health hysteresis is the only state besides the last-fix memory; one per collection, so it
        // runs on the compute dispatcher with the mapper (never on Main) and never across collectors.
        val health = CorrectionHealthStage()
        emitAll(inputs
            .sample(refreshMs)
            .runningFold(Step(null, null)) { prev, i ->
                val now = nowMs()
                // A conversion failure only drops the converted display (lat/lon + MSL is shown instead).
                val coords = i.fix?.let { runCatching { convert(it, i.coordSettings) }.getOrNull() }
                val memory = prev.memory.next(i, coords, now)
                val corrections = health.update(i, now)
                val result = GnssToolbarStateMapper.map(
                    i.source, i.fix, i.sky, i.externalLabel, coords, i.link, memory?.last, now, i.preferLatLon, i.replayActive,
                    correctionHealth = corrections, reference = i.reference.takeIf { i.source != LocationSourceType.INTERNAL },
                    live = i.live)
                Step(memory, ToolbarFrame(i, coords, result))
            }
            .mapNotNull { it.frame })
    }.flowOn(computeDispatcher)

    private data class Step(val memory: FixMemory?, val frame: ToolbarFrame?)

    /**
     * Owns the [CorrectionHealthTracker] for one pipeline collection: recreated when the aging threshold (capture
     * setting) changes, reset when the selected source or receiver changes or a receiver switch starts.
     */
    internal class CorrectionHealthStage {
        private var tracker: CorrectionHealthTracker? = null
        private var key: String? = null

        fun update(i: ToolbarInputs, now: Long): CorrectionHealth {
            val t = tracker?.takeIf { it.agingS == i.correctionAgingS.coerceAtMost(it.staleS) }
                ?: CorrectionHealthTracker(i.correctionAgingS).also { tracker = it }
            val k = "${i.source}|${i.receiverKey}"
            if (k != key || i.live?.transition is SourceTransition.Switching) { t.reset(); key = k }
            val fix = i.fix
            return t.update(i.liveExternalFix, fix?.diffAgeS, fix?.rtkStatus ?: RtkStatus.NONE, now)
        }
    }

    /**
     * The last fix shown, tied to the source and receiver it came from. Forgotten when the selected
     * source or the receiver changes, or a receiver switch starts, so a lost-link display never shows another
     * provider's (or another receiver's) position. Replay fixes and external fixes the selection core does not
     * attribute to the selected receiver are never remembered.
     */
    internal data class FixMemory(val source: LocationSourceType, val receiverKey: String, val last: LastFix)

    internal fun FixMemory?.next(i: ToolbarInputs, coords: ConvertedPosition?, now: Long): FixMemory? {
        val kept = this?.takeIf { it.source == i.source && it.receiverKey == i.receiverKey &&
            i.live?.transition !is SourceTransition.Switching }
        val fix = i.fix ?: return kept
        if (fix.provider == io.github.pennstatecs.geokit.gnss.model.Provider.REPLAY) return kept
        if (!GnssToolbarStateMapper.fixMatchesSource(i.source, fix)) return kept
        if (i.source != LocationSourceType.INTERNAL && !i.liveExternalFix) return kept
        return FixMemory(i.source, i.receiverKey, LastFix(fix, now, coords))
    }

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
