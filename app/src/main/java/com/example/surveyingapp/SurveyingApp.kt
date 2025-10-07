package com.example.surveyingapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.surveyingapp.data.settings.datastore.SettingsLocalDataSource
import com.example.surveyingapp.data.settings.repository.SettingsRepositoryImpl
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.util.GeoProjection
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration

@HiltAndroidApp
class SurveyingApp : Application() {
    companion object {
        // Global settings repository (initialized in Application.onCreate)
        lateinit var settingsRepo: SettingsRepository
        private lateinit var appScope: CoroutineScope // Supervisor scope for long‑lived background jobs
    }

    override fun onCreate() {
        super.onCreate()
        // Basic crash guard: logs uncaught exceptions (consider forwarding to crash reporting service)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("GlobalCrash", "Uncaught exception on thread ${thread.name}", throwable)
        }
        // Configure osmdroid user agent (improves tile server courtesy + analytics separation)
        try {
            val ctx = applicationContext
            val prefs = ctx.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
            Configuration.getInstance().load(ctx, prefs)
            val currentUA = Configuration.getInstance().userAgentValue
            if (currentUA == null || currentUA == "osmdroid") {
                val pkg = ctx.packageName
                val versionName = try {
                    val pm = ctx.packageManager
                    val pInfo = pm.getPackageInfo(pkg, 0)
                    pInfo.versionName ?: "0"
                } catch (e: Exception) { "0" }
                Configuration.getInstance().userAgentValue = "$pkg/$versionName (SurveyingApp)"
            }
            Log.d("SurveyingApp","osmdroid UA set to ${Configuration.getInstance().userAgentValue}")
        } catch (e: Exception) {
            Log.w("SurveyingApp","Failed to set osmdroid user agent: ${e.message}")
        }
        Log.d("SurveyingApp","Application started; global crash handler & osmdroid config done")

        setupSettings() // Initialize settings repository
        createNotificationChannel() // Required for foreground service notifications on O+

        // One-time lightweight backfill: populate UTM fields if missing
        runUtmBackfill()
    }

    private fun runUtmBackfill() {
        try {
            appScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@SurveyingApp)
                val dao = db.coordinateDao()
                val list = kotlin.runCatching { dao.getAllCoordinatesList() }.getOrNull() ?: return@launch
                var updated = 0
                list.forEach { e ->
                    if (e.easting == null || e.northing == null || e.utmZone == null) {
                        try {
                            val utm = GeoProjection.wgs84ToUtm(e.latitude, e.longitude)
                            val copy = e.copy(
                                easting = utm.easting,
                                northing = utm.northing,
                                utmZone = utm.zoneString
                            )
                            dao.update(copy)
                            updated++
                        } catch (_: Exception) { /* ignore bad lat/lon */ }
                    }
                }
                if (updated > 0) Log.d("SurveyingApp", "UTM backfill updated $updated coordinate(s)")
            }
        } catch (t: Throwable) {
            Log.w("SurveyingApp", "UTM backfill skipped: ${t.message}")
        }
    }

    private fun setupSettings() {
        // Dedicated supervisor scope for background operations
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val localDs = SettingsLocalDataSource(this)
        settingsRepo = SettingsRepositoryImpl(localDs)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Low-importance channel prevents intrusive sound while keeping persistent foreground notification.
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel("loc_channel", "Location", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }
}
