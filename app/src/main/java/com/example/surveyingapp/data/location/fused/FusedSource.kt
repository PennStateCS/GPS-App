package com.example.surveyingapp.data.location.fused

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import com.example.surveyingapp.data.location.Fix
import com.example.surveyingapp.data.location.RtkStatus

/** Provides internal fused location fixes at ~1s interval. */
class FusedSource(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    fun fixes(): Flow<Fix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(900L)
            .setWaitForAccurateLocation(false)
            .setMaxUpdates(Int.MAX_VALUE)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    trySend(
                        Fix(
                            lat = loc.latitude,
                            lon = loc.longitude,
                            altEllipsoidalM = loc.altitude,
                            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                            satsUsed = null,
                            hdop = null,
                            rtkStatus = null,
                            timestamp = loc.time,
                            provider = "fused"
                        )
                    )
                }
            }
        }
        client.requestLocationUpdates(request, callback, context.mainLooper)
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
