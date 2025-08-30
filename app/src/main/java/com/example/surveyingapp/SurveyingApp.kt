package com.example.surveyingapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.surveyingapp.data.location.LocationSourceManager
import com.example.surveyingapp.data.location.fused.FusedSource
import com.example.surveyingapp.data.location.nmea.NmeaSource
import com.example.surveyingapp.data.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.osmdroid.config.Configuration

class SurveyingApp : Application() {
    companion object {
        // Global singletons (initialized in Application.onCreate). These are safe here because
        // they rely only on applicationContext; avoid holding Activity references.
        lateinit var locationManager: LocationSourceManager
        lateinit var settingsRepo: SettingsRepository
        private lateinit var appScope: CoroutineScope // Supervisor scope for long‑lived background jobs
        lateinit var nmeaSource: NmeaSource // Exposed for diagnostics / developer tools
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
        setupLocationStack() // Initialize GNSS / fused location pipeline
        createNotificationChannel() // Required for foreground service notifications on O+
    }

    private fun setupLocationStack() {
        // Dedicated supervisor scope so one child failure (e.g., NMEA stream) doesn't cancel others.
        // NOTE: Consider adding a structured dispatcher (e.g., Dispatchers.IO) for I/O heavy NMEA parsing.
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        settingsRepo = SettingsRepository(this)
        val fused = FusedSource(this) // Android fused/location provider wrapper

        // NMEA source configured with suspend lambdas pulling latest prefs each (re)connection.
        // Using .first() each time re-subscribes to DataStore flow; acceptable infrequency, but could be
        // optimized by caching state with stateIn(appScope) if connection churn becomes high.
        val nmea = NmeaSource(
            btAddressProvider = { settingsRepo.externalBtAddress.first() },
            tcpHostProvider = { settingsRepo.externalTcpHost.first() to settingsRepo.externalTcpPort.first() },
            connectionTypeProvider = {
                when (settingsRepo.externalConnType.first().lowercase()) {
                    "tcp" -> NmeaSource.ConnectionType.TCP
                    else -> NmeaSource.ConnectionType.BT
                }
            }
        )
        nmeaSource = nmea
        // LocationSourceManager decides between internal fused vs external RTK sources and exposes unified flows.
        locationManager = LocationSourceManager(settingsRepo, fused, nmea, appScope)
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
