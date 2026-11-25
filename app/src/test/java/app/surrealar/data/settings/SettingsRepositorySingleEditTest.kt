package app.surrealar.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.surrealar.data.settings.datastore.SettingsLocalDataSource
import app.surrealar.data.settings.repository.SettingsRepositoryImpl
import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.gnss.settings.GnssReceiverSettings
import app.surrealar.settings.SettingsDefaults
import app.surrealar.settings.model.AppThemeMode
import app.surrealar.settings.model.AppearanceSettings
import app.surrealar.settings.model.ArDisplaySettings
import app.surrealar.settings.model.CoordinateDisplaySettings
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ExternalReceiverSettings
import io.github.pennstatecs.geokit.gnss.capture.GnssCaptureSettings
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Each settings group must be written in ONE DataStore transaction (all-or-nothing), with the same values. */
class SettingsRepositorySingleEditTest {

    /** Counts DataStore transactions; `edit {}` goes through [updateData]. */
    private class CountingDataStore(private val inner: DataStore<Preferences>) : DataStore<Preferences> {
        var updates = 0
        override val data: Flow<Preferences> get() = inner.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            updates++
            return inner.updateData(transform)
        }
    }

    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: CountingDataStore
    private lateinit var repo: SettingsRepositoryImpl

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("settings_single").toFile()
        scope = CoroutineScope(Job() + Dispatchers.IO)
        store = CountingDataStore(PreferenceDataStoreFactory.create(scope = scope) { File(tempDir, "s.preferences_pb") })
        repo = SettingsRepositoryImpl(SettingsLocalDataSource(store))
    }

    @After
    fun tearDown() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        tempDir.deleteRecursively()
    }

    private fun oneEdit(block: suspend () -> Unit) = runBlocking {
        val before = store.updates
        block()
        assertEquals("expected one DataStore edit", 1, store.updates - before)
    }

    @Test
    fun receiverSettings_oneEdit_roundTrip() {
        val s = GnssReceiverSettings(
            highAccuracy = false, poleHeightM = 1.8, internalAntennaHeightM = 1.2,
            cameraBelowReceiverM = -0.25, phaseCenterOffsetOverrideM = 0.1,
        )
        oneEdit { repo.setGnssReceiverSettings(s) }
        assertEquals(s, runBlocking { repo.gnssReceiverSettings.first() })
        // Clearing the override removes it (the profile's offset applies again).
        oneEdit { repo.setGnssReceiverSettings(s.copy(phaseCenterOffsetOverrideM = null)) }
        assertEquals(null, runBlocking { repo.gnssReceiverSettings.first() }.phaseCenterOffsetOverrideM)
    }

    @Test
    fun externalTcpReceiver_savedInOneEdit_nameKeptForTheSameEndpoint() {
        oneEdit {
            repo.saveExternalTcpReceiver("192.168.42.7", 9001, "Base", ExternalReceiverProfile.GENERIC_NMEA_TCP,
                app.surrealar.settings.model.ProfileOrigin.USER, app.surrealar.domain.model.LocationSourceType.EXTERNAL)
        }
        runBlocking {
            assertEquals(ExternalConnectionType.TCP, repo.externalConnType.first())
            assertEquals("192.168.42.7", repo.externalTcpHost.first())
            assertEquals(9001, repo.externalTcpPort.first())
            assertEquals("Base", repo.externalTcpName.first())
            assertEquals(ExternalReceiverProfile.GENERIC_NMEA_TCP, repo.externalReceiverProfile.first())
            assertEquals(app.surrealar.settings.model.ProfileOrigin.USER, repo.externalReceiverProfileOrigin.first())
            assertEquals(app.surrealar.domain.model.LocationSourceType.EXTERNAL, repo.locationSource.first())
        }
        // Reconnect without a name and without a profile: the name and the profile stay.
        oneEdit {
            repo.saveExternalTcpReceiver(" 192.168.42.7", 9001, "", null,
                app.surrealar.settings.model.ProfileOrigin.USER, app.surrealar.domain.model.LocationSourceType.EXTERNAL)
        }
        runBlocking {
            assertEquals("Base", repo.externalTcpName.first())
            assertEquals(ExternalReceiverProfile.GENERIC_NMEA_TCP, repo.externalReceiverProfile.first())
        }
        // Another receiver without a name: the old name is cleared.
        oneEdit {
            repo.saveExternalTcpReceiver("192.168.42.8", 9001, "", null,
                app.surrealar.settings.model.ProfileOrigin.USER, app.surrealar.domain.model.LocationSourceType.EXTERNAL)
        }
        assertEquals("", runBlocking { repo.externalTcpName.first() })
    }

    @Test
    fun receiverSettings_legacyValuesAreConvertedOnRead() = runBlocking {
        // Stored before 2026-11: pole height to the phase centre, AR antenna above the camera.
        store.updateData {
            it.toMutablePreferences().apply {
                this[app.surrealar.data.settings.datastore.SettingsKeys.ANTENNA_HEIGHT_M] = 2.134
                this[app.surrealar.data.settings.datastore.SettingsKeys.AR_ANTENNA_ABOVE_CAMERA_M] = "0.6"
            }
        }
        val s = repo.gnssReceiverSettings.first()   // default profile: Reach RS2+ (0.134 m offset)
        assertEquals("old pole height kept as is", 2.134, s.poleHeightM, 1e-9)
        assertEquals("and flagged for the user to check", true, s.poleHeightNeedsCheck)
        assertEquals("camera distance keeps the old AR total", 0.6 - 0.134, s.cameraBelowReceiverM, 1e-9)
        val setup = app.surrealar.settings.model.AntennaSetup.resolve(s, ExternalReceiverProfile.REACH_RS2_PLUS)
        assertEquals(0.6, setup.antennaAboveCameraM, 1e-9)

        // Confirming (or changing) the pole height ends the notice and writes the new keys.
        repo.setGnssReceiverSettings(s.copy(poleHeightNeedsCheck = false))
        val after = repo.gnssReceiverSettings.first()
        assertEquals(false, after.poleHeightNeedsCheck)
        assertEquals(2.134, after.poleHeightM, 1e-9)
        assertEquals(0.6 - 0.134, after.cameraBelowReceiverM, 1e-9)
    }

    @Test
    fun receiverSettings_freshInstall_noNoticeAndDefaults() = runBlocking {
        val s = repo.gnssReceiverSettings.first()
        assertEquals(SettingsDefaults.gnssReceiver.poleHeightM, s.poleHeightM, 0.0)
        assertEquals(0.0, s.cameraBelowReceiverM, 0.0)
        assertEquals(false, s.poleHeightNeedsCheck)
    }

    @Test
    fun captureSettings_oneEdit_roundTrip_andBlankTargetRemoved() {
        val s = GnssCaptureSettings(
            requiredMinStatus = RtkStatus.FLOAT, minDurationSec = 3, maxDurationSec = 9, minSamples = 4,
            maxFixAgeSec = 2, maxDiffAgeSec = 7, preset = SettingsDefaults.gnssCapture.preset, precisionTargetM = 0.02,
        )
        oneEdit { repo.setGnssCaptureSettings(s) }
        assertEquals(s, runBlocking { repo.gnssCaptureSettings.first() })
        oneEdit { repo.setGnssCaptureSettings(s.copy(precisionTargetM = null)) }
        assertEquals(null, runBlocking { repo.gnssCaptureSettings.first() }.precisionTargetM)
    }

    @Test
    fun arDisplay_oneEdit_roundTrip() {
        val s = ArDisplaySettings(
            altitudeMode = "TERRAIN", distanceFilterIndex = 2, showDebugOverlay = true, showLabels = false,
            showOffscreenArrows = false, modelScale = 2.5f, showArDebugTools = true,
        )
        oneEdit { repo.setArDisplaySettings(s) }
        assertEquals(s, runBlocking { repo.arDisplaySettings.first() })
    }

    @Test
    fun appearance_coordinateDisplay_stakeout_map_oneEditEach() {
        val a = AppearanceSettings(AppThemeMode.DARK, showLiveGnssStatusBar = false, keepScreenAwake = true, maxBrightnessWhileOpen = true)
        oneEdit { repo.setAppearanceSettings(a) }
        assertEquals(a, runBlocking { repo.appearanceSettings.first() })

        val c = CoordinateDisplaySettings(showAccuracyIndicators = false, defaultNamePrefix = "CP", autoIncrementNames = false)
        oneEdit { repo.setCoordinateDisplaySettings(c) }
        assertEquals(c, runBlocking { repo.coordinateDisplaySettings.first() })

        val st = SettingsDefaults.stakeout.copy(toleranceMeters = 0.07, warningAccuracyMeters = 0.2, enableAudio = !SettingsDefaults.stakeout.enableAudio)
        oneEdit { repo.setStakeoutSettings(st) }
        assertEquals(st, runBlocking { repo.stakeoutSettings.first() })

        val m = SettingsDefaults.map.copy(showMyLocationByDefault = !SettingsDefaults.map.showMyLocationByDefault)
        oneEdit { repo.setMapSettings(m) }
        assertEquals(m, runBlocking { repo.mapSettings.first() })
    }

    @Test
    fun externalReceiverSettings_oneEdit_roundTrip() {
        val s = ExternalReceiverSettings(
            profile = ExternalReceiverProfile.entries.last(), connectionType = ExternalConnectionType.TCP,
            tcpHost = "192.168.1.50", tcpPort = 9001, displayName = "Base",
        )
        oneEdit { repo.setExternalReceiverSettings(s) }
        assertEquals(s, runBlocking { repo.externalReceiverSettings.first() })
    }

    @Test
    fun externalTcp_oneEdit_andNewHostWithoutNameClearsOldName() = runBlocking {
        oneEdit { repo.setExternalTcp("10.0.0.1", 9001, "Rover") }
        assertEquals("Rover", repo.externalTcpName.first())
        // Same host, blank name: keeps the name.
        oneEdit { repo.setExternalTcp("10.0.0.1", 9002, "") }
        assertEquals("Rover", repo.externalTcpName.first())
        assertEquals(9002, repo.externalTcpPort.first())
        // Different host, blank name: clears it.
        oneEdit { repo.setExternalTcp("10.0.0.2", 9001, "") }
        assertEquals("", repo.externalTcpName.first())
        assertEquals("10.0.0.2", repo.externalTcpHost.first())
    }
}
