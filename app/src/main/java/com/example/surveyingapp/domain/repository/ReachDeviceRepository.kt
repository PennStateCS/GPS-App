package com.example.surveyingapp.domain.repository

import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.reach.ReachBatteryService
import com.example.surveyingapp.gnss.reach.ReachDeviceService
import com.example.surveyingapp.gnss.reach.ReachHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class ReachBatteryInfo(
    val percent: Int,
    val voltageV: Double?,
    val currentA: Double?,
    val temperatureC: Double?,
    val chargerStatus: String?,
    val otg: Boolean? = null,              // USB OTG power status
    val chargingTime: Long? = null,        // Estimated charging time remaining
    val batteryHealth: String? = null      // Battery health status
)

data class ReachDeviceInfo(
    val ip: String?,
    val name: String?,
    val model: String?,
    val firmware: String?,
    val serial: String?,
    val uptimeSec: Long?,
    val uptime: String? = null,            // Formatted uptime string
    val temperature: Double? = null,       // Device temperature
    val storage: StorageInfo? = null,      // Storage information
    val network: NetworkInfo? = null,      // Network status
    val gnssStatus: GnssStatus? = null     // GNSS receiver status
)

data class StorageInfo(
    val totalMB: Long,
    val usedMB: Long,
    val availableMB: Long
) {
    val usagePercent: Int get() = ((usedMB.toDouble() / totalMB) * 100).toInt()
}

data class NetworkInfo(
    val signalStrength: Int?,              // WiFi signal strength
    val ssid: String?,                     // Connected WiFi network
    val macAddress: String?,               // Device MAC address
    val isConnected: Boolean
)

data class GnssStatus(
    val isReceiving: Boolean,
    val lastFixTime: Long?,
    val satelliteCount: Int?,
    val rtkStatus: String?
)

enum class DeviceConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    ERROR,
    TIMEOUT
}

enum class DeviceCommand {
    REBOOT,
    SHUTDOWN,
    RESTART_GNSS,
    CLEAR_LOGS,
    FACTORY_RESET
}

@Singleton
class ReachDeviceRepository @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _batteryInfo = MutableStateFlow<ReachBatteryInfo?>(null)
    val batteryInfo: StateFlow<ReachBatteryInfo?> = _batteryInfo.asStateFlow()

    private val _deviceInfo = MutableStateFlow<ReachDeviceInfo?>(null)
    val deviceInfo: StateFlow<ReachDeviceInfo?> = _deviceInfo.asStateFlow()

    private val _connectionStatus = MutableStateFlow(DeviceConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<DeviceConnectionStatus> = _connectionStatus.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var isPolling = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3

    init {
        scope.launch {
            SurveyingApp.settingsRepo.locationSource
                .distinctUntilChanged()
                .collectLatest { source ->
                    val shouldPoll = source == LocationSourceType.EXTERNAL
                    if (shouldPoll && !isPolling) {
                        startPolling()
                    } else if (!shouldPoll) {
                        stopPolling()
                    }
                }
        }
    }

    private suspend fun startPolling() {
        isPolling = true
        scope.launch { pollBattery() }
        scope.launch { pollDevice() }
    }

    private fun stopPolling() {
        isPolling = false
        _batteryInfo.value = null
        _deviceInfo.value = null
    }

    private suspend fun pollBattery() {
        while (isPolling) {
            val ip = getReachIp()
            if (ip == null) {
                _batteryInfo.value = null
            } else {
                val client = ReachHttpClient(ip)
                val service = ReachBatteryService(client)
                val battery = runCatching { service.read() }.getOrNull()
                _batteryInfo.value = battery?.let {
                    ReachBatteryInfo(
                        percent = it.percent,
                        voltageV = it.voltageV,
                        currentA = it.currentA,
                        temperatureC = it.temperatureC,
                        chargerStatus = it.chargerStatus
                    )
                }
            }
            delay(15_000) // Poll every 15 seconds
        }
    }

    private suspend fun pollDevice() {
        while (isPolling) {
            val ip = getReachIp()
            if (ip == null) {
                _deviceInfo.value = null
            } else {
                val client = ReachHttpClient(ip)
                val service = ReachDeviceService(client)
                val device = runCatching { service.read() }.getOrNull()
                _deviceInfo.value = ReachDeviceInfo(
                    ip = ip,
                    name = device?.name,
                    model = device?.model,
                    firmware = device?.firmware,
                    serial = device?.serial,
                    uptimeSec = device?.uptimeSec
                )
            }
            delay(30_000) // Poll every 30 seconds
        }
    }

    private suspend fun getReachIp(): String? {
        return runCatching {
            SurveyingApp.settingsRepo.externalTcpHost.first()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // Device discovery and connection management
    suspend fun discoverDevices(): List<ReachDeviceInfo> {
        _isDiscovering.value = true
        return try {
            // Implementation would scan network for Reach devices
            // This is a placeholder for the actual discovery logic
            emptyList()
        } catch (e: Exception) {
            _lastError.value = "Discovery failed: ${e.message}"
            emptyList()
        } finally {
            _isDiscovering.value = false
        }
    }

    suspend fun connectToDevice(ip: String): Boolean {
        _connectionStatus.value = DeviceConnectionStatus.CONNECTING
        return try {
            val client = ReachHttpClient(ip)
            val service = ReachDeviceService(client)
            val device = service.read()

            _connectionStatus.value = DeviceConnectionStatus.CONNECTED
            _lastError.value = null
            reconnectAttempts = 0
            true
        } catch (e: Exception) {
            _connectionStatus.value = DeviceConnectionStatus.ERROR
            _lastError.value = "Connection failed: ${e.message}"
            false
        }
    }

    suspend fun disconnectDevice() {
        stopPolling()
        _connectionStatus.value = DeviceConnectionStatus.DISCONNECTED
        _batteryInfo.value = null
        _deviceInfo.value = null
    }

    // Device control commands
    suspend fun sendCommand(command: DeviceCommand): Boolean {
        val ip = getReachIp() ?: return false
        return try {
            val client = ReachHttpClient(ip)
            when (command) {
                DeviceCommand.REBOOT -> {
                    // Implementation for device reboot
                    true
                }
                DeviceCommand.SHUTDOWN -> {
                    // Implementation for device shutdown
                    true
                }
                DeviceCommand.RESTART_GNSS -> {
                    // Implementation for GNSS restart
                    true
                }
                DeviceCommand.CLEAR_LOGS -> {
                    // Implementation for clearing device logs
                    true
                }
                DeviceCommand.FACTORY_RESET -> {
                    // Implementation for factory reset
                    true
                }
            }
        } catch (e: Exception) {
            _lastError.value = "Command failed: ${e.message}"
            false
        }
    }

    // Device health monitoring
    fun isDeviceHealthy(): Boolean {
        val battery = _batteryInfo.value
        val device = _deviceInfo.value

        return when {
            battery == null || device == null -> false
            battery.percent < 10 -> false
            battery.temperatureC?.let { it > 60 } == true -> false
            device.storage?.usagePercent?.let { it > 90 } == true -> false
            else -> true
        }
    }

    fun getBatteryStatus(): String {
        val battery = _batteryInfo.value ?: return "Unknown"
        return when {
            battery.percent >= 80 -> "Excellent"
            battery.percent >= 50 -> "Good"
            battery.percent >= 20 -> "Low"
            else -> "Critical"
        }
    }

    // Storage management
    suspend fun getStorageInfo(): StorageInfo? {
        val ip = getReachIp() ?: return null
        return try {
            val client = ReachHttpClient(ip)
            // Implementation would fetch storage information
            null // Placeholder
        } catch (e: Exception) {
            _lastError.value = "Failed to get storage info: ${e.message}"
            null
        }
    }

    suspend fun clearDeviceLogs(): Boolean {
        return sendCommand(DeviceCommand.CLEAR_LOGS)
    }

    // Network diagnostics
    suspend fun pingDevice(): Boolean {
        val ip = getReachIp() ?: return false
        return try {
            val client = ReachHttpClient(ip)
            // Simple ping test
            client.toString() // Placeholder for actual ping
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getNetworkInfo(): NetworkInfo? {
        val ip = getReachIp() ?: return null
        return try {
            val client = ReachHttpClient(ip)
            // Implementation would fetch network information
            null // Placeholder
        } catch (e: Exception) {
            _lastError.value = "Failed to get network info: ${e.message}"
            null
        }
    }

    // Auto-reconnection logic
    private suspend fun attemptReconnection() {
        if (reconnectAttempts >= maxReconnectAttempts) return

        reconnectAttempts++
        _connectionStatus.value = DeviceConnectionStatus.CONNECTING

        val ip = getReachIp()
        if (ip != null && connectToDevice(ip)) {
            reconnectAttempts = 0
        } else {
            delay(5000) // Wait 5 seconds before next attempt
            if (reconnectAttempts < maxReconnectAttempts) {
                attemptReconnection()
            } else {
                _connectionStatus.value = DeviceConnectionStatus.ERROR
                _lastError.value = "Failed to reconnect after $maxReconnectAttempts attempts"
            }
        }
    }

    // Utility methods
    fun formatUptime(uptimeSec: Long?): String {
        if (uptimeSec == null) return "Unknown"

        val days = uptimeSec / 86400
        val hours = (uptimeSec % 86400) / 3600
        val minutes = (uptimeSec % 3600) / 60

        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    fun clearError() {
        _lastError.value = null
    }
}
