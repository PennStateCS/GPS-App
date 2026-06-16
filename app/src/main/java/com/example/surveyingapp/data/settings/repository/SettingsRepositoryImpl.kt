package com.example.surveyingapp.data.settings.repository

import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.data.settings.datastore.SettingsLocalDataSource
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSettings
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.settings.GnssCaptureSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Maps raw DataStore preference primitives to domain enums/models and vice versa.
 */
class SettingsRepositoryImpl(private val local: SettingsLocalDataSource) : SettingsRepository {
    private val sourceFlow = local.locationSourceRaw.map { it.toLocationSourceType() }
    private val connTypeFlow = local.externalConnTypeRaw.map { it.toExternalConnectionType() }

    override val locationSource: Flow<LocationSourceType> = sourceFlow
    override val externalConnType: Flow<ExternalConnectionType> = connTypeFlow
    override val externalBtAddress = local.externalBtAddress
    override val externalTcpHost = local.externalTcpHost
    override val externalTcpPort = local.externalTcpPort
    override val externalTcpName = local.externalTcpName

    // NMEA logging settings
    override val nmeaLoggingEnabled = local.nmeaLoggingEnabled
    override val nmeaLogMaxFileSizeMB = local.nmeaLogMaxFileSizeMB

    override val locationSettings: Flow<LocationSettings>
        get() = combine(
            locationSource,
            externalConnType,
            externalBtAddress,
            externalTcpHost,
            externalTcpPort
        ) { source, connType, btAddr, tcpHost, tcpPort ->
            LocationSettings(
                source = source,
                connectionType = connType,
                btDeviceAddress = btAddr,
                tcpHost = tcpHost,
                tcpPort = tcpPort
            )
        }

    override suspend fun setLocationSource(v: LocationSourceType) {
        local.setLocationSourceString(v.toPrefString())
    }

    override suspend fun setExternalConnType(v: ExternalConnectionType) {
        local.setExternalConnTypeString(v.toPrefString())
    }

    override suspend fun setExternalTcp(host: String, port: Int, name: String) {
        local.setExternalTcp(host, port)
        if (name.isNotBlank()) {
            local.setExternalTcpName(name)
        }
    }

    override suspend fun clearExternalTcp() {
        local.clearExternalTcp()
    }

    // NMEA logging methods
    override suspend fun setNmeaLoggingEnabled(enabled: Boolean) {
        local.setNmeaLoggingEnabled(enabled)
    }

    override suspend fun setNmeaLogMaxFileSizeMB(sizeMB: Int) {
        local.setNmeaLogMaxFileSizeMB(sizeMB)
    }

    // GNSS capture averaging policy
    override val gnssCaptureSettings: Flow<GnssCaptureSettings> = combine(
        local.gnssCaptureRtkStatus,
        local.gnssCaptureMinDurationSec,
        local.gnssCaptureMaxDurationSec,
        local.gnssCaptureMinSamples,
        combine(local.gnssCaptureMaxFixAgeSec, local.gnssCaptureMaxDiffAgeSec) { fix, diff -> fix to diff }
    ) { rtkStr, minDur, maxDur, minSamples, (maxFix, maxDiff) ->
        GnssCaptureSettings(
            requiredMinStatus = rtkStr.toRtkStatus(),
            minDurationSec    = minDur,
            maxDurationSec    = maxDur,
            minSamples        = minSamples,
            maxFixAgeSec      = maxFix,
            maxDiffAgeSec     = maxDiff
        )
    }

    override suspend fun setGnssCaptureSettings(settings: GnssCaptureSettings) {
        local.setGnssCaptureRtkStatus(settings.requiredMinStatus.name)
        local.setGnssCaptureMinDurationSec(settings.minDurationSec)
        local.setGnssCaptureMaxDurationSec(settings.maxDurationSec)
        local.setGnssCaptureMinSamples(settings.minSamples)
        local.setGnssCaptureMaxFixAgeSec(settings.maxFixAgeSec)
        local.setGnssCaptureMaxDiffAgeSec(settings.maxDiffAgeSec)
    }

    private fun String.toRtkStatus(): RtkStatus = when (this) {
        "FIX"    -> RtkStatus.FIX
        "FLOAT"  -> RtkStatus.FLOAT
        "DGPS"   -> RtkStatus.DGPS
        "SINGLE" -> RtkStatus.SINGLE
        else     -> RtkStatus.FIX
    }

    private fun String?.toLocationSourceType(): LocationSourceType = when (this) {
        "INTERNAL" -> LocationSourceType.INTERNAL
        "EXTERNAL" -> LocationSourceType.EXTERNAL
        "SIMULATOR" -> LocationSourceType.SIMULATOR
        else -> LocationSourceType.INTERNAL
    }

    private fun LocationSourceType.toPrefString(): String = when (this) {
        LocationSourceType.INTERNAL -> "INTERNAL"
        LocationSourceType.EXTERNAL -> "EXTERNAL"
        LocationSourceType.SIMULATOR -> "SIMULATOR"
    }

    private fun String?.toExternalConnectionType(): ExternalConnectionType = when (this) {
        "BT" -> ExternalConnectionType.BT
        "TCP" -> ExternalConnectionType.TCP
        "USB" -> ExternalConnectionType.USB
        "RADIO" -> ExternalConnectionType.RADIO
        "WIFI" -> ExternalConnectionType.WIFI
        else -> ExternalConnectionType.BT
    }

    private fun ExternalConnectionType.toPrefString(): String = when (this) {
        ExternalConnectionType.BT -> "BT"
        ExternalConnectionType.TCP -> "TCP"
        ExternalConnectionType.USB -> "USB"
        ExternalConnectionType.RADIO -> "RADIO"
        ExternalConnectionType.WIFI -> "WIFI"
    }
}
