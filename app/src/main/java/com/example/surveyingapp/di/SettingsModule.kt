package com.example.surveyingapp.di

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
}
