package com.example.surveyingapp.di

import android.content.Context
import androidx.room.Room
import com.example.surveyingapp.data.local.dao.CoordinateDao
import com.example.surveyingapp.data.local.dao.ModelDao
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.CoordinateRepositoryImpl
import com.example.surveyingapp.domain.repository.CoordinateRepository
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.domain.repository.ReachDeviceRepository
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import com.example.surveyingapp.gnss.bus.FixBus
import com.example.surveyingapp.gnss.bus.SkyBus
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.bus.adapters.ExternalAdapter
import com.example.surveyingapp.gnss.bus.adapters.InternalAdapter
import com.example.surveyingapp.gnss.bus.adapters.NmeaSource
import com.example.surveyingapp.gnss.bus.adapters.TcpNmeaSource
import com.example.surveyingapp.gnss.bus.adapters.InternalNmeaSource
import com.example.surveyingapp.gnss.nmea.parse.NmeaRegistry
import com.example.surveyingapp.gnss.settings.SourceSettings
import com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice
import com.example.surveyingapp.gnss.satellites.SatelliteInventory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // --- Database + DAO ---
    @Provides
    @Singleton
    @Suppress("DEPRECATION")
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "surveying_app.db")
            .fallbackToDestructiveMigration() // <- correct API
            .build()

    @Provides
    fun provideCoordinateDao(db: AppDatabase): CoordinateDao = db.coordinateDao()

    @Provides
    fun provideModelDao(db: AppDatabase): ModelDao = db.modelDao()

    // --- Repository ---
    @Provides
    @Singleton
    fun provideCoordinateRepository(dao: CoordinateDao): CoordinateRepository =
        CoordinateRepositoryImpl(dao)

    // Provide SettingsRepository from the Application singleton until fully DI-managed
    @Provides
    @Singleton
    fun provideSettingsRepository(): SettingsRepository = SurveyingApp.settingsRepo

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

    // --- App-wide CoroutineScope (long-lived for GNSS streams/parsing) ---
    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- GNSS settings & satellite store ---
    @Provides
    @Singleton
    @Suppress("UNUSED_PARAMETER")
    fun provideSourceSettings(settingsRepository: SettingsRepository): SourceSettings {
        // Initialize provider synchronously from persisted settings so UI/components
        // reading SourceSettings immediately (at app startup) see the correct choice.
        val providerChoice = runBlocking {
            val loc = try { settingsRepository.locationSource.first() } catch (_: Exception) { com.example.surveyingapp.domain.model.LocationSourceType.INTERNAL }
            when (loc) {
                com.example.surveyingapp.domain.model.LocationSourceType.EXTERNAL -> ProviderChoice.EXTERNAL_TCP
                else -> ProviderChoice.INTERNAL
            }
        }
        return SourceSettings(
            _activeProvider    = MutableStateFlow(providerChoice),
            connectionProfiles = MutableStateFlow(emptyList()),
            activeProfileId    = MutableStateFlow(null)
        )
    }

    // --- Reach Device Repository for shared battery/device polling ---
    @Provides
    @Singleton
    fun provideReachDeviceRepository(sourceSettings: SourceSettings): ReachDeviceRepository =
        ReachDeviceRepository(sourceSettings)

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
