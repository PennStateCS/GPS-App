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
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.bus.adapters.ExternalAdapter
import com.example.surveyingapp.gnss.bus.adapters.FusedSource
import com.example.surveyingapp.gnss.bus.adapters.InternalAdapter
import com.example.surveyingapp.gnss.bus.adapters.NmeaSource
import com.example.surveyingapp.gnss.settings.SourceSettings
import com.example.surveyingapp.gnss.satellites.SatelliteInventory
import com.example.surveyingapp.gnss.model.Fix
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
import kotlinx.coroutines.flow.SharedFlow
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
        settingsRepository: SettingsRepository
    ): NmeaSource {
        // Real TCP NMEA source that connects to RS2+ receiver
        return com.example.surveyingapp.gnss.bus.adapters.TcpNmeaSource(
            scope = appScope,
            settingsRepository = settingsRepository
        )
    }

    // --- App-wide CoroutineScope (long-lived for GNSS streams/parsing) ---
    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- GNSS settings & satellite store ---
    @Provides
    @Singleton
    @Suppress("UNUSED_PARAMETER")
    fun provideSourceSettings(
        settingsRepository: SettingsRepository,
        appScope: CoroutineScope
    ): SourceSettings {
        // Initialize provider synchronously from persisted settings so UI/components
        // reading SourceSettings immediately (at app startup) see the correct choice.
        val providerChoice = runBlocking {
            val loc = try { settingsRepository.locationSource.first() } catch (_: Exception) { com.example.surveyingapp.domain.model.LocationSourceType.INTERNAL }
            when (loc) {
                com.example.surveyingapp.domain.model.LocationSourceType.EXTERNAL -> SourceSettings.ProviderChoice.RS2_EXTERNAL
                else -> SourceSettings.ProviderChoice.INTERNAL
            }
        }
        val initialProvider = MutableStateFlow(providerChoice)

        return SourceSettings(
            _activeProvider    = initialProvider,
            rs2Host            = MutableStateFlow<String?>("192.168.42.1"),
            connectionProfiles = MutableStateFlow(emptyList()),
            activeProfileId    = MutableStateFlow<String?>(null)
        )
    }

    @Provides
    @Singleton
    fun provideSatelliteInventory(): SatelliteInventory = SatelliteInventory()

    // --- Reach Device Repository for shared battery/device polling ---
    @Provides
    @Singleton
    fun provideReachDeviceRepository(): ReachDeviceRepository = ReachDeviceRepository()

    // --- Internal GNSS source (uses device's internal GPS via NMEA) ---
    @Provides
    @Singleton
    fun provideFusedSource(
        @ApplicationContext context: Context,
        appScope: CoroutineScope
    ): FusedSource {
        // Use InternalNmeaSource to get NMEA data from Android's internal GPS
        val internalNmea = com.example.surveyingapp.gnss.bus.adapters.InternalNmeaSource(context, appScope)

        return object : FusedSource, com.example.surveyingapp.gnss.bus.Startable {
            override fun fixes(): SharedFlow<Fix> = internalNmea.parsedFixes()
            override fun start() {
                android.util.Log.d("FusedSource", "Starting InternalNmeaSource")
                internalNmea.start()
            }
            override fun stop() {
                android.util.Log.d("FusedSource", "Stopping InternalNmeaSource")
                internalNmea.stop()
            }
        }
    }

    @Provides
    @Singleton
    fun provideInternalAdapter(
        appScope: CoroutineScope,
        fused: FusedSource
    ): InternalAdapter = InternalAdapter(appScope, fused)

    // --- External adapter (wire with required deps) ---
    @Provides
    @Singleton
    fun provideExternalAdapter(
        appScope: CoroutineScope,
        @ApplicationContext appContext: Context,
        settingsRepository: SettingsRepository,
        nmea: NmeaSource,
        inv: SatelliteInventory
    ): ExternalAdapter = ExternalAdapter(
        scope = appScope,
        context = appContext,
        settingsRepository = settingsRepository,
        nmea = nmea,
        inv = inv
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
        scope           = appScope,
        sourceSettings  = sourceSettings,
        internalAdapter = internalAdapter,
        externalAdapter = externalAdapter
    ).also { it.start() }
}
