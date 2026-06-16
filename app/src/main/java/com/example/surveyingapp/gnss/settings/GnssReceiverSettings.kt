package com.example.surveyingapp.gnss.settings

data class GnssReceiverSettings(
    val highAccuracy: Boolean = true,
    val autoReconnect: Boolean = true,
    val validateNmeaChecksum: Boolean = true,
    val connectionTimeoutSeconds: Int = 10,
    val maxReconnectAttempts: Int = 5
)
