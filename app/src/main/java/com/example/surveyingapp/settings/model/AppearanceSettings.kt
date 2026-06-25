package com.example.surveyingapp.settings.model

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

data class AppearanceSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val showLiveGnssStatusBar: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val maxBrightnessWhileOpen: Boolean = false
)
