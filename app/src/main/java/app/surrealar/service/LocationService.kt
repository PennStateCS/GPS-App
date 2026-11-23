package app.surrealar.service

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
import app.surrealar.R

/**
 * Minimal foreground service that keeps location permissions active
 * while the app is backgrounded for surveying sessions.
 *
 * All GNSS data processing is now handled by the GNSS bus system.
 */
class LocationService : Service() {

    companion object {
        private const val CHANNEL_ID = "surveying_location"
        private const val CHANNEL_NAME = "Surveying Location"
        private const val NOTIF_ID = 41

        private const val ACTION_START = "app.surrealar.action.START_LOCATION"
        private const val ACTION_STOP  = "app.surrealar.action.STOP_LOCATION"

        @Volatile var isRunning: Boolean = false
            private set

        /** Whether the location permission a location foreground service needs is granted. */
        fun canStart(context: Context): Boolean =
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        /**
         * Starts the foreground service. Skipped without location permission (Android 14 throws a
         * SecurityException for a location service without it); a start the system refuses (e.g. from
         * the background on Android 12+) is logged instead of crashing. Returns true if requested.
         */
        fun start(context: Context): Boolean {
            if (!canStart(context)) {
                app.surrealar.util.DiagnosticsLogger.w(app.surrealar.util.diagnostics.DiagTags.APP,
                    "Location service start skipped: location permission not granted")
                return false
            }
            val intent = Intent(context, LocationService::class.java).setAction(ACTION_START)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
                true
            }.getOrElse {
                app.surrealar.util.DiagnosticsLogger.w(app.surrealar.util.diagnostics.DiagTags.APP,
                    "Location service start failed: refused by system error=${it.javaClass.simpleName} reason=\"${it.message ?: "unknown"}\"")
                false
            }
        }

        /**
         * Stops the service ([reason] is logged). stopService, not an ACTION_STOP start: Android refuses
         * startService from the background, and this runs while the app is closing. No-op when stopped.
         */
        fun stop(context: Context, reason: String) {
            if (!isRunning) return
            app.surrealar.util.DiagnosticsLogger.i(app.surrealar.util.diagnostics.DiagTags.APP,
                "Location service stop requested reason=\"$reason\"")
            runCatching { context.stopService(Intent(context, LocationService::class.java)) }
                .onFailure {
                    app.surrealar.util.DiagnosticsLogger.w(app.surrealar.util.diagnostics.DiagTags.APP,
                        "Location service stop failed", it)
                }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannel()
        val notificationsAllowed = runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        }.getOrDefault(false)
        app.surrealar.util.DiagnosticsLogger.i(app.surrealar.util.diagnostics.DiagTags.APP,
            "Location service started notificationsAllowed=$notificationsAllowed")
        try {
            androidx.core.app.ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (e: Exception) {
            // SecurityException (permission revoked) or ForegroundServiceStartNotAllowedException.
            app.surrealar.util.DiagnosticsLogger.w(app.surrealar.util.diagnostics.DiagTags.APP,
                "Location service foreground start failed: service stopped error=${e.javaClass.simpleName} reason=\"${e.message ?: "unknown"}\"")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Already started in onCreate; nothing else needed.
            }
            ACTION_STOP -> {
                // The notification's Stop action.
                app.surrealar.util.DiagnosticsLogger.i(app.surrealar.util.diagnostics.DiagTags.APP,
                    "Location service stop requested reason=\"notification\"")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // Not sticky: Android 14 refuses a location service restarted from the background, and the app
        // starts it again when it is opened.
        return START_NOT_STICKY
    }

    /** The user swiped the app away in Recents: that is leaving the app, so stop like an exit. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        app.surrealar.util.DiagnosticsLogger.i(app.surrealar.util.diagnostics.DiagTags.APP,
            "Location service stop requested reason=\"task removed\"")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        app.surrealar.util.DiagnosticsLogger.i(app.surrealar.util.diagnostics.DiagTags.APP, "Location service stopped")
        isRunning = false
    }

    private fun buildNotification(): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this,
                0,
                launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("SurReal AR Active")
            .setContentText("Location services running in background")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openIntent)
            // Lets the user end background running without opening the app or force-stopping it.
            .addAction(R.drawable.ic_stat_location, "Stop", PendingIntent.getService(
                this, 1, Intent(this, LocationService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background location service for surveying"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
