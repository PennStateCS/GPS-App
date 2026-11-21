package app.surrealar.util.diagnostics

import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.util.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
    private val appScope: CoroutineScope,
) {
    val feed = GnssFeedTracker { level, msg -> log(level, DiagTags.GNSS, msg) }
    val link = ReceiverLinkTracker { level, tag, msg -> log(level, tag, msg) }
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        instance = this
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
                DiagnosticsLogger.i(DiagTags.GNSS, feed.heartbeat(now()))
            }
        }
    }

    companion object {
        const val HEARTBEAT_MS = 60_000L
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