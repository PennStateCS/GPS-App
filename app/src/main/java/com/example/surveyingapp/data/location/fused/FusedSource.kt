package com.example.surveyingapp.data.location.fused

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import com.example.surveyingapp.data.location.Fix
import com.example.surveyingapp.data.location.Provider
import com.example.surveyingapp.data.location.TimestampSource
import java.time.Instant

/**
 * Provides device (fused) location at ~1 Hz.
 * This source represents INTERNAL (non‑external GNSS) data and feeds simplified Fix objects.
 */
class FusedSource(private val context: Context) {
    // Fused location provider client (Google Play Services)
    private val client = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Stream of Fix objects built from fused provider updates.
     * Caller MUST ensure location permissions (coarse/fine + background when required).
     */
    @SuppressLint("MissingPermission") // Permissions enforced by caller before subscribing
    fun fixes(): Flow<Fix> = callbackFlow {
        // Request config: 1s desired interval, allow slight earlier updates (900 ms minimum)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(900L)
            .setWaitForAccurateLocation(false) // don't delay first fix waiting for extra precision
            .setMaxUpdates(Int.MAX_VALUE)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    // Altitude from fused provider is typically WGS84 ellipsoidal (may be noisy)
                    val hAcc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
                    val vAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null
                    trySend(
                        Fix(
                            lat = loc.latitude,
                            lon = loc.longitude,
                            altEllipsoidalM = loc.altitude,
                            hAccM = hAcc,                 // primary horizontal accuracy
                            vAccM = vAcc,                 // vertical accuracy if available
                            accuracyM = hAcc,             // legacy single-accuracy mirror
                            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                            satsUsed = null,              // not exposed by fused provider
                            satsVisible = null,
                            hdop = null,                  // no DOP metrics from fused
                            pdop = null,
                            rtkStatus = null,             // internal source not RTK-tagged
                            timestamp = Instant.ofEpochMilli(loc.time), // device time (UTC epoch ms)
                            timestampSource = TimestampSource.DEVICE,
                            diffAge = null,
                            baseStationId = null,
                            baselineLengthM = null,
                            correctionSource = null,
                            geoidSeparationM = null,      // not derivable here
                            provider = Provider.INTERNAL,
                            crsEpsg = 4326
                        )
                    )
                }
            }
        }
        // Register updates; when the flow collector is cancelled we remove them in awaitClose
        client.requestLocationUpdates(request, callback, context.mainLooper)
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
