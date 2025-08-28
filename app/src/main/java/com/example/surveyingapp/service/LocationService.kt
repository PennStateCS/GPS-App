package com.example.surveyingapp.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.surveyingapp.MainActivity
import com.example.surveyingapp.R
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.data.location.Fix
import com.example.surveyingapp.data.location.LocationStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class LocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastFix: Fix? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Starting", null))
        scope.launch { collectStreams() }
    }

    private suspend fun collectStreams() {
        val mgr = SurveyingApp.locationManager
        scope.launch {
            mgr.fixes.collectLatest { fix ->
                lastFix = fix
                updateNotification()
            }
        }
        scope.launch {
            mgr.status.collectLatest { _ -> updateNotification() }
        }
    }

    private fun updateNotification() {
        val status = SurveyingApp.locationManager.status.value
        val fix = lastFix
        val title = when (status) {
            is LocationStatus.Streaming -> if (fix?.provider?.startsWith("rs2") == true) "Location: RS2+" else "Location: Internal"
            is LocationStatus.Connecting -> "Location: Connecting (${status.attempt})"
            is LocationStatus.Error -> "Location: Error"
            LocationStatus.Idle -> "Location: Idle"
        }
        val text = fix?.let { f ->
            val rtk = f.rtkStatus?.name ?: "--"
            "${f.lat.format(5)}, ${f.lon.format(5)} alt=${f.altEllipsoidalM?.format(1) ?: "--"} rtk=$rtk sats=${f.satsUsed ?: "--"} hdop=${f.hdop?.format(1) ?: "--"}"
        } ?: "No fix yet"
        val notif = buildNotification(title, text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    private fun buildNotification(title: String, text: String?): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_home)
            .setContentTitle(title)
            .setContentText(text ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text ?: ""))
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "loc_channel"
        private const val NOTIF_ID = 41
        fun start(context: Context) {
            context.startForegroundService(Intent(context, LocationService::class.java))
        }
    }
}

private fun Double.format(dec: Int) = String.format(java.util.Locale.US, "% .${'$'}{dec}f", this).trim()
