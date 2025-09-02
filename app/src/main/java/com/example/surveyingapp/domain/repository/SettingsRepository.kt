package com.example.surveyingapp.domain.repository

import kotlinx.coroutines.flow.Flow
import com.example.surveyingapp.domain.model.LocationSettings
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.ExternalConnectionType

interface SettingsRepository {
    val locationSource: Flow<LocationSourceType>
    val externalConnType: Flow<ExternalConnectionType>
    val externalBtAddress: Flow<String?>
    val externalTcpHost: Flow<String?>
    val externalTcpPort: Flow<Int?>
    val locationSettings: Flow<LocationSettings>

    suspend fun setLocationSource(v: LocationSourceType)
    suspend fun setExternalConnType(v: ExternalConnectionType)
    suspend fun setExternalTcp(host: String, port: Int)
    suspend fun clearExternalTcp()
}
