package com.example.surveyingapp.domain.repository

import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSettings
import com.example.surveyingapp.gnss.settings.ArDisplaySettings
import com.example.surveyingapp.gnss.settings.CoordinateDisplaySettings
import com.example.surveyingapp.gnss.settings.DiagnosticsSettings
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

    // NMEA logging settings (kept for backward compat; also exposed via diagnosticsSettings)
    val nmeaLoggingEnabled: Flow<Boolean>
    val nmeaLogMaxFileSizeMB: Flow<Int>

    suspend fun setLocationSource(v: LocationSourceType)
    suspend fun setExternalConnType(v: ExternalConnectionType)
    suspend fun setExternalTcp(host: String, port: Int, name: String = "")
    suspend fun clearExternalTcp()

    // NMEA logging methods (kept for backward compat)
    suspend fun setNmeaLoggingEnabled(enabled: Boolean)
    suspend fun setNmeaLogMaxFileSizeMB(sizeMB: Int)

    // GNSS capture averaging policy
    val gnssCaptureSettings: Flow<GnssCaptureSettings>
    suspend fun setGnssCaptureSettings(settings: GnssCaptureSettings)

    // AR Display
    val arDisplaySettings: Flow<ArDisplaySettings>
    suspend fun setArDisplaySettings(settings: ArDisplaySettings)

    // Coordinate display & units
    val coordinateDisplaySettings: Flow<CoordinateDisplaySettings>
    suspend fun setCoordinateDisplaySettings(settings: CoordinateDisplaySettings)

    // Diagnostics & logging
    val diagnosticsSettings: Flow<DiagnosticsSettings>
    suspend fun setDiagnosticsSettings(settings: DiagnosticsSettings)
}
