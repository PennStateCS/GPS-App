package com.example.surveyingapp.di

import android.content.Context
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.accumulator.FixAccumulator
import com.example.surveyingapp.gnss.bus.FixBus
import com.example.surveyingapp.gnss.bus.SkyBus
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.bus.adapters.NmeaSource
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import com.example.surveyingapp.gnss.external.ExternalAdapter
import com.example.surveyingapp.gnss.external.TcpNmeaSource
import com.example.surveyingapp.gnss.internal.InternalAdapter
import com.example.surveyingapp.gnss.internal.InternalNmeaSource
import com.example.surveyingapp.gnss.nmea.parse.*
import com.example.surveyingapp.gnss.source.SourceSettings
import com.example.surveyingapp.gnss.source.SourceSettings.ProviderChoice
import com.example.surveyingapp.gnss.satellites.SatelliteInventory
import com.example.surveyingapp.util.DiagnosticsLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * GNSS pipeline providers: NMEA parsing/registry, sources, adapters, satellite inventories,
 * the [FixSwitchboard] and its [FixBus]/[SkyBus] facets, and the [SourceSettings] startup seed.
 */
@Module
@InstallIn(SingletonComponent::class)
object GnssModule {

    /**
     * Provides a singleton [FixAccumulator] used by the NMEA replay feature
     * ([MainActivity]) and the Diagnostics screen.
     *
     * Note: the live GNSS path (internal GPS and TCP external) does NOT go through this
     * accumulator — it uses the [com.example.surveyingapp.gnss.bus.FixSwitchboard] pipeline
     * instead. The accumulator is deliberately kept separate so the replay feature can run
     * without interfering with live data.
     */
    @Provides
    @Singleton
    fun provideFixAccumulator(): FixAccumulator = FixAccumulator()

    /**
     * Provides a singleton [DiagnosticsService] for tracking NMEA processing metrics.
     */
    @Provides
    @Singleton
    fun provideDiagnosticsService(): DiagnosticsService = DiagnosticsService()

    /**
     * Provides the shared [NmeaRegistry] used by both the live GNSS pipeline
     * ([NmeaFuser] inside each [NmeaSource]) and the NMEA replay [NmeaReplayController].
     *
     * All sentence types supported by the app are registered here. To add a new sentence type:
     *  1. Create a data class implementing [NmeaSentence] in `gnss/nmea/sentence/`
     *  2. Create a [SentenceParser] in `gnss/nmea/parse/`
     *  3. Add it to this map
     *  4. Handle it in [NmeaFuser.handle] (live path) and [FixAccumulator.accept] (replay path)
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
            "GST" to GstParser()   // accuracy error statistics
        )
        return NmeaRegistry(parsers, verifyChecksum = true)
    }

    // NMEA source for external RS2+ adapter (TCP NMEA stream)
    @Provides
    @Singleton
    fun provideExternalNmeaSource(
        appScope: CoroutineScope,
        settingsRepository: SettingsRepository,
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
    fun provideSourceSettings(settingsRepository: SettingsRepository): SourceSettings {
        // Read the persisted selection for diagnostics, but ALWAYS start the live provider as
        // Internal. Seeding EXTERNAL_TCP here would make the switchboard bind the external adapter
        // on launch and route to a receiver that has not been re-validated this session — surfacing
        // stale/blank TCP data before any reachability check. Promotion to External happens only
        // through SettingsFragment's validated reconnect flow (connectViaTcpFlow), which confirms
        // the receiver is present before calling setActiveProvider(EXTERNAL_TCP).
        val persistedSource = runBlocking {
            try { settingsRepository.locationSource.first() } catch (_: Exception) { LocationSourceType.INTERNAL }
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
        evictionSeconds = 60.0  // Increased from default 30s to prevent premature eviction of sparse GLONASS updates
    )

    /**
     * Provides a dedicated [SatelliteInventory] for the external adapter.
     * Separate instances per adapter prevent stale satellite contamination when switching providers.
     */
    @Provides
    @Singleton
    @ExternalSatellites
    fun provideExternalSatelliteInventory(): SatelliteInventory = SatelliteInventory(
        evictionSeconds = 60.0
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
        @ExternalSatellites externalInventory: SatelliteInventory
    ): ExternalAdapter = ExternalAdapter(
        scope = appScope,
        nmea  = nmea,
        inv   = externalInventory
    )

    // --- Switchboard (single point to select/internal/external and expose flows) ---
    @Provides
    @Singleton
    fun provideFixSwitchboard(
        appScope: CoroutineScope,
        sourceSettings: SourceSettings,
        internalAdapter: InternalAdapter,
        externalAdapter: ExternalAdapter
    ): FixSwitchboard = FixSwitchboard(
        scope          = appScope,
        sourceSettings = sourceSettings,
        adapters       = mapOf(
            ProviderChoice.INTERNAL     to internalAdapter,
            ProviderChoice.EXTERNAL_TCP to externalAdapter
        )
    ).also { it.start() }

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
