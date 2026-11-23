package app.surrealar.gnss.external

import app.surrealar.gnss.source.ExternalReconnectable
import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaSource
import io.github.pennstatecs.geokit.gnss.bus.adapters.RawNmeaProvider
import io.github.pennstatecs.geokit.gnss.bus.adapters.SkyProvider
import io.github.pennstatecs.geokit.gnss.bus.adapters.SourceAdapter
import io.github.pennstatecs.geokit.gnss.bus.adapters.Startable
import io.github.pennstatecs.geokit.gnss.link.LinkLog
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.satellites.SatelliteInventory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The external receiver as the switchboard sees it: geokit's [ReconnectingNmeaAdapter] (connect,
 * exponential-backoff reconnect, health checks, sky) plus the app's receiver profile, whose label and
 * accuracy class are stamped onto every fix here.
 *
 * The name is kept on purpose: the switchboard's log lines name the bound adapter class.
 */
class ExternalAdapter(
    scope: CoroutineScope,
    nmea: NmeaSource,
    inv: SatelliteInventory,
    /**
     * The active receiver profile. Each fix is stamped with its label and accuracy class here (the
     * NMEA source and fuser don't know which receiver they talk to), so accuracy fallbacks, exports
     * and captures see the receiver that produced the fix even if the profile changes later.
     */
    currentProfile: () -> ExternalReceiverProfile,
    /** Monotonic clock for data-age and connect timing (never jumps with wall-clock corrections). */
    monotonicNowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    linkLog: LinkLog = DiagnosticsLinkLog,
) : SourceAdapter, SkyProvider, Startable, RawNmeaProvider, ExternalReconnectable {

    private val link = ReconnectingNmeaAdapter(
        scope = scope,
        nmea = nmea,
        inv = inv,
        stampFix = { fix: Fix ->
            val profile = currentProfile()
            fix.copy(accuracyClass = profile.accuracyClass, receiverLabel = profile.label)
        },
        linkLog = linkLog,
        monotonicNowMs = monotonicNowMs,
    )

    override val fixes: SharedFlow<Fix> get() = link.fixes
    override val sky: StateFlow<SkySnapshot> get() = link.sky
    override val rawNmea: SharedFlow<Unit> get() = link.rawNmea

    /** See [ReconnectingNmeaAdapter.connectionState]. */
    val connectionState: StateFlow<ReconnectingNmeaAdapter.ConnectionState> get() = link.connectionState
    /** See [ReconnectingNmeaAdapter.attemptCount]. */
    val attemptCount: StateFlow<Int> get() = link.attemptCount

    override fun start() = link.start()
    override fun stop() = link.stop()

    /**
     * Drops the current connection and reconnects using the CURRENT saved host/port. Used when the
     * user connects to a different receiver while External is already active — the provider does not
     * change, so the switchboard would not rebind and the old socket would stay up.
     */
    override fun reconnectNow(reason: String) = link.reconnectNow(reason)
}
