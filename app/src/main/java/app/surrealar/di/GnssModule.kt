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
import app.surrealar.gnss.external.ExternalAdapter
import app.surrealar.gnss.external.TcpNmeaSource
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
     * as live data. Replaces the old FixAccumulator, whose output nothing observed.
     */
    @Provides
    @Singleton
    fun provideReplayAdapter(appScope: CoroutineScope): app.surrealar.gnss.replay.ReplayAdapter =
        app.surrealar.gnss.replay.ReplayAdapter(appScope)

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
     * All sentence types supported by the app are registered here. To add a new sentence type:
     *  1. Create a data class implementing `NmeaSentence` in `gnss/nmea/sentence/`
     *  2. Create a [SentenceParser] in `gnss/nmea/parse/`
     *  3. Add it to this map
     *  4. Handle it in `NmeaFuser.handle` (live and replay paths)
     */
    @Provides
    @Singleton
    fun provideNmeaRegistry(): NmeaRegistry {
        val parsers = mapOf(
            "GGA" to GgaParser(),
            "RMC" to RmcParser(),
            "GSA" to GsaParser(),
            "GSV" to GsvParser(),
            "ZDA" to ZdaParser(),
            "GST" to GstParser(),  // accuracy error statistics
            // MediaTek proprietary accuracy (e.g. Samsung SM-X940 internal GNSS) — fallback when no GST.
            "ACCURACY" to AccuracyParser(),
        ) +
            // Receiver-specific sentences declared by the profiles (Emlid: EBP, ETC — diagnostics only).
            // TcpNmeaSource keeps this one registry for the app's lifetime, so it registers every
            // profile's parsers; a sentence a receiver never sends simply never arrives.
            app.surrealar.settings.model.ExternalReceiverProfile.allExtraParsers()
        // Live sources (Emlid TCP, Android internal NMEA) always send "*CS". Requiring it rejects a
        // partial line that readLine() returns when the socket drops mid-sentence. Replay builds its
        // own lenient registry (MainActivity) so checksum-less log files still play.
        return NmeaRegistry(parsers, verifyChecksum = true, requireChecksum = true)
    }

    // NMEA source for external RS2+ adapter (TCP NMEA stream)
    @Provides
    @Singleton
    fun provideExternalNmeaSource(
        appScope: CoroutineScope,
        settingsRepository: ExternalReceiverSettingsRepository,
        sourceSettings: SourceSettings,
        registry: NmeaRegistry,
        diagnosticsService: DiagnosticsService
    ): NmeaSource = TcpNmeaSource(
        scope               = appScope,
        settingsRepository  = settingsRepository,
        sourceSettings      = sourceSettings,  // Reserved for future profile support
        registry            = registry,
        diagnostics         = diagnosticsService
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
                DiagnosticsLogger.w("GNSS", "Startup: could not read the persisted source; assuming INTERNAL", e)
                LocationSourceType.INTERNAL
            }
        }
        val startupProvider = ProviderChoice.INTERNAL
        DiagnosticsLogger.i(
            "GNSS",
            "Startup: persistedSource=$persistedSource activeProvider=$startupProvider" +
                if (persistedSource == LocationSourceType.EXTERNAL)
                    " (External will reconnect after validation, not activated blindly)" else ""
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
        )
    }

    /** Lets GnssSourceCoordinator reconnect the external receiver when its saved host/port change. */
    @Provides
    @Singleton
    fun provideExternalReconnectable(externalAdapter: ExternalAdapter): app.surrealar.gnss.source.ExternalReconnectable =
        externalAdapter

    // --- Switchboard (single point to select/internal/external and expose flows) ---
    @Provides
    @Singleton
    fun provideFixSwitchboard(
        appScope: CoroutineScope,
        sourceSettings: SourceSettings,
        internalAdapter: InternalAdapter,
        externalAdapter: ExternalAdapter,
        replayAdapter: app.surrealar.gnss.replay.ReplayAdapter,
        coordinateSettings: app.surrealar.domain.repository.CoordinateDisplaySettingsRepository,
    ): FixSwitchboard {
        // Settings → Coordinates → External Receiver Datum. Internal GNSS is always WGS84.
        val rxDatum = coordinateSettings.coordinateSystemSettings
            .map { it.externalReceiverDatum }
            .stateIn(appScope, SharingStarted.Eagerly, app.surrealar.settings.model.ReceiverDatum.ITRF2014)
        return FixSwitchboard(
            scope          = appScope,
            sourceSettings = sourceSettings,
            adapters       = mapOf(
                ProviderChoice.INTERNAL     to internalAdapter,
                ProviderChoice.EXTERNAL_TCP to externalAdapter,
                ProviderChoice.REPLAY       to replayAdapter,
            ),
            fixTransform   = { fix ->
                if (fix.provider != io.github.pennstatecs.geokit.gnss.model.Provider.INTERNAL &&
                    rxDatum.value == app.surrealar.settings.model.ReceiverDatum.NAD83_2011
                ) app.surrealar.coords.FeedDatum.nad83ToWgs84(fix) else fix
            },
        ).also { it.start() }
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
