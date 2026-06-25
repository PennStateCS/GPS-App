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
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.mock.AndroidMockLocationPublisher
import com.example.surveyingapp.util.UtmConverter
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import androidx.appcompat.app.AppCompatDelegate
import com.example.surveyingapp.gnss.settings.AppThemeMode
import com.example.surveyingapp.util.DiagnosticsLogger
import com.google.android.gms.maps.MapsInitializer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.osmdroid.config.Configuration

/** Hilt entry point for accessing Hilt singletons from non-injected Application code. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SurveyingAppEntryPoint {
    fun fixSwitchboard(): FixSwitchboard
    fun sourceSettings(): com.example.surveyingapp.gnss.settings.SourceSettings
    fun gnssSourceCoordinator(): com.example.surveyingapp.gnss.service.GnssSourceCoordinator
}

@HiltAndroidApp
class SurveyingApp : Application() {
    companion object {
        lateinit var settingsRepo: SettingsRepository
            private set
        lateinit var mockLocationPublisher: AndroidMockLocationPublisher
            private set
        private lateinit var appScope: CoroutineScope

        // Switch to true to force the legacy renderer for one build cycle.
        // Useful for testing whether a blank-map issue is renderer-specific.
        // Must be false in production.
        private const val USE_LEGACY_MAPS_RENDERER_FOR_DEBUG = false

        // Set by the MapsInitializer callback below; readable from any screen (e.g. Maps Debug).
        var activeMapsRenderer: String = "not initialized"
            private set

        // Latest Home-map tile-load status (MAP_READY / MAP_LOADED / timeout), surfaced in the
        // diagnostic report. Updated by HomeFragment via [reportMapLoadStatus].
        var mapLoadStatus: String = "unknown"
            private set

        fun reportMapLoadStatus(status: String) { mapLoadStatus = status }
    }

    override fun onCreate() {
        super.onCreate()

        // Init diagnostic file logger before anything else so startup events are captured.
        DiagnosticsLogger.init(this)
        DiagnosticsLogger.i("App", "Started version=${BuildConfig.VERSION_NAME}" +
            " build=${BuildConfig.BUILD_NUMBER}" +
            " commit=${BuildConfig.BUILD_GIT_HASH}${if (BuildConfig.BUILD_GIT_DIRTY) "-dirty" else ""}" +
            " branch=${BuildConfig.BUILD_GIT_BRANCH}" +
            " device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" +
            " android=${android.os.Build.VERSION.RELEASE}(API ${android.os.Build.VERSION.SDK_INT})" +
            " debug=${BuildConfig.DEBUG}")

        // Explicitly select the Maps renderer before any MapView is created.
        // Without this, preferredRenderer is null and the SDK auto-selects — which
        // causes gray/blank tiles on some tablets when it chooses the legacy renderer.
        val preferredRenderer = if (USE_LEGACY_MAPS_RENDERER_FOR_DEBUG)
            MapsInitializer.Renderer.LEGACY else MapsInitializer.Renderer.LATEST
        MapsInitializer.initialize(this, preferredRenderer) { renderer ->
            activeMapsRenderer = renderer.name
            Log.d("SurveyingApp", "Maps renderer initialised: ${renderer.name} (preferred=${preferredRenderer.name})")
            DiagnosticsLogger.i("App", "Maps renderer: ${renderer.name} (preferred=${preferredRenderer.name})")
        }
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

        setupSettings()
        applyThemeFromSettings()
        createNotificationChannel()
        startMockLocationPublisher()
        restoreSavedGnssSource()
        runUtmBackfill()
    }

    /**
     * Once-per-process restore of the live GNSS provider from the persisted selected source.
     * Runs in Application.onCreate (not an Activity), so it is NOT re-triggered by rotation or
     * Activity recreation — preventing duplicate external reconnects. For saved External this
     * activates EXTERNAL_TCP so the receiver reconnects automatically without opening Settings.
     */
    private fun restoreSavedGnssSource() {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(this, SurveyingAppEntryPoint::class.java)
            appScope.launch {
                runCatching { entryPoint.gnssSourceCoordinator().restoreSavedSourceOnStartup() }
                    .onFailure { Log.e("SurveyingApp", "Startup GNSS source restore failed", it) }
            }
        } catch (e: Exception) {
            Log.e("SurveyingApp", "Failed to launch GNSS source restore", e)
        }
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
                            val utm = UtmConverter.latLonToUtm(e.latitude, e.longitude)
                            val copy = e.copy(
                                easting = utm.easting,
                                northing = utm.northing,
                                utmZone = utm.utmZone
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
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localDs = SettingsLocalDataSource(this)
        settingsRepo = SettingsRepositoryImpl(localDs)
    }

    private fun applyThemeFromSettings() {
        try {
            val themeMode = runBlocking { settingsRepo.appearanceSettings.first().themeMode }
            AppCompatDelegate.setDefaultNightMode(
                when (themeMode) {
                    AppThemeMode.LIGHT  -> AppCompatDelegate.MODE_NIGHT_NO
                    AppThemeMode.DARK   -> AppCompatDelegate.MODE_NIGHT_YES
                    AppThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        } catch (e: Exception) {
            Log.w("SurveyingApp", "Failed to apply theme from settings: ${e.message}")
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun startMockLocationPublisher() {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(this, SurveyingAppEntryPoint::class.java)
            mockLocationPublisher = AndroidMockLocationPublisher(this, entryPoint.fixSwitchboard(), settingsRepo)
            mockLocationPublisher.start(appScope)
        } catch (e: Exception) {
            Log.e("SurveyingApp", "Failed to start mock location publisher", e)
        }
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
