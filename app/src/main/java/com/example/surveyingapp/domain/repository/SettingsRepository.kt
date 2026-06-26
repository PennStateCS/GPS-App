package com.example.surveyingapp.domain.repository

/**
 * Aggregate settings repository — the union of all focused settings interfaces (see
 * [FocusedSettingsRepositories.kt]). It is retained for compatibility and for screens that
 * genuinely touch many settings areas at once (e.g. SettingsFragment, MainActivity).
 *
 * **New code should prefer a focused interface** (e.g. [GnssCaptureSettingsRepository],
 * [ArDisplaySettingsRepository], [ExternalReceiverSettingsRepository]) so a consumer depends only on
 * the settings area it uses. All interfaces — aggregate and focused — resolve to the same singleton
 * [com.example.surveyingapp.data.settings.repository.SettingsRepositoryImpl] over the single
 * Preferences DataStore; see `di/SettingsModule.kt` and `docs/settings-architecture.md`.
 */
interface SettingsRepository :
    LocationSourceSettingsRepository,
    ExternalReceiverSettingsRepository,
    GnssCaptureSettingsRepository,
    ArDisplaySettingsRepository,
    CoordinateDisplaySettingsRepository,
    MockLocationSettingsRepository,
    GnssReceiverSettingsRepository,
    DeveloperSettingsRepository,
    AppearanceSettingsRepository,
    StakeoutSettingsRepository
