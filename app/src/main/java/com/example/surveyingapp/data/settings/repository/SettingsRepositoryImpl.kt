package com.example.surveyingapp.data.settings.repository

import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.data.settings.datastore.SettingsLocalDataSource
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationConfig
import com.example.surveyingapp.domain.model.LocationSettings
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

    override suspend fun setExternalTcp(host: String, port: Int) {
        local.setExternalTcp(host, port)
    }

    override suspend fun setExternalTcpName(name: String) {
        local.setExternalTcpName(name)
    }

    override suspend fun clearExternalTcp() { local.clearExternalTcp() }
}

private fun String?.toLocationSourceType(): LocationSourceType = when (this?.lowercase()) {
    "external" -> LocationSourceType.EXTERNAL
    else -> LocationSourceType.INTERNAL
}

private fun String?.toExternalConnectionType(): ExternalConnectionType = when (this?.lowercase()) {
    "tcp" -> ExternalConnectionType.TCP
    else -> ExternalConnectionType.BT
}

private fun LocationSourceType.toPrefString(): String = when (this) {
    LocationSourceType.INTERNAL -> "internal"
    LocationSourceType.EXTERNAL -> "external"
}

private fun ExternalConnectionType.toPrefString(): String = when (this) {
    ExternalConnectionType.BT -> "bt"
    ExternalConnectionType.TCP -> "tcp"
}
