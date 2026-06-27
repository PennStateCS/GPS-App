package app.surrealar.domain.model

/**
 * Data class representing information about an Emlid device (like Reach RS2+)
 */
data class EmlidDeviceInfo(
    val deviceName: String,
    val ipAddress: String,
    val model: String? = null,
    val firmwareVersion: String? = null,
    val serialNumber: String? = null,
    val uptime: String? = null,
    val temperature: Double? = null,
    val batteryLevel: Double? = null,
    val selfTests: List<SelfTest> = emptyList()
)
