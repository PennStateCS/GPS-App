package com.example.surveyingapp.ui.rs2

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.domain.repository.ReachDeviceRepository
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.app.Application
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class BatteryUi(
    val socPct: Int?, val voltageV: Double?, val tempC: Double?,
    val currentA: Double?, val chargerStatus: String?, val otg: Boolean?
)
data class DeviceUi(
    val ip: String?, val name: String?, val model: String?,
    val firmware: String?, val serial: String?, val uptime: String?
)

@HiltViewModel
class Rs2DeviceViewModel @Inject constructor(
    private val fixSwitchboard: FixSwitchboard,
    private val reachDeviceRepository: ReachDeviceRepository,
    application: Application
) : AndroidViewModel(application) {

    val battery: StateFlow<BatteryUi?> = reachDeviceRepository.batteryInfo
        .map { it?.let { battery ->
            BatteryUi(
                socPct = battery.percent,
                voltageV = battery.voltageV,
                tempC = battery.temperatureC,
                currentA = battery.currentA,
                chargerStatus = battery.chargerStatus,
                otg = null // ReachBatteryService doesn't provide OTG status
            )
        }}
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val device: StateFlow<DeviceUi?> = reachDeviceRepository.deviceInfo
        .map { it?.let { device ->
            DeviceUi(
                ip = device.ip,
                name = device.name,
                model = device.model,
                firmware = device.firmware,
                serial = device.serial,
                uptime = device.uptimeSec?.let { formatUptime(it) }
            )
        }}
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private fun formatUptime(uptimeSec: Long): String {
        val hours = uptimeSec / 3600
        val minutes = (uptimeSec % 3600) / 60
        return "${hours}h ${minutes}m"
    }
}
