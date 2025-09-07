package com.example.surveyingapp.domain.model

data class EmlidDeviceInfo(
    val deviceName: String,
    val ipAddress: String,
    val selfTests: List<SelfTest>,
    val firmwareVersion: String? = null,
    val serialNumber: String? = null,
    val model: String? = null,
    val uptime: String? = null,
    val temperature: Double? = null,
    val batteryLevel: Double? = null
)

data class SelfTest(
    val name: String,
    val status: TestStatus,
    val description: String? = null
)

enum class TestStatus {
    PASSED,
    FAILED,
    WARNING,
    UNKNOWN
}
