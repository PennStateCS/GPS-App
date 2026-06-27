package com.example.surveyingapp.di

import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.domain.repository.AppearanceSettingsRepository
import com.example.surveyingapp.domain.repository.ArDisplaySettingsRepository
import com.example.surveyingapp.domain.repository.CoordinateDisplaySettingsRepository
import com.example.surveyingapp.domain.repository.DeveloperSettingsRepository
import com.example.surveyingapp.domain.repository.ExternalReceiverSettingsRepository
import com.example.surveyingapp.domain.repository.GnssCaptureSettingsRepository
import com.example.surveyingapp.domain.repository.GnssReceiverSettingsRepository
import com.example.surveyingapp.domain.repository.LocationSourceSettingsRepository
import com.example.surveyingapp.domain.repository.MockLocationSettingsRepository
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.domain.repository.MapSettingsRepository
import com.example.surveyingapp.domain.repository.StakeoutSettingsRepository
import com.example.surveyingapp.gnss.capture.FixAcceptanceSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for settings.
 *
 * ## Single-owner rule (do not break)
 * There must be exactly ONE production Preferences DataStore for the `app_settings` file and exactly
 * ONE [com.example.surveyingapp.data.settings.repository.SettingsRepositoryImpl] over it. That single
 * instance is built by [SurveyingApp.setupSettings] during `Application.onCreate` (early, so startup
 * theme + mock-location publishing can read settings synchronously) and exposed as
 * [SurveyingApp.settingsRepo].
 *
 * Every provider here — the aggregate [SettingsRepository] and all the focused settings interfaces —
 * intentionally **returns that same singleton** via `@Provides`. They are deliberately NOT `@Binds`:
 * `@Binds` would have Hilt construct its own `SettingsRepositoryImpl`, which would open a SECOND
 * DataStore over the same file and crash at runtime (`IllegalStateException: There are multiple
 * DataStores active for the same file`). Do not convert these to `@Binds` or add a
 * `@Provides`/`@Inject`-constructed `SettingsRepositoryImpl` unless ownership is deliberately
 * redesigned with a startup-order migration (see `docs/settings-architecture.md` → Production
 * ownership). `ArchitectureGuardTest` guards against accidental second construction.
 *
 * Tests may build their own isolated DataStores (e.g. `PreferenceDataStoreFactory.create` over a temp
 * file) — that is test-only code and does not touch the production file.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun provideFixAcceptanceSettings(): FixAcceptanceSettings {
        return FixAcceptanceSettings()
    }

    /**
     * Provides [SettingsRepository] from the [SurveyingApp] application singleton.
     *
     * Kept as a wrapper (rather than Hilt constructing `SettingsRepositoryImpl` directly) so there
     * is exactly ONE [com.example.surveyingapp.data.settings.datastore.SettingsLocalDataSource] /
     * DataStore for the settings file. `SurveyingApp.setupSettings()` builds this instance during
     * `onCreate` and uses it synchronously for startup work (theme application, mock-location
     * publisher). Having Hilt build a second instance would create a duplicate DataStore over the
     * same file and crash at runtime. The injected instance is therefore identical to the app
     * singleton — see PROMPT notes.
     */
    @Provides
    @Singleton
    fun provideSettingsRepository(): SettingsRepository = SurveyingApp.settingsRepo

    // ── Focused settings interfaces ─────────────────────────────────────────────
    // Each resolves to the SAME aggregate singleton (`SurveyingApp.settingsRepo`), which implements
    // every focused interface. @Provides (not @Binds) for the same reason as the aggregate above:
    // the impl is built by `SurveyingApp.setupSettings()` so there is exactly one DataStore; letting
    // Hilt construct it via @Binds would create a duplicate DataStore over the same file. Injecting a
    // focused interface therefore shares state with the aggregate and with every other interface.

    @Provides
    @Singleton
    fun provideLocationSourceSettingsRepository(): LocationSourceSettingsRepository = SurveyingApp.settingsRepo

    @Provides
    @Singleton
    fun provideExternalReceiverSettingsRepository(): ExternalReceiverSettingsRepository = SurveyingApp.settingsRepo

    @Provides
    @Singleton
    fun provideGnssCaptureSettingsRepository(): GnssCaptureSettingsRepository = SurveyingApp.settingsRepo

    @Provides
    @Singleton
    fun provideArDisplaySettingsRepository(): ArDisplaySettingsRepository = SurveyingApp.settingsRepo

    @Provides
    @Singleton
    fun provideCoordinateDisplaySettingsRepository(): CoordinateDisplaySettingsRepository = SurveyingApp.settingsRepo

    @Provides
    @Singleton
    fun provideMockLocationSettingsRepository(): MockLocationSettingsRepository = SurveyingApp.settingsRepo

    @Provides
    @Singleton
    fun provideGnssReceiverSettingsRepository(): GnssReceiverSettingsRepository = SurveyingApp.settingsRepo

    @Provides
    @Singleton
    fun provideDeveloperSettingsRepository(): DeveloperSettingsRepository = SurveyingApp.settingsRepo

    @Provides
    @Singleton
    fun provideAppearanceSettingsRepository(): AppearanceSettingsRepository = SurveyingApp.settingsRepo

    @Provides
    @Singleton
    fun provideStakeoutSettingsRepository(): StakeoutSettingsRepository = SurveyingApp.settingsRepo

    @Provides
    @Singleton
    fun provideMapSettingsRepository(): MapSettingsRepository = SurveyingApp.settingsRepo
}
