package com.example.surveyingapp.domain.repository

import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSettings
import com.example.surveyingapp.gnss.settings.GnssCaptureSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val locationSource: Flow<LocationSourceType>
    val externalConnType: Flow<ExternalConnectionType>
    val externalBtAddress: Flow<String?>
    val externalTcpHost: Flow<String?>
    val externalTcpPort: Flow<Int?>
    val externalTcpName: Flow<String?>
    val locationSettings: Flow<LocationSettings>

    // NMEA logging settings
    val nmeaLoggingEnabled: Flow<Boolean>
    val nmeaLogMaxFileSizeMB: Flow<Int>

    suspend fun setLocationSource(v: LocationSourceType)
    suspend fun setExternalConnType(v: ExternalConnectionType)
    suspend fun setExternalTcp(host: String, port: Int, name: String = "")
    suspend fun clearExternalTcp()

    // NMEA logging methods
    suspend fun setNmeaLoggingEnabled(enabled: Boolean)
    suspend fun setNmeaLogMaxFileSizeMB(sizeMB: Int)

    // GNSS capture averaging policy
    val gnssCaptureSettings: Flow<GnssCaptureSettings>
    suspend fun setGnssCaptureSettings(settings: GnssCaptureSettings)
}
