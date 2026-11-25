package app.surrealar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.surrealar.data.settings.datastore.SettingsLocalDataSource
import app.surrealar.data.settings.repository.SettingsRepositoryImpl
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.mock.AndroidMockLocationPublisher
import io.github.pennstatecs.geokit.projection.Utm
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import androidx.appcompat.app.AppCompatDelegate
import app.surrealar.settings.model.AppThemeMode
import app.surrealar.gnss.debug.AndroidGnssLogger
import io.github.pennstatecs.geokit.gnss.log.GnssLog
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.DevLog
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.gms.maps.MapsInitializer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Hilt entry point for accessing Hilt singletons from non-injected Application code. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SurRealApplicationEntryPoint {
    fun fixSwitchboard(): FixSwitchboard
    fun lastKnownPositionStore(): app.surrealar.gnss.lastknown.LastKnownPositionStore
    fun sourceSettings(): app.surrealar.gnss.source.SourceSettings
    fun gnssSourceCoordinator(): app.surrealar.gnss.source.GnssSourceCoordinator
    /** The external TCP NMEA source (for reading its fuser's diagnostics stats). */
    fun externalNmeaSource(): io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaSource
    fun settingsRepository(): SettingsRepository
    fun coordinateRepository(): app.surrealar.domain.repository.CoordinateRepository
    fun modelRepository(): app.surrealar.domain.repository.ModelRepository
    fun gnssFeedLogger(): app.surrealar.util.diagnostics.GnssFeedLogger
    fun diagnosticsService(): app.surrealar.gnss.diagnostics.DiagnosticsService
    fun receiverDeviceManager(): app.surrealar.receiver.ReceiverDeviceManager
    fun appDatabase(): app.surrealar.data.local.db.AppDatabase
    fun receiverTiltMonitor(): app.surrealar.receiver.tilt.ReceiverTiltMonitor
    fun coordinateSystemService(): app.surrealar.coords.CoordinateSystemService
}

/**
 * Hilt [Application] and process-startup entry point. In `onCreate` it builds the settings DataStore
 * early (so startup reads don't race), applies the saved theme, restores the last GNSS source, kicks
 * off a one-time UTM backfill, starts the mock-location publisher, and initializes the Maps renderer.
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
        // geokit-gnss logs through GnssLog; route it to logcat / DiagnosticsLogger / GnssDebugLog before
        // Hilt (super.onCreate) builds any GNSS singleton.
        GnssLog.install(AndroidGnssLogger)
        super.onCreate()

        // Init diagnostic file logger before anything else so startup events are captured.
        DiagnosticsLogger.init(this)
        app.surrealar.util.BuildInfo.init(this)
        // SESSION_START: session + install IDs, version, device, locale/zone, last session clean?
        app.surrealar.util.diagnostics.SessionTracker.startProcess(this)
        app.surrealar.util.diagnostics.LifecycleLogger.install(this)
        app.surrealar.util.diagnostics.SettingsChangeLogger.install(this)
        app.surrealar.util.diagnostics.SystemEventsLogger.install(this)
        app.surrealar.util.diagnostics.MainThreadWatchdog.start()
        // Surfaced for tester support: only when a developer opts in (Gradle property
        // ALLOW_DESTRUCTIVE_MIGRATION) may Room wipe & recreate the database on a schema mismatch,
        // which would clear saved coordinates/models. Otherwise data is kept via real migrations.
        DiagnosticsLogger.i(DiagTags.DATA, "Database destructive migration allowed=${BuildConfig.ALLOW_DESTRUCTIVE_MIGRATION}")
        app.surrealar.util.diagnostics.ExitReasonRecorder.recordAtStartup(this)

        // Explicitly select the Maps renderer before any MapView is created.
        // Without this, preferredRenderer is null and the SDK auto-selects — which
        // causes gray/blank tiles on some tablets when it chooses the legacy renderer.
        val preferredRenderer = if (USE_LEGACY_MAPS_RENDERER_FOR_DEBUG)
            MapsInitializer.Renderer.LEGACY else MapsInitializer.Renderer.LATEST
        MapsInitializer.initialize(this, preferredRenderer) { renderer ->
            activeMapsRenderer = renderer.name
            DiagnosticsLogger.i(DiagTags.APP, "Map renderer selected renderer=${renderer.name} preferred=${preferredRenderer.name}")
        }
        // Global crash guard. Persists a SANITIZED crash record to DiagnosticsLogger so it lands in
        // the exported diagnostic ZIP (timestamp + thread + exception type/message + stack trace +
        // build info). It deliberately does NOT include raw API keys, tokens, NMEA, or live
        // coordinates. The previous/default handler is preserved and invoked afterward so Android's
        // normal crash behavior (process termination, Play Console reporting) still happens.
        val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Blocking, fsynced write: the process dies as soon as the previous handler runs.
            try {
                DiagnosticsLogger.writeCrashSync(thread, throwable, fatal = true)
            } catch (_: Throwable) { /* never let crash logging mask the original crash */ }
            previousCrashHandler?.uncaughtException(thread, throwable)
        }
        DevLog.d("SurRealApplication") { "Application started; global crash handler installed" }

        setupSettings()
        // Background: deletes model files no saved model points to (older than 10 min, so never mid-import).
        app.surrealar.data.files.ModelOrphanSweeper.start(this)
        applyThemeFromSettings()
        createNotificationChannel()
        startMockLocationPublisher()
        // After setupSettings(): the feed logger's Hilt graph (FixSwitchboard → SourceSettings) reads settingsRepo.
        // Before the restore, so the restored source switch is logged.
        runCatching {
            EntryPointAccessors.fromApplication(this, SurRealApplicationEntryPoint::class.java).gnssFeedLogger().start()
        }.onFailure { DiagnosticsLogger.w(DiagTags.GNSS, "GNSS feed log start failed", it) }
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
                    .onFailure {
                        if (it is CancellationException) throw it
                        DiagnosticsLogger.e(DiagTags.GNSS, "Startup GNSS source restore failed", it)
                    }
            }
        } catch (e: Exception) {
            DiagnosticsLogger.e(DiagTags.GNSS, "Startup GNSS source restore launch failed", e)
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
                val list = try {
                    repo.getAllCoordinatesList()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    DiagnosticsLogger.e(DiagTags.DATA, "UTM backfill read failed", e)
                    return@launch
                }
                var updated = 0
                var badPosition = 0
                var writeFailed = 0
                list.forEach { c ->
                    if (c.easting == null || c.northing == null || c.utmZone == null) {
                        // Expected for a bad lat/lon: that row is skipped (counted in the summary).
                        val utm = try { Utm.forward(c.latitude, c.longitude) } catch (_: Exception) { badPosition++; null }
                        if (utm != null) {
                            try {
                                repo.update(c.copy(easting = utm.eastingM, northing = utm.northingM, utmZone = utm.zoneLabel))
                                updated++
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                // First failure in full; the rest are counted in the summary. Row is skipped.
                                if (writeFailed++ == 0) DiagnosticsLogger.e(DiagTags.DATA, "UTM backfill save failed coordinateId=${c.id}", e)
                            }
                        }
                    }
                }
                if (updated + badPosition + writeFailed > 0) {
                    DiagnosticsLogger.i(DiagTags.DATA, "UTM backfill finished updated=$updated skippedBadPosition=$badPosition writeFailed=$writeFailed")
                }
            }
        } catch (t: Throwable) {
            DiagnosticsLogger.w(DiagTags.DATA, "UTM backfill start failed", t)
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
        // LogUncaught: an exception in a startup job (mock publisher, settings migration, last-known
        // position) is logged instead of crashing the app.
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + app.surrealar.di.CoroutineModule.LogUncaught)
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
            DiagnosticsLogger.w(DiagTags.SETTINGS, "Theme apply failed: following system", e)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun startMockLocationPublisher() {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(this, SurRealApplicationEntryPoint::class.java)
            val tilt = entryPoint.receiverTiltMonitor()
            mockLocationPublisher = AndroidMockLocationPublisher(this, entryPoint.fixSwitchboard(), settingsRepo,
                heightsMayBePoleTip = tilt::heightsMayBePoleTip)
            mockLocationPublisher.start(appScope)
        } catch (e: Exception) {
            DiagnosticsLogger.e(DiagTags.GNSS, "Mock location publisher start failed", e)
        }
        // Remember the last GNSS position so the Home map can open there while waiting for a fix.
        try {
            val entryPoint = EntryPointAccessors.fromApplication(this, SurRealApplicationEntryPoint::class.java)
            entryPoint.lastKnownPositionStore().start(appScope, entryPoint.fixSwitchboard())
        } catch (e: Exception) {
            DiagnosticsLogger.e(DiagTags.GNSS, "Last-known position store start failed", e)
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
