package app.surrealar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import app.surrealar.data.settings.datastore.SettingsLocalDataSource
import app.surrealar.data.settings.repository.SettingsRepositoryImpl
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.mock.AndroidMockLocationPublisher
import app.surrealar.util.UtmConverter
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import androidx.appcompat.app.AppCompatDelegate
import app.surrealar.settings.model.AppThemeMode
import app.surrealar.util.DiagnosticsLogger
import com.google.android.gms.maps.MapsInitializer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.osmdroid.config.Configuration

/** Hilt entry point for accessing Hilt singletons from non-injected Application code. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SurRealApplicationEntryPoint {
    fun fixSwitchboard(): FixSwitchboard
    fun sourceSettings(): app.surrealar.gnss.source.SourceSettings
    fun gnssSourceCoordinator(): app.surrealar.gnss.source.GnssSourceCoordinator
    /** The external TCP NMEA source (for reading its fuser's diagnostics stats). */
    fun externalNmeaSource(): app.surrealar.gnss.bus.adapters.NmeaSource
    fun settingsRepository(): SettingsRepository
    fun coordinateRepository(): app.surrealar.domain.repository.CoordinateRepository
}

/**
 * Hilt [Application] and process-startup entry point. In `onCreate` it builds the settings DataStore
 * early (so startup reads don't race), applies the saved theme, restores the last GNSS source, kicks
 * off a one-time UTM backfill, starts the mock-location publisher, and initializes the Maps renderer
 * and osmdroid config.
 *
 * The companion exposes a few process-wide singletons ([settingsRepo], [mockLocationPublisher]) and
 * map-diagnostic state ([activeMapsRenderer], [mapLoadStatus]). Prefer Hilt injection (via
 * [SurRealApplicationEntryPoint]) over the [settingsRepo] service-locator in new code — it is retained
 * only for early-startup access before the graph is available. See the architecture guard test.
 */
@HiltAndroidApp
class SurRealApplication : Application() {
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
        // Surfaced for tester support: in debug builds Room is allowed to wipe & recreate the
        // database on a schema mismatch (fallbackToDestructiveMigration), which would clear saved
        // coordinates/models. Release builds keep data via real migrations.
        DiagnosticsLogger.i("DB", "Destructive migration allowed: ${BuildConfig.DEBUG}")

        // Explicitly select the Maps renderer before any MapView is created.
        // Without this, preferredRenderer is null and the SDK auto-selects — which
        // causes gray/blank tiles on some tablets when it chooses the legacy renderer.
        val preferredRenderer = if (USE_LEGACY_MAPS_RENDERER_FOR_DEBUG)
            MapsInitializer.Renderer.LEGACY else MapsInitializer.Renderer.LATEST
        MapsInitializer.initialize(this, preferredRenderer) { renderer ->
            activeMapsRenderer = renderer.name
            Log.d("SurRealApplication", "Maps renderer initialised: ${renderer.name} (preferred=${preferredRenderer.name})")
            DiagnosticsLogger.i("App", "Maps renderer: ${renderer.name} (preferred=${preferredRenderer.name})")
        }
        // Global crash guard. Persists a SANITIZED crash record to DiagnosticsLogger so it lands in
        // the exported diagnostic ZIP (timestamp + thread + exception type/message + stack trace +
        // build info). It deliberately does NOT include raw API keys, tokens, NMEA, or live
        // coordinates. The previous/default handler is preserved and invoked afterward so Android's
        // normal crash behavior (process termination, Play Console reporting) still happens.
        val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                DiagnosticsLogger.e(
                    "GlobalCrash",
                    "Uncaught exception thread=${thread.name} type=${throwable.javaClass.name}" +
                        " app=${BuildConfig.VERSION_NAME} build=${BuildConfig.BUILD_NUMBER}" +
                        " debug=${BuildConfig.DEBUG}",
                    throwable
                )
            } catch (_: Throwable) { /* never let crash logging mask the original crash */ }
            Log.e("GlobalCrash", "Uncaught exception on thread ${thread.name}", throwable)
            previousCrashHandler?.uncaughtException(thread, throwable)
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
            Log.d("SurRealApplication","osmdroid UA set to ${Configuration.getInstance().userAgentValue}")
        } catch (e: Exception) {
            Log.w("SurRealApplication","Failed to set osmdroid user agent: ${e.message}")
        }
        Log.d("SurRealApplication","Application started; global crash handler & osmdroid config done")

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
            val entryPoint = EntryPointAccessors.fromApplication(this, SurRealApplicationEntryPoint::class.java)
            appScope.launch {
                runCatching { entryPoint.gnssSourceCoordinator().restoreSavedSourceOnStartup() }
                    .onFailure { Log.e("SurRealApplication", "Startup GNSS source restore failed", it) }
            }
        } catch (e: Exception) {
            Log.e("SurRealApplication", "Failed to launch GNSS source restore", e)
        }
    }

    private fun runUtmBackfill() {
        try {
            appScope.launch(Dispatchers.IO) {
                // Use the Hilt graph (same EntryPoint pattern as the startup GNSS restore) instead
                // of constructing the database directly — keeps all coordinate access on one path.
                val repo = EntryPointAccessors
                    .fromApplication(this@SurRealApplication, SurRealApplicationEntryPoint::class.java)
                    .coordinateRepository()
                val list = kotlin.runCatching { repo.getAllCoordinatesList() }.getOrNull() ?: return@launch
                var updated = 0
                list.forEach { c ->
                    if (c.easting == null || c.northing == null || c.utmZone == null) {
                        try {
                            val utm = UtmConverter.latLonToUtm(c.latitude, c.longitude)
                            repo.update(c.copy(easting = utm.easting, northing = utm.northing, utmZone = utm.utmZone))
                            updated++
                        } catch (_: Exception) { /* ignore bad lat/lon */ }
                    }
                }
                if (updated > 0) Log.d("SurRealApplication", "UTM backfill updated $updated coordinate(s)")
            }
        } catch (t: Throwable) {
            Log.w("SurRealApplication", "UTM backfill skipped: ${t.message}")
        }
    }

    /**
     * Builds the ONE production settings repository for the process.
     *
     * This is the single approved construction site for [SettingsRepositoryImpl] and the single
     * production [SettingsLocalDataSource] (which opens the one `app_settings` Preferences DataStore).
     * It runs here, in `Application.onCreate`, so the theme ([applyThemeFromSettings]) and the
     * mock-location publisher ([startMockLocationPublisher]) can read settings during startup. Hilt
     * does not construct this — it bridges to [settingsRepo] (see [app.surrealar.di.SettingsModule]),
     * which keeps the DataStore count at exactly one.
     *
     * Do NOT construct another [SettingsRepositoryImpl] or open another settings DataStore in
     * production code: DataStore throws if two instances are active over the same file. Tests use
     * isolated temp DataStores instead. See `docs/settings-architecture.md` → Production ownership;
     * `ArchitectureGuardTest` enforces this.
     */
    private fun setupSettings() {
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localDs = SettingsLocalDataSource(this)
        settingsRepo = SettingsRepositoryImpl(localDs)
        // Run settings-storage migrations on the same DataStore (currently a version stamp; this is
        // the hook for future format changes). Non-blocking — current settings reads tolerate the
        // pre-migration state (enum tokens normalize lazily on read).
        appScope.launch {
            app.surrealar.data.settings.migration.SettingsMigrationRunner(
                readVersion = { localDs.schemaVersion.first() },
                writeVersion = { localDs.setSchemaVersion(it) }
            ).migrateIfNeeded()
        }
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
            Log.w("SurRealApplication", "Failed to apply theme from settings: ${e.message}")
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun startMockLocationPublisher() {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(this, SurRealApplicationEntryPoint::class.java)
            mockLocationPublisher = AndroidMockLocationPublisher(this, entryPoint.fixSwitchboard(), settingsRepo)
            mockLocationPublisher.start(appScope)
        } catch (e: Exception) {
            Log.e("SurRealApplication", "Failed to start mock location publisher", e)
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
