package com.example.surveyingapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.surveyingapp.data.AppDatabase
import com.example.surveyingapp.data.PositionEntity
import com.example.surveyingapp.data.location.Fix
import com.example.surveyingapp.data.location.LocationSourceManager
import com.example.surveyingapp.data.location.fused.FusedSource
import com.example.surveyingapp.data.location.nmea.NmeaSource
import com.example.surveyingapp.data.settings.SettingsRepository
import com.example.surveyingapp.service.LocationService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.osmdroid.config.Configuration

class SurveyingApp : Application() {
    companion object {
        lateinit var locationManager: LocationSourceManager
        lateinit var settingsRepo: SettingsRepository
        private lateinit var appScope: CoroutineScope
    }

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("GlobalCrash", "Uncaught exception on thread ${thread.name}", throwable)
        }
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
        setupLocationStack()
        createNotificationChannel()
    }

    private fun setupLocationStack() {
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settingsRepo = SettingsRepository(this)
        val fused = FusedSource(this)
        val nmea = NmeaSource(
            btAddressProvider = { settingsRepo.externalBtAddress.first() },
            tcpHostProvider = { settingsRepo.externalTcpHost.first() to settingsRepo.externalTcpPort.first() },
            connectionTypeProvider = { settingsRepo.externalConnType.first() }
        )
        locationManager = LocationSourceManager(settingsRepo, fused, nmea, appScope)
        LocationService.start(this) // start foreground service to stream fixes
        // Logger
        val db = AppDatabase.getDatabase(this)
        appScope.launch {
            locationManager.fixes.collect { f: Fix ->
                try {
                    db.positionDao().insert(f.toEntity())
                } catch (e: Exception) {
                    Log.w("SurveyingApp", "Insert position failed: ${e.message}")
                }
            }
        }
    }

    private fun Fix.toEntity() = PositionEntity(
        id = java.util.UUID.randomUUID().toString(),
        timestamp = timestamp,
        lat = lat,
        lon = lon,
        altEllipsoidalM = altEllipsoidalM,
        accuracyM = null,
        bearingDeg = bearingDeg,
        speedMps = speedMps,
        provider = provider,
        rtkStatus = rtkStatus?.name,
        satsUsed = satsUsed,
        hdop = hdop
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel("loc_channel", "Location", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }
}
