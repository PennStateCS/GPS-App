package com.example.surveyingapp.settings.model

import com.example.surveyingapp.domain.model.ExternalConnectionType

/**
 * Typed aggregate of the external GNSS receiver settings.
 *
 * These fields are still persisted as separate Preferences keys (receiver profile, connection type,
 * TCP host, TCP port, device name) for backward compatibility — this model is just a single,
 * sanitized, typed view assembled by the repository. App code should prefer this model over reading
 * the individual flows. Stable enum `prefKey`s remain the persistence contract.
 *
 * Only currently-persisted values are included (no speculative `autoReconnect`/`lastKnownDeviceName`).
 *
 * @property tcpHost   Empty string when no host is configured yet.
 * @property tcpPort   Always a valid port (sanitized; defaults via SettingsDefaults when unset).
 * @property displayName User-entered receiver name; empty string when unset.
 */
data class ExternalReceiverSettings(
    val profile: ExternalReceiverProfile,
    val connectionType: ExternalConnectionType,
    val tcpHost: String,
    val tcpPort: Int,
    val displayName: String,
)
