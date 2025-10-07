package com.example.surveyingapp.di

import android.content.Context
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.gnss.bus.FixBus
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.bus.adapters.ExternalAdapter
import com.example.surveyingapp.gnss.bus.adapters.ExternalNmeaSource
import com.example.surveyingapp.gnss.bus.adapters.FusedSource
import com.example.surveyingapp.gnss.bus.adapters.InternalAdapter
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.settings.SourceSettings
import com.example.surveyingapp.gnss.satellites.SatelliteInventory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Centralizes GNSS objects and exposes a single shared Graph.
 * - Uses SourceSettings (with ProviderChoice) for routing
 * - No legacy NMEA pipeline bits
 * - Thread-safe singleton with explicit lifecycle (shutdown)
 */
object GnssGraph {

    data class Graph(
        val scope: CoroutineScope,
        val bus: FixBus,                 // public stream of Fix
        val switchboard: FixSwitchboard, // routes internal/external
        val external: ExternalAdapter,
        val internal: InternalAdapter,
        val inventory: SatelliteInventory,
        private val cancel: () -> Unit   // lifecycle hook
    ) {
        fun shutdown() = cancel()
    }

    @Volatile private var instance: Graph? = null

    fun getOrCreate(
        context: Context,
        settingsRepository: SettingsRepository
    ): Graph {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: buildGraph(context.applicationContext, settingsRepository).also {
                instance = it
            }
        }
    }

    private fun buildGraph(
        appCtx: Context,
        settingsRepository: SettingsRepository
    ): Graph {
        // Managed app-scope (not MainScope), resilient to child failures
        val job   = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)

        // Satellite inventory (no external dependencies)
        val inventory = SatelliteInventory()

        // External GNSS (NMEA over TCP/BT/whatever your ExternalNmeaSource supports)
        val nmeaSource = ExternalNmeaSource(appCtx, scope)
        val external = ExternalAdapter(
            scope = scope,
            context = appCtx,
            settingsRepository = settingsRepository,
            nmea = nmeaSource,
            inv = inventory
        )

        // Minimal fused/internal source stub (replace with your real provider when ready)
        val fusedSource = object : FusedSource {
            private val flow = MutableSharedFlow<Fix>(replay = 0, extraBufferCapacity = 16)
            override fun fixes() = flow.asSharedFlow()
            override fun stop() { /* no-op */ }
        }
        val internal = InternalAdapter(scope = scope, fusedSource = fusedSource)

        // Routing configuration (ProviderChoice.INTERNAL by default)
        val sourceSettings = SourceSettings(
            _activeProvider    = MutableStateFlow(SourceSettings.ProviderChoice.INTERNAL),
            rs2Host            = MutableStateFlow("192.168.42.1"),
            connectionProfiles = MutableStateFlow(emptyList()),
            activeProfileId    = MutableStateFlow(null)
        )

        val switchboard = FixSwitchboard(
            scope = scope,
            sourceSettings = sourceSettings,
            internalAdapter = internal,
            externalAdapter = external
        )
        switchboard.start()

        // Public bus delegates to the switchboard's stream
        val bus = object : FixBus { override val fixes = switchboard.fixes }

        val cancel: () -> Unit = {
            // Orderly shutdown of adapters/switchboard, then scope
            try { switchboard.stop() } catch (_: Throwable) {}
            try { external.stop() } catch (_: Throwable) {}
            try { internal.stop() } catch (_: Throwable) {}
            job.cancel() // cancels all running coroutines
            instance = null
        }

        return Graph(scope, bus, switchboard, external, internal, inventory, cancel)
    }

    /** For tests / process restarts */
    fun resetForTests() {
        instance?.shutdown()
        instance = null
    }
}

/** Activities/fragments expose the shared graph via this interface */
interface HasGnssGraph { val gnssGraph: GnssGraph.Graph }

/** Back-compat alias if older code expects `fixSwitchboard` */
val GnssGraph.Graph.fixSwitchboard: FixSwitchboard get() = this.switchboard
