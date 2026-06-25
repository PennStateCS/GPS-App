package com.example.surveyingapp.domain.model

/**
 * Persisted selection enums for the location/GNSS source.
 *
 * Both enums are stored in DataStore (via `SettingsLocalDataSource`, by their string names) and
 * read pervasively across the GNSS source/restore code, the toolbar mapper, capture, and the
 * Settings screen. **Do not rename these constants** — the names are the persisted keys/values.
 *
 * - [LocationSourceType] is the user's *selected* source (what Settings persists). The live
 *   *active provider* is a separate runtime concept owned by
 *   [com.example.surveyingapp.gnss.source.SourceSettings] (`ProviderChoice`); see
 *   `docs/settings-architecture.md`.
 * - [ExternalConnectionType] is the configured transport for an external receiver.
 */
enum class LocationSourceType {
    INTERNAL,    // Device internal GPS/GNSS
    EXTERNAL,    // External GNSS receiver
    SIMULATOR    // Simulated/replay data for testing
}

enum class ExternalConnectionType {
    BT,          // Bluetooth connection
    TCP,         // TCP/IP network connection
    USB,         // USB serial connection
    RADIO,       // Radio (UHF/VHF) connection
    WIFI         // WiFi direct connection
}
