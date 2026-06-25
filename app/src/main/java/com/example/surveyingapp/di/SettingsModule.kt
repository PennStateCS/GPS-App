package com.example.surveyingapp.di

import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.gnss.capture.FixAcceptanceSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
     * Kept as a wrapper (rather than Hilt constructing [SettingsRepositoryImpl] directly) so there
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
}
