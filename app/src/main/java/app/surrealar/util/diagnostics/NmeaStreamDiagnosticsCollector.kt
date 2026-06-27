package app.surrealar.util.diagnostics

import android.content.Context
import app.surrealar.SurRealApplicationEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

/**
 * Gathers the external receiver settings + the external NMEA source's fuser stats and renders the
 * "NMEA Stream Diagnostics" report section via [NmeaStreamDiagnosticsFormatter].
 *
 * Reads existing state only (through the Hilt entry point — no service-locator, no new global
 * state). Sanitized: no raw NMEA, no live/base coordinates.
 */
object NmeaStreamDiagnosticsCollector {

    suspend fun collect(context: Context): String = try {
        val ep = EntryPointAccessors.fromApplication(
            context.applicationContext, SurRealApplicationEntryPoint::class.java
        )
        val repo = ep.settingsRepository()
        val profile = runCatching { repo.externalReceiverProfile.first() }.getOrNull()
        val connType = runCatching { repo.externalConnType.first() }.getOrNull()
        val host = runCatching { repo.externalTcpHost.first() }.getOrNull()
        val port = runCatching { repo.externalTcpPort.first() }.getOrNull()
        val source = ep.externalNmeaSource()

        NmeaStreamDiagnosticsFormatter.format(
            profileLabel = profile?.label ?: "unknown",
            connType = connType?.name,
            host = host,
            port = port,
            timing = source.nmeaTimingStats(),
            custom = source.nmeaCustomStats(),
        )
    } catch (e: Exception) {
        "=== NMEA Stream Diagnostics ===\n(unavailable: ${e.message})\n"
    }
}
