package com.example.surveyingapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.surveyingapp.MainActivity
import com.example.surveyingapp.R

// Central helper for foreground location / GNSS notifications
object LocationNotifications {
    // Stable channel / ID constants (referenced by service + system UI)
    const val CHANNEL_ID = "location_channel"
    private const val CHANNEL_NAME = "Location"

    // Create the notification channel (idempotent) for Android O+.
    // On pre-O devices channels do not exist so we no-op.
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            // Only create if missing to avoid resetting user-modified settings
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW // Low = no sound; still visible
                ).apply {
                    enableLights(false)        // Avoid drawing attention; persistent service
                    enableVibration(false)     // No vibration for continuous updates
                    lightColor = Color.GREEN   // If lights ever enabled; cosmetic
                    description = "GNSS / location streaming" // Shown in system settings
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    // Build a foreground service notification with optional extended status text.
    // 'status' can be longer; we use BigTextStyle to allow wrapping.
    fun build(context: Context, title: String, status: String?): Notification {
        // PendingIntent so tapping notification returns to main UI
        val intent = Intent(context, MainActivity::class.java)
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(context, 0, intent, piFlags)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_home)
            .setOngoing(true)                  // Mark as foreground/persistent
            .setContentTitle(title)            // Dynamic title (source / state)
            .setContentText(status ?: "")     // First line (truncated if long)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status ?: "")) // Expanded detail
            .setContentIntent(pi)              // Return to app on tap
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
