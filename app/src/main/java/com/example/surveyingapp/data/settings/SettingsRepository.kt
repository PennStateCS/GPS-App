package com.example.surveyingapp.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DATASTORE_NAME = "app_settings"

val Context.appDataStore by preferencesDataStore(name = DATASTORE_NAME)

object SettingsKeys {
    val LOCATION_SOURCE = stringPreferencesKey("location_source") // internal | external
    val EXTERNAL_CONN_TYPE = stringPreferencesKey("external_connection_type") // bt | tcp
    val EXTERNAL_BT_ADDR = stringPreferencesKey("external_bt_device_address")
    val EXTERNAL_BT_NAME = stringPreferencesKey("external_bt_device_name")
    val EXTERNAL_TCP_HOST = stringPreferencesKey("external_tcp_host")
    val EXTERNAL_TCP_PORT = intPreferencesKey("external_tcp_port")
}

class SettingsRepository(private val context: Context) {
    val locationSource: Flow<String> = context.appDataStore.data.map { it[SettingsKeys.LOCATION_SOURCE] ?: "internal" }
    val externalConnType: Flow<String> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_CONN_TYPE] ?: "bt" }
    val externalBtAddress: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_BT_ADDR] }
    val externalBtName: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_BT_NAME] }
    val externalTcpHost: Flow<String?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_HOST] }
    val externalTcpPort: Flow<Int?> = context.appDataStore.data.map { it[SettingsKeys.EXTERNAL_TCP_PORT] }

    suspend fun setLocationSource(v: String) { context.appDataStore.edit { it[SettingsKeys.LOCATION_SOURCE] = v } }
    suspend fun setExternalConnType(v: String) { context.appDataStore.edit { it[SettingsKeys.EXTERNAL_CONN_TYPE] = v } }
    suspend fun setExternalBt(addr: String, name: String) { context.appDataStore.edit { prefs ->
        prefs[SettingsKeys.EXTERNAL_BT_ADDR] = addr; prefs[SettingsKeys.EXTERNAL_BT_NAME] = name }
    }
    suspend fun setExternalTcp(host: String, port: Int) { context.appDataStore.edit { prefs ->
        prefs[SettingsKeys.EXTERNAL_TCP_HOST] = host; prefs[SettingsKeys.EXTERNAL_TCP_PORT] = port }
    }
}
