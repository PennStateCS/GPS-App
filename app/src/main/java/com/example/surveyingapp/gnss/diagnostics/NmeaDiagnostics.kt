package com.example.surveyingapp.gnss.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Diagnostic utility to test if the device provides NMEA data.
 * Use this to verify if InternalNmeaSource can work on this device.
 */
class NmeaDiagnostics(private val context: Context) {

    private val _diagnosticResult = MutableStateFlow<DiagnosticResult>(DiagnosticResult.NotStarted)
    val diagnosticResult: StateFlow<DiagnosticResult> = _diagnosticResult.asStateFlow()

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var nmeaListener: OnNmeaMessageListener? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null

    sealed class DiagnosticResult {
        object NotStarted : DiagnosticResult()
        object Testing : DiagnosticResult()
        data class Success(
            val nmeaMessagesReceived: Int,
            val satellitesVisible: Int,
            val firstNmeaSample: String?
        ) : DiagnosticResult()
        data class PartialSuccess(
            val satellitesVisible: Int,
            val nmeaMessagesReceived: Int,
            val message: String
        ) : DiagnosticResult()
        data class Failure(val reason: String) : DiagnosticResult()
    }

    suspend fun runDiagnostic(durationMs: Long = 30_000): DiagnosticResult {
        Log.d(TAG, "Starting NMEA diagnostic test for ${durationMs}ms")
        _diagnosticResult.value = DiagnosticResult.Testing

        // Check permissions
        if (!hasLocationPermission()) {
            val result = DiagnosticResult.Failure("Location permission not granted")
            _diagnosticResult.value = result
            return result
        }

        // Check if GPS is enabled
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            val result = DiagnosticResult.Failure("GPS is disabled in device settings")
            _diagnosticResult.value = result
            return result
        }

        var nmeaCount = 0
        var firstNmea: String? = null
        var satelliteCount = 0

        // Set up NMEA listener
        val nmea = OnNmeaMessageListener { message, _ ->
            nmeaCount++
            if (firstNmea == null && message.isNotEmpty()) {
                firstNmea = message.trim()
                Log.d(TAG, "First NMEA received: $firstNmea")
            }
            if (nmeaCount <= 5) {
                Log.d(TAG, "NMEA #$nmeaCount: $message")
            }
        }
        nmeaListener = nmea

        // Set up GNSS status callback
        val gnssCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    satelliteCount = status.satelliteCount
                    Log.d(TAG, "Satellites visible: $satelliteCount")
                }
            }
        } else null
        gnssStatusCallback = gnssCallback

        try {
            // Register listeners
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                @Suppress("DEPRECATION")
                locationManager.addNmeaListener(nmea)
                Log.d(TAG, "NMEA listener registered")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null) {
                    locationManager.registerGnssStatusCallback(gnssCallback)
                    Log.d(TAG, "GNSS status callback registered")
                }
            }

            // Wait for data
            Log.d(TAG, "Waiting ${durationMs}ms for NMEA data...")
            delay(durationMs)

            // Evaluate results
            val result = when {
                nmeaCount > 0 && satelliteCount > 0 -> {
                    DiagnosticResult.Success(nmeaCount, satelliteCount, firstNmea)
                }
                satelliteCount > 0 && nmeaCount == 0 -> {
                    DiagnosticResult.PartialSuccess(
                        satelliteCount, 0,
                        "GPS sees satellites but no NMEA data received. Device may not expose NMEA."
                    )
                }
                nmeaCount > 0 && satelliteCount == 0 -> {
                    DiagnosticResult.PartialSuccess(
                        0, nmeaCount,
                        "NMEA data received but no satellite count. Check GPS signal."
                    )
                }
                else -> {
                    DiagnosticResult.Failure(
                        "No NMEA data and no satellites detected after ${durationMs}ms. " +
                        "Ensure clear sky view and wait longer."
                    )
                }
            }

            Log.d(TAG, "Diagnostic complete: $result")
            _diagnosticResult.value = result
            return result

        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        nmeaListener?.let {
            runCatching { locationManager.removeNmeaListener(it) }
        }
        gnssStatusCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { locationManager.unregisterGnssStatusCallback(it) }
            }
        }
        nmeaListener = null
        gnssStatusCallback = null
        Log.d(TAG, "Diagnostic cleanup complete")
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "NmeaDiagnostics"
    }
}

