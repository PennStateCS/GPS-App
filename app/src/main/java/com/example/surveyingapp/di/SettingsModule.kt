package com.example.surveyingapp.di

import com.example.surveyingapp.gnss.settings.CaptureSettings
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
    fun provideCaptureSettings(): CaptureSettings {
        return CaptureSettings()
    }
}
