package com.example.surveyingapp.data.location

/** Represents a unified location/gnss fix coming from either internal fused or external RS2+. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val altEllipsoidalM: Double?,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val satsUsed: Int?,
    val hdop: Double?,
    val rtkStatus: RtkStatus?,
    val timestamp: Long,
    val provider: String
)

enum class RtkStatus { FIX, FLOAT, DGPS, SINGLE, INVALID }

sealed class LocationStatus {
    object Idle: LocationStatus()
    data class Connecting(val attempt:Int): LocationStatus()
    object Streaming: LocationStatus()
    data class Error(val message:String): LocationStatus()
}

