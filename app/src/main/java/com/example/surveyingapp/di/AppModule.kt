package com.example.surveyingapp.di

import android.content.Context
import androidx.room.Room
import com.example.surveyingapp.data.local.dao.CoordinateDao
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

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.bus.adapters.GsvMessage
import com.example.surveyingapp.gnss.settings.SourceSettings
import com.example.surveyingapp.gnss.satellites.SatelliteInventory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // --- Database + DAO ---
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "surveying_app.db")
            .fallbackToDestructiveMigration() // <- correct API
            .build()

    @Provides
    fun provideCoordinateDao(db: AppDatabase): CoordinateDao = db.coordinateDao()

    // --- Repository ---
    @Provides
    @Singleton
    fun provideCoordinateRepository(dao: CoordinateDao): CoordinateRepository =
        CoordinateRepositoryImpl(dao)

    // Provide SettingsRepository from the Application singleton until fully DI-managed
    @Provides
    @Singleton
    fun provideSettingsRepository(): SettingsRepository = SurveyingApp.settingsRepo

    // Minimal NMEA source stub so ExternalAdapter can construct; replace with real bridge later
    @Provides
    @Singleton
    fun provideNmeaSource(): NmeaSource = object : NmeaSource {
        private val fixesFlow = MutableSharedFlow<Fix>()
        private val gsvFlow = MutableSharedFlow<GsvMessage>()
        override fun start() { /* no-op */ }
        override fun stop() { /* no-op */ }
        override fun parsedFixes() = fixesFlow.asSharedFlow()
        override fun gsvStream() = gsvFlow.asSharedFlow()
    }

    // --- App-wide CoroutineScope (long-lived for GNSS streams/parsing) ---
    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- GNSS settings & satellite store ---
    @Provides
    @Singleton
    fun provideSourceSettings(): SourceSettings = SourceSettings(
        _activeProvider    = MutableStateFlow(SourceSettings.ProviderChoice.INTERNAL),
        rs2Host            = MutableStateFlow<String?>("192.168.42.1"),
        connectionProfiles = MutableStateFlow(emptyList()),
        activeProfileId    = MutableStateFlow<String?>(null)
    )

    @Provides
    @Singleton
    fun provideSatelliteInventory(): SatelliteInventory = SatelliteInventory()

    // --- Reach Device Repository for shared battery/device polling ---
    @Provides
    @Singleton
    fun provideReachDeviceRepository(): ReachDeviceRepository = ReachDeviceRepository()

    // --- Internal GNSS source (stub; wire to fused provider later) ---
    @Provides
    @Singleton
    fun provideFusedSource(): FusedSource = object : FusedSource {
        private val _fixes = MutableSharedFlow<Fix>(
            replay = 0,
            extraBufferCapacity = 16
        )
        override fun fixes(): SharedFlow<Fix> = _fixes.asSharedFlow()
        override fun stop() { /* no-op for now */ }
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
