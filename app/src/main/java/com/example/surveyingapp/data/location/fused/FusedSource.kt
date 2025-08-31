package com.example.surveyingapp.data.location.fused

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.example.surveyingapp.domain.model.Fix
import com.example.surveyingapp.domain.model.Provider
import com.example.surveyingapp.domain.model.TimestampSource
import java.time.Instant

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
                    val hAcc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
                    val vAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null
                    trySend(
                        Fix(
                            lat = loc.latitude,
                            lon = loc.longitude,
                            altEllipsoidalM = loc.altitude,
                            hAccM = hAcc,
                            vAccM = vAcc,
                            accuracyM = hAcc,
                            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                            satsUsed = null,
                            satsVisible = null,
                            hdop = null,
                            pdop = null,
                            rtkStatus = null,
                            timestamp = Instant.ofEpochMilli(loc.time),
                            timestampSource = TimestampSource.DEVICE,
                            diffAge = null,
                            baseStationId = null,
                            baselineLengthM = null,
                            correctionSource = null,
                            geoidSeparationM = null,
                            provider = Provider.INTERNAL,
                            crsEpsg = 4326
                        )
                    )
                }
            }
        }
        client.requestLocationUpdates(request, callback, context.mainLooper)
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
