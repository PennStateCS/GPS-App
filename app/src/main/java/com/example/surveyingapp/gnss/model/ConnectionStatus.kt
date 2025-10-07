package com.example.surveyingapp.gnss.model

/**
 * Represents the connection status for external GNSS devices
 */
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    STREAMING,
    DEGRADED,
    ERROR
}
