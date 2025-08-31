package com.example.surveyingapp.domain.model

import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.ExternalConnectionType

/**
 * Represents the configuration for location source and external connection.
 */
data class LocationConfig(
    val sourceType: LocationSourceType,
    val connectionType: ExternalConnectionType,
    val btAddress: String?,
    val tcpHost: String?,
    val tcpPort: Int?
)

