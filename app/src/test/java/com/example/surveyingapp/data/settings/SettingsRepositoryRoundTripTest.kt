package com.example.surveyingapp.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.surveyingapp.data.settings.datastore.SettingsKeys
import com.example.surveyingapp.data.settings.datastore.SettingsLocalDataSource
import com.example.surveyingapp.data.settings.migration.SettingsMigrationRunner
import com.example.surveyingapp.data.settings.repository.SettingsRepositoryImpl
import com.example.surveyingapp.domain.repository.ArDisplaySettingsRepository
import com.example.surveyingapp.domain.repository.ExternalReceiverSettingsRepository
import com.example.surveyingapp.domain.repository.GnssCaptureSettingsRepository
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.capture.GnssCaptureSettings
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.settings.SettingsDefaults
import com.example.surveyingapp.settings.model.AppThemeMode
import com.example.surveyingapp.settings.model.AppearanceSettings
import com.example.surveyingapp.settings.model.ArDisplaySettings
import com.example.surveyingapp.settings.model.CoordinateDisplaySettings
import com.example.surveyingapp.settings.model.ExternalReceiverProfile
import com.example.surveyingapp.settings.model.ExternalReceiverSettings
import com.example.surveyingapp.settings.model.StakeoutSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Exercises the REAL [SettingsRepositoryImpl] over a REAL Preferences DataStore. Each test gets its
 * own temp-file DataStore + coroutine scope (via the [SettingsLocalDataSource] DataStore constructor),
 * so tests are fully isolated with no shared state — no Robolectric, no production files touched.
 */
class SettingsRepositoryRoundTripTest {

    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var localDs: SettingsLocalDataSource
    private lateinit var repo: SettingsRepositoryImpl

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("settings_rt").toFile()
        scope = CoroutineScope(Job() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(tempDir, "settings.preferences_pb") }
        localDs = SettingsLocalDataSource(dataStore)
        repo = SettingsRepositoryImpl(localDs)
    }

    @After
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    /** Writes a raw legacy/garbage value directly to a key, bypassing the repo's prefKey writer. */
    private fun writeRawString(key: Preferences.Key<String>, value: String) =
        runBlocking { dataStore.edit { it[key] = value } }

    // ── enum-backed settings ────────────────────────────────────────────────────

    @Test
    fun `locationSource default, round-trip, legacy name, unknown fallback`() = runBlocking {
        assertEquals(LocationSourceType.INTERNAL, repo.locationSource.first())
        repo.setLocationSource(LocationSourceType.EXTERNAL)
        assertEquals(LocationSourceType.EXTERNAL, repo.locationSource.first())
        writeRawString(SettingsKeys.LOCATION_SOURCE, "INTERNAL") // legacy uppercase name
        assertEquals(LocationSourceType.INTERNAL, repo.locationSource.first())
        writeRawString(SettingsKeys.LOCATION_SOURCE, "garbage")
        assertEquals(LocationSourceType.INTERNAL, repo.locationSource.first())
    }

    @Test
    fun `externalConnType default is TCP for a fresh install`() = runBlocking {
        // No value saved → defaults to TCP (the only implemented external transport).
        assertEquals(ExternalConnectionType.TCP, repo.externalConnType.first())
    }

    @Test
    fun `externalConnType round-trip, legacy BT preserved, unknown falls back to TCP`() = runBlocking {
        repo.setExternalConnType(ExternalConnectionType.TCP)
        assertEquals(ExternalConnectionType.TCP, repo.externalConnType.first())
        // A legacy saved "bt"/"BT" value still reads back as BT.
        writeRawString(SettingsKeys.EXTERNAL_CONN_TYPE, "bt")
        assertEquals(ExternalConnectionType.BT, repo.externalConnType.first())
        writeRawString(SettingsKeys.EXTERNAL_CONN_TYPE, "BT")
        assertEquals(ExternalConnectionType.BT, repo.externalConnType.first())
        // An explicitly saved TCP value still reads back as TCP.
        writeRawString(SettingsKeys.EXTERNAL_CONN_TYPE, "tcp")
        assertEquals(ExternalConnectionType.TCP, repo.externalConnType.first())
        // Garbage falls back to TCP.
        writeRawString(SettingsKeys.EXTERNAL_CONN_TYPE, "garbage")
        assertEquals(ExternalConnectionType.TCP, repo.externalConnType.first())
    }

    @Test
    fun `selecting an RS4 or RS4 Pro profile keeps connection type TCP on a fresh install`() = runBlocking {
        listOf(ExternalReceiverProfile.REACH_RS4, ExternalReceiverProfile.REACH_RS4_PRO).forEach { p ->
            repo.setExternalReceiverProfile(p)
            // Profile selection does not touch the connection type; the fresh-install default is TCP.
            assertEquals(ExternalConnectionType.TCP, repo.externalReceiverSettings.first().connectionType)
        }
    }

    @Test
    fun `appThemeMode default, round-trip, legacy name, unknown fallback`() = runBlocking {
        assertEquals(AppThemeMode.SYSTEM, repo.appearanceSettings.first().themeMode)
        repo.setAppearanceSettings(AppearanceSettings(themeMode = AppThemeMode.DARK))
        assertEquals(AppThemeMode.DARK, repo.appearanceSettings.first().themeMode)
        writeRawString(SettingsKeys.APP_THEME_MODE, "LIGHT")
        assertEquals(AppThemeMode.LIGHT, repo.appearanceSettings.first().themeMode)
        writeRawString(SettingsKeys.APP_THEME_MODE, "garbage")
        assertEquals(AppThemeMode.SYSTEM, repo.appearanceSettings.first().themeMode)
    }

    @Test
    fun `capture RtkStatus default, round-trip, legacy name, unknown fallback`() = runBlocking {
        assertEquals(RtkStatus.FIX, repo.gnssCaptureSettings.first().requiredMinStatus)
        repo.setGnssCaptureSettings(GnssCaptureSettings(requiredMinStatus = RtkStatus.FLOAT))
        assertEquals(RtkStatus.FLOAT, repo.gnssCaptureSettings.first().requiredMinStatus)
        writeRawString(SettingsKeys.GNSS_CAPTURE_RTK_STATUS, "DGPS")
        assertEquals(RtkStatus.DGPS, repo.gnssCaptureSettings.first().requiredMinStatus)
        writeRawString(SettingsKeys.GNSS_CAPTURE_RTK_STATUS, "garbage")
        assertEquals(RtkStatus.FIX, repo.gnssCaptureSettings.first().requiredMinStatus)
    }

    // ── external receiver settings ──────────────────────────────────────────────

    @Test
    fun `receiver profile default and every profile persists`() = runBlocking {
        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, repo.externalReceiverProfile.first())
        ExternalReceiverProfile.entries.forEach { p ->
            repo.setExternalReceiverProfile(p)
            assertEquals(p, repo.externalReceiverProfile.first())
        }
    }

    @Test
    fun `tcp host and port persist`() = runBlocking {
        repo.setExternalTcp("192.168.1.25", 9001, "field-unit")
        assertEquals("192.168.1.25", repo.externalTcpHost.first())
        assertEquals(9001, repo.externalTcpPort.first())
        assertEquals("field-unit", repo.externalTcpName.first())
    }

    @Test
    fun `invalid stored port is preserved raw but sanitized by SettingsDefaults`() = runBlocking {
        dataStore.edit { it[SettingsKeys.EXTERNAL_TCP_PORT] = 70000 }
        assertEquals(70000, repo.externalTcpPort.first())
        assertEquals(9000, SettingsDefaults.sanitizeTcpPort(repo.externalTcpPort.first()))
    }

    @Test
    fun `selecting a profile does not overwrite an existing saved port at the repository level`() = runBlocking {
        repo.setExternalTcp("10.0.0.5", 2947)
        repo.setExternalReceiverProfile(ExternalReceiverProfile.REACH_RS4)
        assertEquals(2947, repo.externalTcpPort.first())
        assertEquals(ExternalReceiverProfile.REACH_RS4, repo.externalReceiverProfile.first())
    }

    // ── grouped settings ────────────────────────────────────────────────────────

    @Test
    fun `capture settings default then round-trip exactly`() = runBlocking {
        assertEquals(SettingsDefaults.gnssCapture, repo.gnssCaptureSettings.first())
        val custom = GnssCaptureSettings(
            requiredMinStatus = RtkStatus.FLOAT, minDurationSec = 30, maxDurationSec = 90,
            minSamples = 200, maxFixAgeSec = 5, maxDiffAgeSec = 15
        )
        repo.setGnssCaptureSettings(custom)
        assertEquals(custom, repo.gnssCaptureSettings.first())
    }

    @Test
    fun `AR display settings default then round-trip exactly`() = runBlocking {
        assertEquals(SettingsDefaults.arDisplay, repo.arDisplaySettings.first())
        val custom = ArDisplaySettings(
            altitudeMode = "TERRAIN", distanceFilterIndex = 2, showDebugOverlay = true,
            showLabels = false, showOffscreenArrows = false, modelScale = 2.5f, showArDebugTools = true
        )
        repo.setArDisplaySettings(custom)
        assertEquals(custom, repo.arDisplaySettings.first())
    }

    @Test
    fun `coordinate display settings default then round-trip exactly`() = runBlocking {
        assertEquals(SettingsDefaults.coordinateDisplay, repo.coordinateDisplaySettings.first())
        val custom = CoordinateDisplaySettings(
            showAccuracyIndicators = false, defaultNamePrefix = "Stn", autoIncrementNames = false
        )
        repo.setCoordinateDisplaySettings(custom)
        assertEquals(custom, repo.coordinateDisplaySettings.first())
    }

    @Test
    fun `mock location and developer settings default then round-trip`() = runBlocking {
        assertEquals(false, repo.mockLocationEnabled.first())
        repo.setMockLocationEnabled(true)
        assertEquals(true, repo.mockLocationEnabled.first())

        assertEquals(SettingsDefaults.gnssReceiver, repo.gnssReceiverSettings.first())
        repo.setGnssReceiverSettings(repo.gnssReceiverSettings.first().copy(highAccuracy = false))
        assertEquals(false, repo.gnssReceiverSettings.first().highAccuracy)
    }

    // ── ExternalReceiverSettings aggregate ──────────────────────────────────────

    @Test
    fun `external receiver aggregate default matches SettingsDefaults`() = runBlocking {
        assertEquals(SettingsDefaults.externalReceiverSettings, repo.externalReceiverSettings.first())
    }

    @Test
    fun `external receiver aggregate reflects individual writes and matches individual flows`() = runBlocking {
        repo.setExternalReceiverProfile(ExternalReceiverProfile.REACH_RS4)
        repo.setExternalConnType(ExternalConnectionType.TCP)
        repo.setExternalTcp("192.168.42.1", 9001, "rover")

        val agg = repo.externalReceiverSettings.first()
        assertEquals(ExternalReceiverProfile.REACH_RS4, agg.profile)
        assertEquals(ExternalConnectionType.TCP, agg.connectionType)
        assertEquals("192.168.42.1", agg.tcpHost)
        assertEquals(9001, agg.tcpPort)
        assertEquals("rover", agg.displayName)
        assertEquals(repo.externalReceiverProfile.first(), agg.profile)
        assertEquals(repo.externalTcpHost.first(), agg.tcpHost)
        assertEquals(repo.externalTcpPort.first(), agg.tcpPort)
        assertEquals(repo.externalTcpName.first(), agg.displayName)
    }

    @Test
    fun `external receiver aggregate sanitizes an invalid stored port`() = runBlocking {
        dataStore.edit { it[SettingsKeys.EXTERNAL_TCP_PORT] = 70000 }
        assertEquals(9000, repo.externalReceiverSettings.first().tcpPort)
    }

    @Test
    fun `setExternalReceiverSettings round-trips`() = runBlocking {
        val s = ExternalReceiverSettings(
            profile = ExternalReceiverProfile.REACH_RS4_PRO,
            connectionType = ExternalConnectionType.TCP,
            tcpHost = "10.0.0.9", tcpPort = 2947, displayName = "pro-unit"
        )
        repo.setExternalReceiverSettings(s)
        assertEquals(s, repo.externalReceiverSettings.first())
    }

    // ── stakeout guidance settings ──────────────────────────────────────────────

    @Test
    fun `stakeout settings default matches SettingsDefaults`() = runBlocking {
        assertEquals(SettingsDefaults.stakeout, repo.stakeoutSettings.first())
    }

    @Test
    fun `stakeout settings round-trip`() = runBlocking {
        val s = StakeoutSettings(
            toleranceMeters = 0.05,
            warningAccuracyMeters = 0.50,
            enableHaptics = false,
            enableAudio = true,
            keepScreenOnDuringStakeout = false,
            guidanceUsesCompassHeading = false,
        )
        repo.setStakeoutSettings(s)
        assertEquals(s, repo.stakeoutSettings.first())
    }

    @Test
    fun `stakeout settings sanitize an out-of-range tolerance and accuracy`() = runBlocking {
        dataStore.edit {
            it[SettingsKeys.STAKEOUT_TOLERANCE_M] = -5.0
            it[SettingsKeys.STAKEOUT_WARNING_ACCURACY_M] = 100000.0
        }
        val s = repo.stakeoutSettings.first()
        assertEquals(SettingsDefaults.stakeout.toleranceMeters, s.toleranceMeters, 1e-9)
        assertEquals(SettingsDefaults.stakeout.warningAccuracyMeters, s.warningAccuracyMeters, 1e-9)
    }

    // ── map display defaults ────────────────────────────────────────────────────

    @Test
    fun `map settings default matches SettingsDefaults`() = runBlocking {
        assertEquals(SettingsDefaults.map, repo.mapSettings.first())
    }

    @Test
    fun `map settings round-trip with stable enum tokens`() = runBlocking {
        val s = com.example.surveyingapp.ui.rendermap.MapSettings(
            defaultMapType = com.google.android.gms.maps.GoogleMap.MAP_TYPE_HYBRID,
            defaultGridMode = com.example.surveyingapp.ui.rendermap.MapGridMode.FINE,
            defaultPointLabelMode = com.example.surveyingapp.ui.rendermap.PointLabelMode.DISTANCE,
            showMyLocationByDefault = false,
            keepMapToolsOpenByDefault = true,
            mapPointsDrawerExpandedByDefault = false,
        )
        repo.setMapSettings(s)
        assertEquals(s, repo.mapSettings.first())
        // Stored values are stable tokens, not enum.name / raw ints.
        assertEquals("hybrid", dataStore.data.first()[SettingsKeys.MAP_DEFAULT_TYPE])
        assertEquals("fine", dataStore.data.first()[SettingsKeys.MAP_DEFAULT_GRID_MODE])
        assertEquals("distance", dataStore.data.first()[SettingsKeys.MAP_DEFAULT_LABEL_MODE])
    }

    @Test
    fun `map settings tolerate legacy enum-name tokens and unknown map type`() = runBlocking {
        dataStore.edit {
            it[SettingsKeys.MAP_DEFAULT_GRID_MODE] = "COARSE"     // legacy uppercase enum name
            it[SettingsKeys.MAP_DEFAULT_LABEL_MODE] = "ELEVATION"
            it[SettingsKeys.MAP_DEFAULT_TYPE] = "nonsense"
        }
        val s = repo.mapSettings.first()
        assertEquals(com.example.surveyingapp.ui.rendermap.MapGridMode.COARSE, s.defaultGridMode)
        assertEquals(com.example.surveyingapp.ui.rendermap.PointLabelMode.ELEVATION, s.defaultPointLabelMode)
        // Unknown / never MAP_TYPE_NONE → Normal.
        assertEquals(com.google.android.gms.maps.GoogleMap.MAP_TYPE_NORMAL, s.defaultMapType)
    }

    // ── focused interfaces share the one backing implementation ─────────────────

    @Test
    fun `aggregate and focused settings interfaces are one and the same owner instance`() = runBlocking {
        // OWNERSHIP: the single SettingsRepositoryImpl satisfies the aggregate AND every focused
        // interface. In production this same instance is what SurveyingApp.setupSettings() builds and
        // Hilt bridges via SurveyingApp.settingsRepo (see di/SettingsModule.kt). Here we assert
        // referential identity — they are literally the one object, not separate impls/DataStores.
        val ext: ExternalReceiverSettingsRepository = repo
        val capture: GnssCaptureSettingsRepository = repo
        val ar: ArDisplaySettingsRepository = repo
        val agg: SettingsRepository = repo
        assertSame(agg, ext)
        assertSame(agg, capture)
        assertSame(agg, ar)

        // A write through one focused interface is visible through another related focused
        // interface AND through the aggregate flow — proving a shared backing store, not copies.
        ext.setExternalReceiverProfile(ExternalReceiverProfile.REACH_RS4_PRO)
        assertEquals(ExternalReceiverProfile.REACH_RS4_PRO, ext.externalReceiverProfile.first())
        assertEquals(ExternalReceiverProfile.REACH_RS4_PRO, agg.externalReceiverSettings.first().profile)

        capture.setGnssCaptureSettings(GnssCaptureSettings(requiredMinStatus = RtkStatus.FLOAT))
        assertEquals(RtkStatus.FLOAT, agg.gnssCaptureSettings.first().requiredMinStatus)

        ar.setArDisplaySettings(ar.arDisplaySettings.first().copy(showLabels = false))
        assertEquals(false, agg.arDisplaySettings.first().showLabels)
    }

    // ── migration through the real DataStore ────────────────────────────────────

    @Test
    fun `missing schema version migrates to current via real DataStore`() = runBlocking {
        assertEquals(null, localDs.schemaVersion.first())
        val runner = SettingsMigrationRunner(
            readVersion = { localDs.schemaVersion.first() },
            writeVersion = { localDs.setSchemaVersion(it) }
        )
        val result = runner.migrateIfNeeded()
        assertEquals(SettingsDefaults.CURRENT_SETTINGS_SCHEMA_VERSION, localDs.schemaVersion.first())
        assertEquals(true, result.migrated)

        val second = runner.migrateIfNeeded()
        assertEquals(false, second.migrated)
        assertEquals(SettingsDefaults.CURRENT_SETTINGS_SCHEMA_VERSION, localDs.schemaVersion.first())
    }
}
