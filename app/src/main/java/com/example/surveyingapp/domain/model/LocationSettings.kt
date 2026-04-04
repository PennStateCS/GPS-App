package com.example.surveyingapp.domain.model

/** Domain-level location source selection and connection settings. */
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

data class LocationSettings(
    val source: LocationSourceType = LocationSourceType.INTERNAL,
    val connectionType: ExternalConnectionType = ExternalConnectionType.BT,

    // Bluetooth settings
    val btDeviceAddress: String? = null,
    val btDeviceName: String? = null,

    // TCP/IP settings
    val tcpHost: String? = null,
    val tcpPort: Int? = null,
    val tcpDeviceName: String? = null,

    // USB/Serial settings
    val usbDevicePath: String? = null,
    val serialBaudRate: Int? = 9600,

    // Radio settings
    val radioFrequency: Double? = null,
    val radioChannel: Int? = null,

    // Connection behavior
    val autoReconnect: Boolean = true,
    val connectionTimeoutSeconds: Int = 10,
    val maxReconnectAttempts: Int = 5,

    // Data quality settings
    val validateNmeaChecksum: Boolean = true,
    val requireMinSatellites: Int = 4,
    val maxHdopThreshold: Double? = null,
    val requireRtkFixed: Boolean = false
)
