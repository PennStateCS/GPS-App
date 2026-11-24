package app.surrealar.util.diagnostics

import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.source.SourceRoutingDecisions
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.util.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds the shared GNSS flows into [GnssFeedTracker] from app scope, so GNSS lines are written
 * whatever screen is open. Read-only: it only collects existing flows.
 */
@Singleton
class GnssFeedLogger @Inject constructor(
    private val switchboard: FixSwitchboard,
    private val sourceSettings: SourceSettings,
    private val externalAdapter: app.surrealar.gnss.external.ExternalAdapter,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope,
) {
    val link = ReceiverLinkTracker { level, tag, msg -> log(level, tag, msg) }
    val feed = GnssFeedTracker(connId = { externalConnId() }) { level, msg -> log(level, DiagTags.GNSS, msg) }
    private val mismatch = SourceMismatchTracker()
    @Volatile private var started = false

    /** conn= for GNSS lines: only while the external receiver is the active source. */
    private fun externalConnId(): String? =
        if (sourceSettings.activeProvider.value == SourceSettings.ProviderChoice.EXTERNAL_TCP) link.connId else null

    fun start() {
        if (started) return
        started = true
        instance = this
        app.surrealar.gnss.external.DiagnosticsLinkLog.onSocketConnected = { link.onSocketConnected(now()) }
        appScope.launch { sourceSettings.activeProvider.collect { feed.onSource(it.name, now()) } }
        appScope.launch {
            switchboard.fixes.collect { fix ->
                feed.onFix(fix, now())
                ClockOffset.onFix(fix, System.currentTimeMillis())
            }
        }
        appScope.launch { switchboard.sky.collect { feed.onSky(it.totalUsed, it.totalVisible) } }
        appScope.launch {
            switchboard.rawNmea.collect {
                val t = now()
                feed.onNmea(t)
                if (sourceSettings.activeProvider.value == SourceSettings.ProviderChoice.EXTERNAL_TCP) link.onData(t)
            }
        }
        appScope.launch { externalAdapter.connectionState.collect { link.onConnectionState(it.name, now()) } }
        appScope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                // Written only when a bucket changed (or every 10 min), and never without a source/fix.
                feed.heartbeatIfDue(now())?.let { DiagnosticsLogger.i(DiagTags.GNSS, it) }
            }
        }
        // GNSS_FEED: a few comparisons a second; a line only on a held change.
        appScope.launch {
            while (isActive) {
                delay(FEED_CHECK_MS)
                feed.checkFeed(now())
            }
        }
        // SOURCE_MISMATCH: selected source vs live provider, logged once it has held MISMATCH_HOLD_MS.
        appScope.launch {
            runCatching {
                combine(
                    settingsRepository.locationSource, sourceSettings.activeProvider,
                    settingsRepository.externalTcpHost, settingsRepository.externalTcpPort,
                ) { selected, active, host, port ->
                    Triple(selected, active, SourceRoutingDecisions.classifySelectedActiveMismatch(
                        selected, active, SourceRoutingDecisions.externalConfigUsable(host, port)).reason)
                }.distinctUntilChanged().collectLatest { (selected, active, reason) ->
                    if (reason != null) delay(MISMATCH_HOLD_MS)
                    mismatch.onStable(selected.name, active.name, reason, externalConnId())?.let { (level, msg) ->
                        log(level, DiagTags.GNSS, msg)
                    }
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                DiagnosticsLogger.w(DiagTags.GNSS, "GNSS source mismatch monitor stopped", it)
            }
        }
    }

    companion object {
        /** How often the heartbeat is checked; see [GnssFeedTracker.heartbeatIfDue]. */
        const val HEARTBEAT_MS = 60_000L
        /** How often the feed state is checked; see [GnssFeedTracker.checkFeed]. */
        const val FEED_CHECK_MS = 1_000L
        /** A selected/active mismatch must hold this long (startup and source switches pass through one). */
        const val MISMATCH_HOLD_MS = 15_000L
        /** Set by ReceiverDeviceManager so its corrections flow reaches [link] without an early inject. */
        fun onCorrections(c: io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections?) { instance?.link?.onCorrections(c) }

        /** Set once started; the report reads live stats from it. */
        @Volatile var instance: GnssFeedLogger? = null
            private set

        fun now(): Long = System.nanoTime() / 1_000_000L

        internal fun log(level: Char, tag: String, msg: String) = when (level) {
            'E' -> DiagnosticsLogger.e(tag, msg)
            'W' -> DiagnosticsLogger.w(tag, msg)
            else -> DiagnosticsLogger.i(tag, msg)
        }
    }
}
