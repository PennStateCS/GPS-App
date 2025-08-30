package com.example.surveyingapp.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DATASTORE_NAME = "app_settings"

val Context.appDataStore by preferencesDataStore(name = DATASTORE_NAME)

object SettingsKeys {
    val LOCATION_SOURCE = stringPreferencesKey("location_source") // used
    val EXTERNAL_CONN_TYPE = stringPreferencesKey("external_connection_type") // used
    val EXTERNAL_BT_ADDR = stringPreferencesKey("external_bt_device_address") // used (BT provider)
    // Removed EXTERNAL_BT_NAME (unused)
    val EXTERNAL_TCP_HOST = stringPreferencesKey("external_tcp_host") // used
    val EXTERNAL_TCP_PORT = intPreferencesKey("external_tcp_port") // used
}

enum class LocationSourceType { INTERNAL, EXTERNAL }
enum class ExternalConnectionType { BT, TCP }

data class LocationSettings(
    val source: LocationSourceType = LocationSourceType.INTERNAL,
    val connectionType: ExternalConnectionType = ExternalConnectionType.BT,
    val btDeviceAddress: String? = null,
    // Removed btDeviceName (unused)
    val tcpHost: String? = null,
    val tcpPort: Int? = null
)

class SettingsRepository(private val context: Context) {
    // Individual flows
    val locationSource: Flow<String> = context.appDataStore.data.map { it[SettingsKeys.LOCATION_SOURCE] ?: "internal" }
    val externalConnType: Flow<String> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_CONN_TYPE] ?: "bt" }
    val externalBtAddress: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_BT_ADDR] }
    // Removed externalBtName flow
    val externalTcpHost: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_HOST] }
    val externalTcpPort: Flow<Int?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_PORT] }

    // Aggregated strongly-typed model flow
    val locationSettings: Flow<LocationSettings> = context.appDataStore.data.map { prefs ->
        val src = when (prefs[SettingsKeys.LOCATION_SOURCE]?.lowercase()) {
            "external" -> LocationSourceType.EXTERNAL
            else -> LocationSourceType.INTERNAL
        }
        val conn = when (prefs[SettingsKeys.EXTERNAL_CONN_TYPE]?.lowercase()) {
            "tcp" -> ExternalConnectionType.TCP
            else -> ExternalConnectionType.BT
        }
        LocationSettings(
            source = src,
            connectionType = conn,
            btDeviceAddress = prefs[SettingsKeys.EXTERNAL_BT_ADDR],
            tcpHost = prefs[SettingsKeys.EXTERNAL_TCP_HOST],
            tcpPort = prefs[SettingsKeys.EXTERNAL_TCP_PORT]
        )
    }

    // String-based setters (in active use)
    suspend fun setLocationSource(v: String) { context.appDataStore.edit { it[SettingsKeys.LOCATION_SOURCE] = v } }
    suspend fun setExternalConnType(v: String) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_CONN_TYPE] = v } }
    // Removed setExternalBt (unused)
    suspend fun setExternalTcp(host: String, port: Int) { context.appDataStore.edit { prefs ->
        prefs[SettingsKeys.EXTERNAL_TCP_HOST] = host; prefs[SettingsKeys.EXTERNAL_TCP_PORT] = port }
    }

    // Removed typed setters (unused)

    suspend fun clearExternalTcp() { context.appDataStore.edit { prefs ->
        prefs.remove(SettingsKeys.EXTERNAL_TCP_HOST); prefs.remove(SettingsKeys.EXTERNAL_TCP_PORT)
    } }
}
