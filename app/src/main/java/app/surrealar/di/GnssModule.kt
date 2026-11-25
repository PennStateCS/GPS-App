package app.surrealar.di

import android.content.Context
import app.surrealar.domain.repository.ExternalReceiverSettingsRepository
import app.surrealar.domain.repository.LocationSourceSettingsRepository
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.bus.FixBus
import app.surrealar.gnss.bus.SkyBus
import app.surrealar.gnss.bus.FixSwitchboard
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaSource
import app.surrealar.gnss.diagnostics.DiagnosticsService
import app.surrealar.gnss.external.DiagnosticsLinkLog
import app.surrealar.gnss.external.ExternalAdapter
import io.github.pennstatecs.geokit.gnss.link.TcpNmeaSource
import io.github.pennstatecs.geokit.gnss.replay.ReplayAdapter
import app.surrealar.gnss.internal.InternalAdapter
import app.surrealar.gnss.internal.InternalNmeaSource
import io.github.pennstatecs.geokit.gnss.nmea.parse.*
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import io.github.pennstatecs.geokit.gnss.satellites.SatelliteInventory
import app.surrealar.util.DiagnosticsLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import javax.inject.Singleton

/**
 * GNSS pipeline providers: NMEA parsing/registry, sources, adapters, satellite inventories,
 * the [FixSwitchboard] and its [FixBus]/[SkyBus] facets, and the [SourceSettings] startup seed.
 */
@Module
@InstallIn(SingletonComponent::class)
object GnssModule {

    /**
     * NMEA replay as a regular source (ProviderChoice.REPLAY): same NmeaFuser and switchboard path
     * as live data. Replaces the old FixAccumulator, whose output nothing observed. It registers the
     * same receiver-specific parsers as the live registry, so a recorded Emlid log replays as before.
     */
    @Provides
    @Singleton
    fun provideReplayAdapter(appScope: CoroutineScope): ReplayAdapter =
        ReplayAdapter(
            appScope,
            log = DiagnosticsLinkLog,   // GNSS lines to DiagnosticsLogger, as before
            extraParsers = app.surrealar.settings.model.ExternalReceiverProfile.allExtraParsers(),
        )

    /**
     * Provides a singleton [DiagnosticsService] for tracking NMEA processing metrics.
     */
    @Provides
    @Singleton
    fun provideDiagnosticsService(): DiagnosticsService = DiagnosticsService()

    /**
     * Provides the shared [NmeaRegistry] used by both the live GNSS pipeline
     * (`NmeaFuser` inside each [NmeaSource]). Replay builds its own lenient registry.
     *
     * geokit-gnss's standard parsers (GGA, RMC, GSA, GSV, ZDA, GST, MediaTek `$..ACCURACY`) plus the
     * receiver-specific sentences declared by the profiles (Emlid: EBP, ETC from
     * geokit-receiver-emlid — diagnostics only). TcpNmeaSource keeps this one registry for the app's
     * lifetime, so it registers every profile's parsers; a sentence a receiver never sends simply
     * never arrives. A new proprietary sentence goes in its receiver module and that profile's
     * `extraParsers`; the fuser counts it per tag (`NmeaSource.nmeaExtraStats()`).
     */
    @Provides
    @Singleton
    fun provideNmeaRegistry(): NmeaRegistry =
        // Live sources (Emlid TCP, Android internal NMEA) always send "*CS". Requiring it rejects a
        // partial line that readLine() returns when the socket drops mid-sentence. Replay builds its
        // own lenient registry so checksum-less log files still play.
        DefaultNmeaRegistry.create(
            verifyChecksum = true,
            requireChecksum = true,
            extraParsers = app.surrealar.settings.model.ExternalReceiverProfile.allExtraParsers(),
        )

    // NMEA source for external RS2+ adapter (TCP NMEA stream)
    @Provides
    @Singleton
    fun provideExternalNmeaSource(
        appScope: CoroutineScope,
        settingsRepository: ExternalReceiverSettingsRepository,
        registry: NmeaRegistry,
        diagnosticsService: DiagnosticsService,
        tiltMonitor: app.surrealar.receiver.tilt.ReceiverTiltMonitor,
    ): NmeaSource = TcpNmeaSource(
        scope    = appScope,
        // Saved host/port, read once per connection attempt; null when either is missing.
        endpoint = {
            val host = settingsRepository.externalTcpHost.first()
            val port = settingsRepository.externalTcpPort.first()
            if (host.isNullOrBlank() || port == null) null else host to port
        },
        registry = registry,
        // The live NMEA rate/diagnostics counters, and the RS4 tilt status (ETC lines only; status, never a fix).
        onLine   = { line -> diagnosticsService.recordLine(line); tiltMonitor.onLine(line) },
        linkLog  = DiagnosticsLinkLog,               // shared with ExternalAdapter (same throttle keys)
    )

    // --- GNSS settings & satellite store ---
    @Provides
    @Singleton
    fun provideSourceSettings(settingsRepository: LocationSourceSettingsRepository): SourceSettings {
        // Read the persisted selection for diagnostics, but ALWAYS start the live provider as
        // Internal. Seeding EXTERNAL_TCP here would make the switchboard bind the external adapter
        // on launch and route to a receiver that has not been re-validated this session — surfacing
        // stale/blank TCP data before any reachability check. Promotion to External happens only
        // through SettingsFragment's validated reconnect flow (connectViaTcpFlow), which confirms
        // the receiver is present before calling setActiveProvider(EXTERNAL_TCP).
        val persistedSource = runBlocking {
            try { settingsRepository.locationSource.first() } catch (e: Exception) {
                DiagnosticsLogger.w("GNSS", "GNSS source read failed: assuming INTERNAL", e)
                LocationSourceType.INTERNAL
            }
        }
        val startupProvider = ProviderChoice.INTERNAL
        DiagnosticsLogger.i(
            "GNSS",
            "GNSS source initialized persistedSource=$persistedSource activeProvider=$startupProvider" +
                if (persistedSource == LocationSourceType.EXTERNAL)
                    " externalPending=true" else ""
        )
        return SourceSettings(
            _activeProvider    = MutableStateFlow(startupProvider),
            connectionProfiles = MutableStateFlow(emptyList()),
            activeProfileId    = MutableStateFlow(null)
        )
    }

    // --- Internal GNSS source (uses device's internal GPS via NMEA) ---
    @Provides
    @Singleton
    fun provideInternalNmeaSource(
        @ApplicationContext context: Context,
        appScope: CoroutineScope,
        registry: NmeaRegistry,
        diagnosticsService: DiagnosticsService
    ): InternalNmeaSource = InternalNmeaSource(context, appScope, registry, diagnosticsService)

    /**
     * Provides a dedicated [SatelliteInventory] for the internal adapter.
     * Separate instances per adapter prevent stale satellite contamination when switching providers.
     */
    @Provides
    @Singleton
    @InternalSatellites
    fun provideInternalSatelliteInventory(): SatelliteInventory = SatelliteInventory(
        evictionSeconds = 60.0,  // Increased from default 30s to prevent premature eviction of sparse GLONASS updates
        debugName = "internal"
    )

    /**
     * Provides a dedicated [SatelliteInventory] for the external adapter.
     * Separate instances per adapter prevent stale satellite contamination when switching providers.
     */
    @Provides
    @Singleton
    @ExternalSatellites
    fun provideExternalSatelliteInventory(): SatelliteInventory = SatelliteInventory(
        evictionSeconds = 60.0,
        debugName = "external"
    )

    @Provides
    @Singleton
    fun provideInternalAdapter(
        appScope: CoroutineScope,
        internalNmea: InternalNmeaSource,
        @InternalSatellites internalInventory: SatelliteInventory
    ): InternalAdapter = InternalAdapter(appScope, internalNmea, internalInventory)

    // --- External adapter (wire with required deps) ---
    @Provides
    @Singleton
    fun provideExternalAdapter(
        appScope: CoroutineScope,
        nmea: NmeaSource,
        @ExternalSatellites externalInventory: SatelliteInventory,
        settingsRepository: ExternalReceiverSettingsRepository,
    ): ExternalAdapter {
        // Latest saved profile, kept hot so stamping a fix never suspends.
        val profile = settingsRepository.externalReceiverProfile.stateIn(
            appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
            app.surrealar.settings.model.ExternalReceiverProfile.DEFAULT
        )
        return ExternalAdapter(
            scope = appScope,
            nmea  = nmea,
            inv   = externalInventory,
            currentProfile = { profile.value },
            monotonicNowMs = { android.os.SystemClock.elapsedRealtime() },
            linkLog = DiagnosticsLinkLog,
        )
    }

    /** Lets GnssSourceCoordinator reconnect the external receiver when its saved host/port change. */
    @Provides
    @Singleton
    fun provideExternalReconnectable(externalAdapter: ExternalAdapter): app.surrealar.gnss.source.ExternalReconnectable =
        externalAdapter

    /**
     * Settings → Coordinates → External Receiver Datum as the feed applies it, plus which datum
     * normalised each published fix (captures accept only fixes normalised under their occupation's
     * datum). Internal GNSS is always WGS84.
     */
    @Provides
    @Singleton
    fun provideReceiverFeedDatum(
        appScope: CoroutineScope,
        coordinateSettings: app.surrealar.domain.repository.CoordinateDisplaySettingsRepository,
    ): app.surrealar.gnss.source.ReceiverFeedDatum = app.surrealar.gnss.source.ReceiverFeedDatum(
        coordinateSettings.coordinateSystemSettings
            .map { it.externalReceiverDatum }
            .stateIn(appScope, SharingStarted.Eagerly, app.surrealar.settings.model.ReceiverDatum.ITRF2014)
    )

    // --- Switchboard (single point to select/internal/external and expose flows) ---
    @Provides
    @Singleton
    fun provideFixSwitchboard(
        appScope: CoroutineScope,
        sourceSettings: SourceSettings,
        internalAdapter: InternalAdapter,
        externalAdapter: ExternalAdapter,
        replayAdapter: ReplayAdapter,
        feedDatum: app.surrealar.gnss.source.ReceiverFeedDatum,
        tiltMonitor: app.surrealar.receiver.tilt.ReceiverTiltMonitor,
    ): FixSwitchboard {
        return FixSwitchboard(
            scope          = appScope,
            sourceSettings = sourceSettings,
            adapters       = mapOf(
                ProviderChoice.INTERNAL     to internalAdapter,
                ProviderChoice.EXTERNAL_TCP to externalAdapter,
                ProviderChoice.REPLAY       to replayAdapter,
            ),
            // Non-internal fixes: FixDatumNormalizer.toWgs84 with the current receiver datum (unchanged
            // maths), recording the datum used per fix.
            fixTransform   = feedDatum::normalise,
        ).also {
            it.start()
            // Tilt status: interpret each external fix; forget ETC state when the link reconnects.
            tiltMonitor.observe(it.fixes)
            tiltMonitor.resetOn(externalAdapter.connectionState)
        }
    }

    /**
     * Exposes [FixSwitchboard] as [FixBus] so ViewModels and other consumers can
     * depend on the interface rather than the concrete class.
     */
    @Provides
    @Singleton
    fun provideFixBus(switchboard: FixSwitchboard): FixBus = switchboard

    /**
     * Exposes [FixSwitchboard] as [SkyBus] for satellite sky consumers.
     */
    @Provides
    @Singleton
    fun provideSkyBus(switchboard: FixSwitchboard): SkyBus = switchboard
}
