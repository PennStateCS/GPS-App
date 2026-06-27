package app.surrealar.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import app.surrealar.MainActivity
import app.surrealar.R

/** Central helper for foreground location / GNSS notifications. */
object LocationNotifications {
    // Use the same channel ID as LocationService to avoid drift.
    const val CHANNEL_ID = "surveying_location"
    private const val CHANNEL_NAME = "Surveying Location"

    /** Create the channel (idempotent) on Android O+. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW // visible, no sound
                ).apply {
                    enableLights(false)
                    enableVibration(false)
                    lightColor = Color.GREEN
                    description = "GNSS / location streaming"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    /** Build a foreground-service notification with optional extended status text. */
    fun build(context: Context, title: String, status: String?): Notification {
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location) // white, transparent status icon
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(title)
            .setContentText(status ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(status ?: ""))
            .setContentIntent(pi)
            .build()
    }
}
