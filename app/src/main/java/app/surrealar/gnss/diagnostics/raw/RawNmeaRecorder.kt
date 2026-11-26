package app.surrealar.gnss.diagnostics.raw

import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Developer Tools → Raw NMEA Recording: writes the NMEA lines a live source
 * delivers, verbatim and in order, to an app-private `.nmea` file with a companion `.json`
 * ([RawNmeaMetadataJson]). A developer diagnostic only — independent of point capture, never changes GNSS
 * processing, and never opens its own receiver connection.
 *
 * **Tap points** (wired in GnssModule): TcpNmeaSource's `onLine` hook for the external receiver and
 * InternalNmeaSource's raw-line hook for the tablet GNSS; both run before parsing / fusion. [offer] runs on
 * those callers' threads (the TCP reader thread, the main looper) and only does a non-blocking `trySend`
 * into a bounded queue ([queueCapacity]); a background writer on [ioDispatcher] does all file I/O. When
 * the queue is full the line is counted as dropped and the recording ends with
 * [RawNmeaTermination.BUFFER_OVERFLOW] (`complete=false`) — the stream itself is never delayed.
 *
 * One recording at a time. It ends at the chosen duration, at [maxFileBytes] (also in Manual mode), on
 * [stop], when the source changes or the receiver link drops ([watchSource]), or on a writer failure.
 */
class RawNmeaRecorder(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    val files: RawNmeaFiles,
    private val metadata: (RawNmeaOrigin) -> RawNmeaSourceMetadata,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monoClockMs: () -> Long,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val openOutput: (File) -> OutputStream = { FileOutputStream(it) },
    private val statsPublishIntervalMs: Long = 250L,
    /** Status lines (never sentence text or coordinates), e.g. to DiagnosticsLogger. */
    private val log: (String) -> Unit = {},
) {
    sealed interface StartResult {
        data class Started(val nmeaFile: File) : StartResult
        data class Unavailable(val reason: String) : StartResult
        data object AlreadyRecording : StartResult
        data class Failed(val message: String) : StartResult
    }

    private inner class Session(
        val origin: RawNmeaOrigin,
        val nmeaFile: File,
        val metaFile: File,
        val startedAtMs: Long,
        val startedMonoMs: Long,
        val durationS: Int?,
        val meta: RawNmeaSourceMetadata,
    ) {
        val channel = Channel<String>(queueCapacity)
        val dropped = AtomicLong(0)
        val reason = AtomicReference<RawNmeaTermination?>(null)
        val stats = RawNmeaStats()   // writer only
        @Volatile var timerJob: Job? = null
    }

    private val active = AtomicReference<Session?>(null)

    private val _state = MutableStateFlow<RawNmeaState>(RawNmeaState.Idle)
    val state: StateFlow<RawNmeaState> = _state.asStateFlow()

    private val provider = MutableStateFlow<ProviderChoice?>(null)
    private val link = MutableStateFlow<ConnectionState?>(null)
    private val _availability = MutableStateFlow<RawNmeaAvailability>(availabilityOf(null, null))
    val availability: StateFlow<RawNmeaAvailability> = _availability.asStateFlow()

    val isRecording: Boolean get() = active.get() != null

    /**
     * Ends a recording when the active source changes ([RawNmeaTermination.SOURCE_CHANGED]: another provider,
     * or another external receiver profile) or the external link leaves CONNECTED
     * ([RawNmeaTermination.DISCONNECTED]), and keeps [availability] current. Call once (GnssModule).
     */
    fun watchSource(providers: Flow<ProviderChoice>, links: Flow<ConnectionState>, profileKeys: Flow<String?>) {
        scope.launch {
            providers.collect { p ->
                provider.value = p
                refreshAvailability()
                val s = active.get() ?: return@collect
                if (originOf(p) != s.origin) requestStop(s, RawNmeaTermination.SOURCE_CHANGED)
            }
        }
        scope.launch {
            links.collect { st ->
                link.value = st
                refreshAvailability()
                val s = active.get() ?: return@collect
                if (s.origin == RawNmeaOrigin.EXTERNAL_TCP && st != ConnectionState.CONNECTED) {
                    requestStop(s, RawNmeaTermination.DISCONNECTED)
                }
            }
        }
        scope.launch {
            profileKeys.collect { key ->
                val s = active.get() ?: return@collect
                if (s.origin == RawNmeaOrigin.EXTERNAL_TCP && key != s.meta.profileKey) {
                    requestStop(s, RawNmeaTermination.SOURCE_CHANGED)
                }
            }
        }
    }

    private fun refreshAvailability() { _availability.value = availabilityOf(provider.value, link.value) }

    /**
     * The per-line tap. Cheap and non-blocking on every path: returns at once when not recording or when
     * the line is from another source; never throws.
     */
    fun offer(origin: RawNmeaOrigin, line: String) {
        val s = active.get() ?: return
        if (s.origin != origin) return
        try {
            val r = s.channel.trySend(line)
            if (r.isFailure && !r.isClosed) {
                s.dropped.incrementAndGet()
                requestStop(s, RawNmeaTermination.BUFFER_OVERFLOW)
            }
        } catch (_: Throwable) {
            // Never let the diagnostic tap affect the stream.
        }
    }

    /** Starts a recording from the currently available live source. File creation runs on [ioDispatcher]. */
    suspend fun start(duration: RawNmeaDuration): StartResult {
        val avail = _availability.value
        if (avail !is RawNmeaAvailability.Available) return StartResult.Unavailable((avail as RawNmeaAvailability.Unavailable).reason)
        if (active.get() != null) return StartResult.AlreadyRecording
        val origin = avail.origin
        val meta = metadata(origin)
        val startedAt = wallClockMs()
        val startedMono = monoClockMs()
        val tz = zone()
        val session = try {
            withContext(ioDispatcher) {
                val f = files.newRecordingFile(startedAt, tz, meta.fileLabel)
                val s = Session(origin, f, files.metadataFor(f), startedAt, startedMono, duration.seconds, meta)
                // Initial companion file: an interrupted recording (process death) stays complete=false.
                s.metaFile.writeText(json(s, s.stats.snapshot(0), endedAtMs = null, termination = null, writerError = null, complete = false))
                s
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            log("Raw NMEA recording failed to start: ${e.javaClass.simpleName}")
            _state.value = RawNmeaState.Failed("Could not create the recording file (${e.javaClass.simpleName})")
            return StartResult.Failed(e.javaClass.simpleName)
        }
        if (!active.compareAndSet(null, session)) {
            withContext(ioDispatcher) { session.nmeaFile.delete(); session.metaFile.delete() }
            return StartResult.AlreadyRecording
        }
        _state.value = RawNmeaState.Recording(origin, session.nmeaFile, startedMono, duration.seconds?.let { it * 1000L }, session.stats.snapshot(0))
        log("Raw NMEA recording started source=${origin.name} duration=${duration.seconds ?: "manual"} file=${session.nmeaFile.name}")
        scope.launch(ioDispatcher) { writer(session) }
        duration.seconds?.let { secs ->
            session.timerJob = scope.launch(ioDispatcher) {
                delay(secs * 1000L)
                requestStop(session, RawNmeaTermination.DURATION)
            }
        }
        // The source may have changed while the file was being created.
        if (originOf(provider.value) != origin ||
            (origin == RawNmeaOrigin.EXTERNAL_TCP && link.value != ConnectionState.CONNECTED)) {
            requestStop(session, RawNmeaTermination.SOURCE_CHANGED)
        }
        return StartResult.Started(session.nmeaFile)
    }

    /** Ends the current recording (if any) with [reason]; the writer finalizes it. Safe from any thread. */
    fun stop(reason: RawNmeaTermination = RawNmeaTermination.USER_STOP) {
        active.get()?.let { requestStop(it, reason) }
    }

    /** First reason wins; detaches the session so the tap ignores further lines, and closes the queue. */
    private fun requestStop(s: Session, reason: RawNmeaTermination) {
        if (s.reason.compareAndSet(null, reason)) {
            active.compareAndSet(s, null)
            s.channel.close()
            s.timerJob?.cancel()
        }
    }

    private suspend fun writer(s: Session) {
        var out: OutputStream? = null
        var writerError: String? = null
        var lastPublish = monoClockMs()
        try {
            out = BufferedOutputStream(openOutput(s.nmeaFile), 64 * 1024)
            for (line in s.channel) {
                val text = line.trimEnd('\r', '\n')
                val bytes = (text + "\r\n").toByteArray(Charsets.UTF_8)
                if (s.stats.bytes + bytes.size > maxFileBytes) {
                    requestStop(s, RawNmeaTermination.SIZE_LIMIT)
                    s.channel.cancel()   // nothing more is written; the rest is past the limit
                    break
                }
                out.write(bytes)
                s.stats.add(text, bytes.size)
                val now = monoClockMs()
                if (now - lastPublish >= statsPublishIntervalMs) {
                    lastPublish = now
                    publishProgress(s)
                }
            }
            out.flush()
        } catch (ce: CancellationException) {
            requestStop(s, RawNmeaTermination.LIFECYCLE)
            throw ce
        } catch (e: Exception) {
            writerError = e.javaClass.simpleName
            failWriter(s)
        } finally {
            try {
                out?.close()
            } catch (e: Exception) {
                if (writerError == null) { writerError = e.javaClass.simpleName; failWriter(s) }
            }
            withContext(NonCancellable) { finish(s, writerError) }
        }
    }

    /** A write failure overrides any earlier reason; queued lines that will never be written count as dropped. */
    private fun failWriter(s: Session) {
        requestStop(s, RawNmeaTermination.WRITER_ERROR)
        s.reason.set(RawNmeaTermination.WRITER_ERROR)
        while (true) {
            if (s.channel.tryReceive().isSuccess) s.dropped.incrementAndGet() else break
        }
        s.channel.cancel()
    }

    private fun publishProgress(s: Session) {
        val cur = _state.value
        if (cur is RawNmeaState.Recording && cur.nmeaFile == s.nmeaFile) {
            _state.value = cur.copy(stats = s.stats.snapshot(s.dropped.get()))
        }
    }

    private suspend fun finish(s: Session, writerError: String?) {
        s.timerJob?.cancel()
        val reason = s.reason.get() ?: RawNmeaTermination.LIFECYCLE
        val dropped = s.dropped.get()
        val complete = reason.completesRecording && dropped == 0L && writerError == null
        val snapshot = s.stats.snapshot(dropped)
        val ended = wallClockMs()
        val durationMs = (monoClockMs() - s.startedMonoMs).coerceAtLeast(0)
        // Receiver-reported fields may arrive after the start (30 s device-info poll): fill only what was
        // missing, and only for the same receiver.
        val meta = if (reason == RawNmeaTermination.SOURCE_CHANGED) s.meta else runCatching {
            val now = metadata(s.origin)
            if (now.profileKey != s.meta.profileKey) s.meta else s.meta.copy(
                reportedModel = s.meta.reportedModel ?: now.reportedModel,
                reportedFirmware = s.meta.reportedFirmware ?: now.reportedFirmware,
                reportedSerial = s.meta.reportedSerial ?: now.reportedSerial,
            )
        }.getOrDefault(s.meta)
        try {
            withContext(ioDispatcher) {
                s.metaFile.writeText(json(s, snapshot, ended, reason, writerError, complete, meta, durationMs))
            }
        } catch (e: Exception) {
            log("Raw NMEA recording metadata write failed: ${e.javaClass.simpleName}")
        }
        val result = RawNmeaResult(s.nmeaFile, s.metaFile, reason, complete, snapshot, durationMs, writerError)
        _state.value = RawNmeaState.Finished(result)
        log("Raw NMEA recording ended reason=${reason.name} complete=$complete sentences=${snapshot.sentences} " +
            "bytes=${snapshot.bytes} dropped=$dropped invalidChecksum=${snapshot.checksumInvalid} malformed=${snapshot.malformed}" +
            (writerError?.let { " writerError=$it" } ?: ""))
    }

    private fun json(
        s: Session, stats: RawNmeaStatsSnapshot, endedAtMs: Long?, termination: RawNmeaTermination?,
        writerError: String?, complete: Boolean, meta: RawNmeaSourceMetadata = s.meta, durationMs: Long? = null,
    ): String = RawNmeaMetadataJson.write(
        RawNmeaMetadataJson.Input(
            nmeaFileName = s.nmeaFile.name, origin = s.origin, meta = meta,
            startedAtMs = s.startedAtMs, endedAtMs = endedAtMs, durationMs = durationMs,
            requestedDurationS = s.durationS, zone = zone(), stats = stats, maxFileBytes = maxFileBytes,
            termination = termination, writerError = writerError, complete = complete,
        )
    )

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 4096
        const val MAX_FILE_BYTES = 10L * 1024 * 1024

        fun originOf(p: ProviderChoice?): RawNmeaOrigin? = when (p) {
            ProviderChoice.INTERNAL -> RawNmeaOrigin.INTERNAL
            ProviderChoice.EXTERNAL_TCP -> RawNmeaOrigin.EXTERNAL_TCP
            ProviderChoice.REPLAY, null -> null
        }

        /** Raw NMEA exists only for a live source with a line hook: tablet GNSS, or a connected TCP receiver. */
        fun availabilityOf(p: ProviderChoice?, link: ConnectionState?): RawNmeaAvailability = when (p) {
            ProviderChoice.INTERNAL -> RawNmeaAvailability.Available(RawNmeaOrigin.INTERNAL)
            ProviderChoice.EXTERNAL_TCP ->
                if (link == ConnectionState.CONNECTED) RawNmeaAvailability.Available(RawNmeaOrigin.EXTERNAL_TCP)
                else RawNmeaAvailability.Unavailable("Raw NMEA unavailable: the external receiver is not connected.")
            ProviderChoice.REPLAY ->
                RawNmeaAvailability.Unavailable("Raw NMEA unavailable: NMEA replay is not a live source and is never recorded.")
            null -> RawNmeaAvailability.Unavailable("Raw NMEA unavailable: no GNSS source is active.")
        }
    }
}
