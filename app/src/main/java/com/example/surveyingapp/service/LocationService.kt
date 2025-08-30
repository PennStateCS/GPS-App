package com.example.surveyingapp.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.data.location.Fix
import com.example.surveyingapp.data.location.LocationStatus
import com.example.surveyingapp.data.location.Provider
import com.example.surveyingapp.data.location.TimestampSource
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

class LocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastFix: Fix? = null
    @Volatile private var lastConnType: String = "bt" // bt | tcp
    @Volatile private var lastSource: String = "internal" // internal | external

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        LocationNotifications.ensureChannel(this)
        // Start foreground immediately with provisional title
        startForeground(NOTIF_ID, LocationNotifications.build(this, initialTitle(), "Starting"))
        collectSettings()
        collectStreams()
    }

    private fun initialTitle(): String = try {
        // Synchronously read current preference for quick initial title
        val src = runBlocking { SurveyingApp.settingsRepo.locationSource.first() }
        if (src == "external") {
            val ct = runBlocking { SurveyingApp.settingsRepo.externalConnType.first() }
            "Location: RS2+ (${ct.uppercase()})"
        } else "Location: Internal"
    } catch (_: Exception) { "Location: Internal" }

    private fun collectSettings() {
        scope.launch {
            SurveyingApp.settingsRepo.externalConnType.collectLatest { ct ->
                lastConnType = ct
                updateNotification()
            }
        }
        scope.launch {
            SurveyingApp.settingsRepo.locationSource.collectLatest { src ->
                lastSource = src
                updateNotification()
            }
        }
    }

    private fun collectStreams() {
        val mgr = SurveyingApp.locationManager
        scope.launch {
            mgr.fixes.collectLatest { fix ->
                lastFix = fix
                updateNotification()
            }
        }
        scope.launch { mgr.status.collectLatest { _ -> updateNotification() } }
    }

    private fun updateNotification() {
        // Snapshot current status & last fix (avoid multiple volatile reads inside when chains)
        val status = SurveyingApp.locationManager.status.value
        val fix = lastFix
        // Build the notification title: reflects high‑level streaming / connection state
        val baseTitle = when (status) {
            is LocationStatus.Connecting -> "Location: Connecting (${status.attempt})" // show attempt count
            is LocationStatus.Error -> "Location: Error" // error state; details in text body
            LocationStatus.Idle -> "Location: Idle" // no active streams
            is LocationStatus.Streaming -> {
                // Decide which label to show based on provider or last source preference
                val internal = fix?.provider == Provider.INTERNAL || (fix == null && lastSource == "internal")
                if (internal) "Location: Internal" else "Location: RS2+ (${lastConnType.uppercase()})"
            }
        }
        // Build the detail text line (compact, comma‑separated telemetry)
        val text = when (status) {
            is LocationStatus.Connecting -> "Connecting…" // transitional state
            is LocationStatus.Error -> status.message // bubble up message
            LocationStatus.Idle -> "Idle" // nothing active
            is LocationStatus.Streaming -> fix?.let { f ->
                // Solution state (prefer explicit RTK status; otherwise derive from provider)
                val state = f.rtkStatus?.name ?: when (f.provider) {
                    Provider.INTERNAL -> "INTERNAL" // fused / device GNSS
                    Provider.RS2_BT, Provider.RS2_TCP -> "NO FIX" // external but no quality yet
                    else -> "NO FIX"
                }
                // Satellite counts: used / (visible) if we know both
                val satsUsedPart = f.satsUsed?.toString() ?: "--"
                val satsVisPart = f.satsVisible?.let { "/$it" } ?: ""
                val satsPart = "$satsUsedPart$satsVisPart sats"
                // Dilution metrics (optional)
                val hdopPart = f.hdop?.let { "HDOP ${it.format(1)}" }
                val pdopPart = f.pdop?.let { "PDOP ${it.format(1)}" }
                // Accuracy: prefer horizontal/vertical; fall back to legacy single accuracy
                val hAccPart = f.hAccM?.let { "HACC ${it.format(1)}m" } ?: f.accuracyM?.let { "ACC ${it.format(1)}m" }
                val vAccPart = f.vAccM?.let { "VACC ${it.format(1)}m" }
                // Correction age (Duration -> seconds w/ tenths); only if we have external corrections
                val agePart = f.diffAge?.let { d: Duration ->
                    val sec = d.toDouble(DurationUnit.MILLISECONDS) / 1000.0
                    "AGE ${String.format(Locale.US, "%.1fs", sec)}"
                }
                // Heights: show both orthometric (MSL) and ellipsoidal when geoid info available
                val altEllip = f.altEllipsoidalM
                val altMsl = f.altOrthometricM
                val altPart = when {
                    altEllip != null && altMsl != null -> "ALT ${altMsl.format(2)}m MSL / ${altEllip.format(2)}m ellip"
                    altEllip != null -> "ALT ${altEllip.format(2)}m"
                    else -> null
                }
                // Geoid separation (N) if known
                val geoidPart = if (f.geoidSeparationM != null) "N ${f.geoidSeparationM.format(1)}m" else null
                // Timestamp source (helps diagnose stale or fallback time modes)
                val tsSrcPart = f.timestampSource?.let { src ->
                    val tag = when (src) {
                        TimestampSource.NMEA_RMC -> "RMC"
                        TimestampSource.NMEA_GGA -> "GGA"
                        TimestampSource.DEVICE -> "DEV"
                        TimestampSource.SYSTEM -> "SYS"
                    }
                    "TS $tag"
                }
                // Assemble visible, non‑null parts into a concise status line
                listOf(state, satsPart, hdopPart, pdopPart, hAccPart, vAccPart, altPart, geoidPart, agePart, tsSrcPart)
                    .filterNotNull()
                    .joinToString(", ")
            } ?: if (lastSource == "internal") "Waiting for fused fix…" else "Waiting for RS2+ fix…"
        }
        // Post / update the foreground notification
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, LocationNotifications.build(this, baseTitle, text))
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }

    companion object {
        /** Internal notification ID for the persistent foreground service notification. */
        private const val NOTIF_ID = 41

        /** True while the service has been created and not yet destroyed. */
        @Volatile var isRunning: Boolean = false

        /**
         * Start (or elevate) the service to foreground mode.
         * Safe to call repeatedly; Android will route to onCreate only once while running.
         */
        fun start(context: Context) {
            LocationNotifications.ensureChannel(context)
            val intent = Intent(context, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        /** Stop the foreground service if running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }
    }
}

/**
 * Format a Double to the given decimal places (locale US) trimming surrounding whitespace.
 * NOTE: Format string includes a leading space for positive values; we trim() to remove it.
 */
private fun Double.format(dec: Int) = String.format(Locale.US, "% .${'$'}{dec}f", this).trim()
