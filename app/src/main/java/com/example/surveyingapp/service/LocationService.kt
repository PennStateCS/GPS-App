package com.example.surveyingapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.surveyingapp.R
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.data.location.nmea.NmeaSource
import com.example.surveyingapp.domain.model.Fix
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.LocationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Foreground service that:
 *  - Keeps location alive while app is backgrounded.
 *  - Shows a robust, flicker-free notification with source and RTK state.
 */
class LocationService : Service() {

    companion object {
        private const val CHANNEL_ID = "surveying_location"
        private const val CHANNEL_NAME = "Surveying Location"
        private const val NOTIF_ID = 41

        private const val ACTION_START = "com.example.surveyingapp.action.START_LOCATION"
        private const val ACTION_STOP  = "com.example.surveyingapp.action.STOP_LOCATION"

        @Volatile var isRunning: Boolean = false
            private set

        /** Starts the foreground service immediately. */
        fun start(context: Context) {
            val intent = Intent(context, LocationService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Requests the service to stop. */
        fun stop(context: Context) {
            val intent = Intent(context, LocationService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    // Use the app-level singletons you already have wired.
    private val locationManager by lazy { SurveyingApp.locationManager }
    private val settingsRepo   by lazy { SurveyingApp.settingsRepo }

    private val serviceJob: Job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)

    private var lastFix: Fix? = null
    private var lastStatus: LocationStatus = LocationStatus.Idle
    private var lastActiveSource: LocationSourceType? = null
    private var lastExternalConn: NmeaSource.ConnectionType? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("Location: Starting…", "Initializing"))
        bindFlows()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Already started in onCreate; nothing else needed.
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
    }

    // --- Flow bindings ---

    private fun bindFlows() {
        // 1) Service/engine status
        locationManager.statusFlow
            .onEach { st ->
                lastStatus = st
                updateNotification()
            }
            .launchIn(scope)

        // 2) Selected source (from settings repo)
        settingsRepo.locationSource
            .distinctUntilChanged()
            .onEach { src ->
                lastActiveSource = src
                updateNotification()
            }
            .launchIn(scope)

        // 3) External connection type (BT/TCP)
        locationManager.externalConnectionType
            .onEach { conn ->
                lastExternalConn = conn
                if (lastActiveSource == LocationSourceType.EXTERNAL) updateNotification()
            }
            .launchIn(scope)

        // 4) Fixes
        locationManager.fixFlow
            .onEach { fix ->
                lastFix = fix
                updateNotification()
            }
            .launchIn(scope)
    }

    // --- Notification ---

    private fun updateNotification() {
        val status = lastStatus
        val src = lastActiveSource
        val fix = lastFix

        val title = when (status) {
            is LocationStatus.Connecting -> "Location: Connecting (${status.attempt})"
            is LocationStatus.Error      -> "Location: Error"
            LocationStatus.Idle          -> "Location: Idle"
            is LocationStatus.Streaming  -> when (src) {
                LocationSourceType.INTERNAL -> "Location: Internal"
                LocationSourceType.EXTERNAL -> {
                    val tag = when (lastExternalConn) {
                        NmeaSource.ConnectionType.BT  -> "RS2+ (BT)"
                        NmeaSource.ConnectionType.TCP -> "RS2+ (TCP)"
                        null                          -> "RS2+"
                    }
                    "Location: $tag"
                }
                null -> "Location: Streaming"
            }
        }

        val text = when (status) {
            is LocationStatus.Connecting -> "Connecting to selected source…"
            is LocationStatus.Error      -> status.message
            LocationStatus.Idle          -> "Idle"
            is LocationStatus.Streaming  -> {
                if (fix != null) {
                    val state = fix.rtkStatus?.name ?: if (src == LocationSourceType.INTERNAL) "INTERNAL" else "NO FIX"
                    val satsUsedPart = fix.satsUsed?.toString() ?: "--"
                    val satsVisPart  = fix.satsVisible?.let { "/$it" } ?: ""
                    val hAcc = fix.hAccM?.let { "±${fmt(it, 1)} m" } ?: "—"
                    val alt = when {
                        fix.altOrthometricM != null && fix.geoidSeparationM != null ->
                            "${fmt(fix.altOrthometricM!!, 2)} m MSL (ΔN ${fmt(fix.geoidSeparationM!!, 2)})"
                        fix.altEllipsoidalM != null -> "${fmt(fix.altEllipsoidalM!!, 2)} m ellip."
                        else -> "—"
                    }
                    "State: $state • Sats: $satsUsedPart$satsVisPart • H-Acc: $hAcc • Alt: $alt"
                } else {
                    if (src == LocationSourceType.INTERNAL &&
                        (lastExternalConn != null) &&
                        isExternalRequested()
                    ) "Streaming (Fallback: Internal while external connects…)"
                    else "Streaming…"
                }
            }
        }

        val notif = buildNotification(title, text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this,
                0,
                launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location) // ensure this exists in mipmap/drawable
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GNSS/RTK foreground service"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun isExternalRequested(): Boolean {
        // If an external connection type is known but source reports INTERNAL, we’re in fallback
        return lastExternalConn != null && lastActiveSource == LocationSourceType.INTERNAL
    }

    private fun fmt(v: Double, digits: Int): String =
        String.format(java.util.Locale.US, "%.${digits}f", v)
}
