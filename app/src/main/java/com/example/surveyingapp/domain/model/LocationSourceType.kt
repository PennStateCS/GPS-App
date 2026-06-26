package com.example.surveyingapp.domain.model

/**
 * Persisted selection enums for the location/GNSS source.
 *
 * Both enums are stored in DataStore (via `SettingsLocalDataSource`) using their stable [prefKey]
 * token — **not** the constant name — so renaming a constant can never break saved settings.
 * [fromPrefKey] also accepts the legacy uppercase constant names ("INTERNAL", "TCP", …) that older
 * installs persisted, so existing users are never stranded on the default.
 *
 * - [LocationSourceType] is the user's *selected* source (what Settings persists). The live
 *   *active provider* is a separate runtime concept owned by
 *   [com.example.surveyingapp.gnss.source.SourceSettings] (`ProviderChoice`); see
 *   `docs/settings-architecture.md`.
 * - [ExternalConnectionType] is the configured transport for an external receiver.
 */
enum class LocationSourceType(val prefKey: String) {
    INTERNAL("internal"),    // Device internal GPS/GNSS
    EXTERNAL("external"),    // External GNSS receiver
    SIMULATOR("simulator");  // Simulated/replay data for testing

    companion object {
        val DEFAULT = INTERNAL

        /** Resolves a persisted token (new prefKey or legacy enum name) to a value; unknown → [DEFAULT]. */
        fun fromPrefKey(value: String?): LocationSourceType =
            entries.firstOrNull { it.prefKey.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: DEFAULT
    }
}

/**
 * Configured transport for an external GNSS receiver.
 *
 * **Only [TCP] has a working connection implementation** (`TcpNmeaSource`); it is the path used by
 * every Reach profile (RS2+/RS4/RS4 Pro). [BT], [USB], [RADIO] and [WIFI] are reserved for future
 * transports — no source adapter exists for them yet and the UI never offers them as active
 * options. They are kept as enum constants so older saved values (e.g. a legacy "bt") still parse
 * and so future migrations have stable tokens to target. Hence [DEFAULT] is [TCP].
 */
enum class ExternalConnectionType(val prefKey: String) {
    BT("bt"),          // Bluetooth connection — reserved, not implemented
    TCP("tcp"),        // TCP/IP network connection — the only implemented transport
    USB("usb"),        // USB serial connection — reserved, not implemented
    RADIO("radio"),    // Radio (UHF/VHF) connection — reserved, not implemented
    WIFI("wifi");      // WiFi direct connection — reserved, not implemented

    companion object {
        // TCP is the only implemented external transport, so it is the default for fresh installs
        // and the fallback for unknown/missing stored values. Legacy "bt" still resolves to BT.
        val DEFAULT = TCP

        /** Resolves a persisted token (new prefKey or legacy enum name) to a value; unknown → [DEFAULT]. */
        fun fromPrefKey(value: String?): ExternalConnectionType =
            entries.firstOrNull { it.prefKey.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: DEFAULT
    }
}
