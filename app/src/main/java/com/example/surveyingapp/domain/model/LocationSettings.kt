package com.example.surveyingapp.domain.model

/** Domain-level location source selection and connection settings. */
enum class LocationSourceType { INTERNAL, EXTERNAL }
enum class ExternalConnectionType { BT, TCP }

data class LocationSettings(
    val source: LocationSourceType = LocationSourceType.INTERNAL,
    val connectionType: ExternalConnectionType = ExternalConnectionType.BT,
    val btDeviceAddress: String? = null,
    val tcpHost: String? = null,
    val tcpPort: Int? = null
)
