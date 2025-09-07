package com.example.surveyingapp.data.location.fused

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.google.android.gms.location.*
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
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(900L)
            .setWaitForAccurateLocation(false)
            .setGranularity(Granularity.GRANULARITY_FINE) // prefer fine-grained (GNSS-backed) updates
            // .setMinUpdateDistanceMeters(0f)           // optional: reduce jitter
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    val hasAlt = loc.hasAltitude()
                    val alt = if (hasAlt) loc.altitude else null

                    val hAcc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
                    val vAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy())
                        loc.verticalAccuracyMeters.toDouble() else null

                    // New: speed/bearing accuracy (API 26+)
                    val speedAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasSpeedAccuracy())
                        loc.speedAccuracyMetersPerSecond.toDouble() else null
                    val bearingAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasBearingAccuracy())
                        loc.bearingAccuracyDegrees.toDouble() else null

                    trySend(
                        Fix(
                            lat = loc.latitude,
                            lon = loc.longitude,
                            altEllipsoidalM = alt,               // Android fused altitude is ellipsoidal (WGS-84)
                            hAccM = hAcc,
                            vAccM = vAcc,
                            accuracyM = hAcc,                    // deprecated; kept for back-compat (won’t serialize)
                            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                            speedAccMps = speedAcc,
                            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                            bearingAccDeg = bearingAcc,
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
