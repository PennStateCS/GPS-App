package com.example.surveyingapp.gnss.model

/**
 * Represents the current location service status
 */
sealed class LocationStatus {
    object Idle : LocationStatus()
    object Connecting : LocationStatus()
    object Streaming : LocationStatus()
    object Error : LocationStatus()
}
