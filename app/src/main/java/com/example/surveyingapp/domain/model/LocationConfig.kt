package com.example.surveyingapp.domain.model

/**
 * Comprehensive configuration for location source and external connection settings.
 * Enhanced for professional surveying applications.
 */
data class LocationConfig(
    val sourceType: LocationSourceType,
    val connectionType: ExternalConnectionType,

    // Bluetooth connection settings
    val btAddress: String? = null,
    val btDeviceName: String? = null,

    // TCP connection settings
    val tcpHost: String? = null,
    val tcpPort: Int? = null,
    val tcpDeviceName: String? = null,

    // Serial connection settings
    val serialPort: String? = null,
    val serialBaudRate: Int? = null,

    // Connection behavior
    val autoReconnect: Boolean = true,
    val connectionTimeoutMs: Long = 10000L,
    val reconnectDelayMs: Long = 5000L,
    val maxReconnectAttempts: Int = 5,

    // Data validation
    val validateChecksum: Boolean = true,
    val ignoreOldData: Boolean = true,
    val maxDataAgeMs: Long = 30000L,

    // Advanced settings
    val preferredConstellations: Set<String> = setOf("GPS", "GLONASS", "Galileo", "BeiDou"),
    val minSatellites: Int = 4,
    val minHdop: Double? = null,
    val requireRtkFix: Boolean = false
)
