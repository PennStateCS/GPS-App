package app.surrealar.data.settings.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.surrealar.settings.SettingsDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Raw [SettingsLocalDataSource] behaviour over a real temp-file Preferences DataStore: defaults when a
 * key is absent, setter -> flow round trips, removal, and the precision-target "<= 0 means off" rule.
 * Plain JVM (no Robolectric); each test gets its own file + scope.
 */
class SettingsLocalDataSourceTest {

    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var ds: SettingsLocalDataSource

    @Before fun setUp() {
        tempDir = Files.createTempDirectory("settings_lds").toFile()
        scope = CoroutineScope(Job() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(tempDir, "s.preferences_pb") }
        ds = SettingsLocalDataSource(dataStore)
    }

    @After fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    private fun <T> Flow<T>.now(): T = runBlocking { first() }

    @Test fun `empty store yields null raw values and centralized defaults`() {
        assertNull(ds.locationSourceRaw.now())
        assertNull(ds.externalConnTypeRaw.now())
        assertNull(ds.externalTcpHost.now())
        assertNull(ds.externalTcpPort.now())
        assertNull(ds.externalTcpName.now())
        assertNull(ds.externalReceiverProfileRaw.now())
        assertNull(ds.schemaVersion.now())
        assertNull(ds.defaultCoordinateNamePrefix.now())
        assertNull(ds.coordHorizontalSystem.now())
        assertNull(ds.coordOutputDatum.now())
        assertNull(ds.coordLinearUnit.now())
        assertNull(ds.coordVerticalReference.now())
        assertNull(ds.coordExternalRxDatum.now())
        assertNull(ds.gnssCapturePrecisionTargetM.now())
        assertNull(ds.stakeoutToleranceMRaw.now())
        assertNull(ds.stakeoutWarningAccuracyMRaw.now())
        assertNull(ds.mapDefaultTypeRaw.now())
        assertNull(ds.mapDefaultGridModeRaw.now())
        assertNull(ds.mapDefaultLabelModeRaw.now())

        val d = SettingsDefaults
        assertEquals(d.coordinateDisplay.autoIncrementNames, ds.autoIncrementCoordinateNames.now())
        assertEquals(d.coordinateDisplay.showAccuracyIndicators, ds.showAccuracyIndicators.now())
        assertEquals(d.gnssCapture.requiredMinStatus.prefKey, ds.gnssCaptureRtkStatus.now())
        assertEquals(d.gnssCapture.minDurationSec, ds.gnssCaptureMinDurationSec.now())
        assertEquals(d.gnssCapture.maxDurationSec, ds.gnssCaptureMaxDurationSec.now())
        assertEquals(d.gnssCapture.minSamples, ds.gnssCaptureMinSamples.now())
        assertEquals(d.gnssCapture.maxFixAgeSec, ds.gnssCaptureMaxFixAgeSec.now())
        assertEquals(d.gnssCapture.maxDiffAgeSec, ds.gnssCaptureMaxDiffAgeSec.now())
        assertEquals(d.gnssCapture.preset.prefKey, ds.gnssCapturePreset.now())
        assertEquals(d.arDisplay.altitudeMode, ds.arAltitudeMode.now())
        assertEquals(d.arDisplay.distanceFilterIndex, ds.arDistanceFilterIndex.now())
        assertEquals(d.arDisplay.showDebugOverlay, ds.arShowDebugOverlay.now())
        assertEquals(d.arDisplay.showLabels, ds.arShowLabels.now())
        assertEquals(d.arDisplay.showOffscreenArrows, ds.arShowOffscreenArrows.now())
        assertEquals(d.arDisplay.modelScale.toString(), ds.arModelScale.now())
        assertEquals(d.arDisplay.showArDebugTools, ds.arDebugToolsEnabled.now())
        assertEquals(d.gnssReceiver.highAccuracy, ds.highAccuracy.now())
        assertEquals(d.gnssReceiver.internalAntennaHeightM, ds.internalAntennaHeightM.now(), 0.0)
        assertEquals(d.developer.developerToolsEnabled, ds.developerToolsEnabled.now())
        assertEquals(d.mockLocationEnabled, ds.mockLocationEnabled.now())
        assertEquals("SELECTED", ds.arVisibilityMode.now())
        assertFalse(ds.arVisibilityCustomized.now())
        assertTrue(ds.arVisibleIds.now().isEmpty())
        assertEquals(d.stakeout.enableHaptics, ds.stakeoutEnableHaptics.now())
        assertEquals(d.stakeout.enableAudio, ds.stakeoutEnableAudio.now())
        assertEquals(d.stakeout.keepScreenOnDuringStakeout, ds.stakeoutKeepScreenOn.now())
        assertEquals(d.stakeout.guidanceUsesCompassHeading, ds.stakeoutCompassHeading.now())
        assertEquals(d.map.showMyLocationByDefault, ds.mapShowMyLocationDefault.now())
        assertEquals(d.map.keepMapToolsOpenByDefault, ds.mapToolsOpenDefault.now())
        assertEquals(d.map.mapPointsDrawerExpandedByDefault, ds.mapDrawerExpandedDefault.now())
        assertEquals(d.appearance.themeMode.prefKey, ds.appThemeModeRaw.now())
        assertEquals(d.appearance.showLiveGnssStatusBar, ds.showLiveGnssStatusBar.now())
        assertEquals(d.appearance.keepScreenAwake, ds.keepScreenAwake.now())
        assertEquals(d.appearance.maxBrightnessWhileOpen, ds.maxBrightnessWhileOpen.now())

        val antenna = ds.externalAntennaRaw.now()
        assertEquals(ExternalAntennaRaw(null, null, null, null, null, false), antenna)
    }

    @Test fun `source and external TCP setters round trip and clear`() = runBlocking {
        ds.setLocationSourceString("external")
        ds.setExternalConnTypeString("tcp")
        ds.setExternalTcp("192.168.42.1", 9001)
        ds.setExternalTcpName("reach-rs2")
        ds.setExternalReceiverProfile("emlid_rs2")
        ds.setSchemaVersion(7)

        assertEquals("external", ds.locationSourceRaw.first())
        assertEquals("tcp", ds.externalConnTypeRaw.first())
        assertEquals("192.168.42.1", ds.externalTcpHost.first())
        assertEquals(9001, ds.externalTcpPort.first())
        assertEquals("reach-rs2", ds.externalTcpName.first())
        assertEquals("emlid_rs2", ds.externalReceiverProfileRaw.first())
        assertEquals(7, ds.schemaVersion.first())

        ds.clearExternalTcp()
        assertNull(ds.externalTcpHost.first())
        assertNull(ds.externalTcpPort.first())
        assertNull(ds.externalTcpName.first())
        // Clearing TCP leaves the other source keys alone.
        assertEquals("external", ds.locationSourceRaw.first())
        assertEquals("emlid_rs2", ds.externalReceiverProfileRaw.first())
    }

    @Test fun `coordinate display and coordinate system setters round trip`() = runBlocking {
        ds.setDefaultCoordinateNamePrefix("PT-")
        ds.setAutoIncrementCoordinateNames(!SettingsDefaults.coordinateDisplay.autoIncrementNames)
        ds.setShowAccuracyIndicators(!SettingsDefaults.coordinateDisplay.showAccuracyIndicators)
        ds.setCoordinateSystem("spcs", "nad83_2011", "us_ft", "navd88", "nad83")

        assertEquals("PT-", ds.defaultCoordinateNamePrefix.first())
        assertEquals(!SettingsDefaults.coordinateDisplay.autoIncrementNames, ds.autoIncrementCoordinateNames.first())
        assertEquals(!SettingsDefaults.coordinateDisplay.showAccuracyIndicators, ds.showAccuracyIndicators.first())
        assertEquals("spcs", ds.coordHorizontalSystem.first())
        assertEquals("nad83_2011", ds.coordOutputDatum.first())
        assertEquals("us_ft", ds.coordLinearUnit.first())
        assertEquals("navd88", ds.coordVerticalReference.first())
        assertEquals("nad83", ds.coordExternalRxDatum.first())
        // Phase 3 beta preview: absent = OFF; the 5-argument write leaves it alone; it round-trips.
        assertEquals(false, ds.coordNsrs2022Preview.first())
        ds.setCoordinateSystem("geographic", "wgs84", "m", "geoid2022_beta", "itrf2014", nsrs2022Preview = true)
        assertEquals(true, ds.coordNsrs2022Preview.first())
        assertEquals("geoid2022_beta", ds.coordVerticalReference.first())
        ds.setCoordinateSystem("geographic", "wgs84", "m", "geoid2022_beta", "itrf2014")
        assertEquals(true, ds.coordNsrs2022Preview.first())
        ds.setCoordinateSystem("geographic", "wgs84", "m", "geoid2022_beta", "itrf2014", nsrs2022Preview = false)
        assertEquals(false, ds.coordNsrs2022Preview.first())
        assertEquals("geoid2022_beta", ds.coordVerticalReference.first())   // turning the preview off keeps the stored choice
        // Phase 4 WP X: SPCS2022 zone key, absent = null; untouched by writes that do not ask for it; null removes it.
        assertEquals(null, ds.coordSpcs2022Zone.first())
        ds.setCoordinateSystem("spcs2022_beta", "wgs84", "m", "receiver_msl", "itrf2014", nsrs2022Preview = true,
            spcs2022Zone = "spcs2022:421001", writeSpcs2022Zone = true)
        assertEquals("spcs2022_beta", ds.coordHorizontalSystem.first())
        assertEquals("spcs2022:421001", ds.coordSpcs2022Zone.first())
        ds.setCoordinateSystem("geographic", "natrf2022_beta", "m", "receiver_msl", "itrf2014")
        assertEquals("spcs2022:421001", ds.coordSpcs2022Zone.first())
        assertEquals("natrf2022_beta", ds.coordOutputDatum.first())
        ds.setCoordinateSystem("geographic", "wgs84", "m", "receiver_msl", "itrf2014", spcs2022Zone = null, writeSpcs2022Zone = true)
        assertEquals(null, ds.coordSpcs2022Zone.first())
    }

    @Test fun `gnss capture setters round trip`() = runBlocking {
        ds.setGnssCaptureRtkStatus("float")
        ds.setGnssCaptureMinDurationSec(11)
        ds.setGnssCaptureMaxDurationSec(222)
        ds.setGnssCaptureMinSamples(33)
        ds.setGnssCaptureMaxFixAgeSec(4)
        ds.setGnssCaptureMaxDiffAgeSec(55)
        ds.setGnssCapturePreset("custom")

        assertEquals("float", ds.gnssCaptureRtkStatus.first())
        assertEquals(11, ds.gnssCaptureMinDurationSec.first())
        assertEquals(222, ds.gnssCaptureMaxDurationSec.first())
        assertEquals(33, ds.gnssCaptureMinSamples.first())
        assertEquals(4, ds.gnssCaptureMaxFixAgeSec.first())
        assertEquals(55, ds.gnssCaptureMaxDiffAgeSec.first())
        assertEquals("custom", ds.gnssCapturePreset.first())
    }

    @Test fun `precision target is stored only when positive and removed otherwise`() = runBlocking {
        ds.setGnssCapturePrecisionTargetM(0.02)
        assertEquals(0.02, ds.gnssCapturePrecisionTargetM.first()!!, 0.0)

        ds.setGnssCapturePrecisionTargetM(0.0)
        assertNull(ds.gnssCapturePrecisionTargetM.first())
        assertNull(dataStore.data.first()[SettingsKeys.GNSS_CAPTURE_PRECISION_TARGET_M])

        ds.setGnssCapturePrecisionTargetM(0.05)
        ds.setGnssCapturePrecisionTargetM(null)
        assertNull(dataStore.data.first()[SettingsKeys.GNSS_CAPTURE_PRECISION_TARGET_M])

        ds.setGnssCapturePrecisionTargetM(-1.0)
        assertNull(dataStore.data.first()[SettingsKeys.GNSS_CAPTURE_PRECISION_TARGET_M])
    }

    @Test fun `a non-positive precision target written directly reads back as off`() = runBlocking {
        dataStore.edit { it[SettingsKeys.GNSS_CAPTURE_PRECISION_TARGET_M] = -0.5 }
        assertNull(ds.gnssCapturePrecisionTargetM.first())
    }

    @Test fun `AR display and visibility setters round trip`() = runBlocking {
        ds.setArAltitudeMode("TERRAIN")
        ds.setArDistanceFilterIndex(3)
        ds.setArShowDebugOverlay(true)
        ds.setArShowLabels(false)
        ds.setArShowOffscreenArrows(false)
        ds.setArModelScale("2.5")
        ds.setArDebugToolsEnabled(true)
        ds.setArVisibilityMode("ALL")
        ds.setArVisibilityCustomized(true)
        ds.setArVisibleIds(setOf("a", "b"))

        assertEquals("TERRAIN", ds.arAltitudeMode.first())
        assertEquals(3, ds.arDistanceFilterIndex.first())
        assertTrue(ds.arShowDebugOverlay.first())
        assertFalse(ds.arShowLabels.first())
        assertFalse(ds.arShowOffscreenArrows.first())
        assertEquals("2.5", ds.arModelScale.first())
        assertTrue(ds.arDebugToolsEnabled.first())
        assertEquals("ALL", ds.arVisibilityMode.first())
        assertTrue(ds.arVisibilityCustomized.first())
        assertEquals(setOf("a", "b"), ds.arVisibleIds.first())
    }

    @Test fun `receiver developer and mock setters round trip`() = runBlocking {
        ds.setHighAccuracy(!SettingsDefaults.gnssReceiver.highAccuracy)
        ds.setInternalAntennaHeightM(1.234)
        ds.setDeveloperToolsEnabled(true)
        ds.setMockLocationEnabled(true)

        assertEquals(!SettingsDefaults.gnssReceiver.highAccuracy, ds.highAccuracy.first())
        assertEquals(1.234, ds.internalAntennaHeightM.first(), 0.0)
        assertTrue(ds.developerToolsEnabled.first())
        assertTrue(ds.mockLocationEnabled.first())
    }

    @Test fun `external antenna raw reads every stored key from one snapshot including legacy`() = runBlocking {
        ds.editAll {
            it[SettingsKeys.POLE_HEIGHT_ARP_M] = 2.0
            it[SettingsKeys.ANTENNA_HEIGHT_M] = 2.134
            it[SettingsKeys.CAMERA_BELOW_RECEIVER_M] = 0.4
            it[SettingsKeys.AR_ANTENNA_ABOVE_CAMERA_M] = "0.35"
            it[SettingsKeys.PHASE_CENTER_OFFSET_OVERRIDE_M] = 0.134
            it[SettingsKeys.POLE_HEIGHT_CHECKED] = true
        }
        assertEquals(ExternalAntennaRaw(2.0, 2.134, 0.4, "0.35", 0.134, true), ds.externalAntennaRaw.first())
    }

    @Test fun `appearance setters round trip`() = runBlocking {
        ds.setAppThemeModeString("dark")
        ds.setShowLiveGnssStatusBar(!SettingsDefaults.appearance.showLiveGnssStatusBar)
        ds.setKeepScreenAwake(!SettingsDefaults.appearance.keepScreenAwake)
        ds.setMaxBrightnessWhileOpen(!SettingsDefaults.appearance.maxBrightnessWhileOpen)

        assertEquals("dark", ds.appThemeModeRaw.first())
        assertEquals(!SettingsDefaults.appearance.showLiveGnssStatusBar, ds.showLiveGnssStatusBar.first())
        assertEquals(!SettingsDefaults.appearance.keepScreenAwake, ds.keepScreenAwake.first())
        assertEquals(!SettingsDefaults.appearance.maxBrightnessWhileOpen, ds.maxBrightnessWhileOpen.first())
    }

    @Test fun `stakeout setters store raw numeric values unsanitized`() = runBlocking {
        ds.setStakeoutToleranceM(-3.0)
        ds.setStakeoutWarningAccuracyM(Double.NaN)
        ds.setStakeoutEnableHaptics(!SettingsDefaults.stakeout.enableHaptics)
        ds.setStakeoutEnableAudio(!SettingsDefaults.stakeout.enableAudio)
        ds.setStakeoutKeepScreenOn(!SettingsDefaults.stakeout.keepScreenOnDuringStakeout)
        ds.setStakeoutCompassHeading(!SettingsDefaults.stakeout.guidanceUsesCompassHeading)

        assertEquals(-3.0, ds.stakeoutToleranceMRaw.first()!!, 0.0)
        assertTrue(ds.stakeoutWarningAccuracyMRaw.first()!!.isNaN())
        assertEquals(!SettingsDefaults.stakeout.enableHaptics, ds.stakeoutEnableHaptics.first())
        assertEquals(!SettingsDefaults.stakeout.enableAudio, ds.stakeoutEnableAudio.first())
        assertEquals(!SettingsDefaults.stakeout.keepScreenOnDuringStakeout, ds.stakeoutKeepScreenOn.first())
        assertEquals(!SettingsDefaults.stakeout.guidanceUsesCompassHeading, ds.stakeoutCompassHeading.first())
    }

    @Test fun `map default setters round trip`() = runBlocking {
        ds.setMapDefaultType("hybrid")
        ds.setMapDefaultGridMode("utm")
        ds.setMapDefaultLabelMode("name")
        ds.setMapShowMyLocationDefault(!SettingsDefaults.map.showMyLocationByDefault)
        ds.setMapToolsOpenDefault(!SettingsDefaults.map.keepMapToolsOpenByDefault)
        ds.setMapDrawerExpandedDefault(!SettingsDefaults.map.mapPointsDrawerExpandedByDefault)

        assertEquals("hybrid", ds.mapDefaultTypeRaw.first())
        assertEquals("utm", ds.mapDefaultGridModeRaw.first())
        assertEquals("name", ds.mapDefaultLabelModeRaw.first())
        assertEquals(!SettingsDefaults.map.showMyLocationByDefault, ds.mapShowMyLocationDefault.first())
        assertEquals(!SettingsDefaults.map.keepMapToolsOpenByDefault, ds.mapToolsOpenDefault.first())
        assertEquals(!SettingsDefaults.map.mapPointsDrawerExpandedByDefault, ds.mapDrawerExpandedDefault.first())
    }

    @Test fun `unknown legacy tokens are passed through raw for the repository to resolve`() = runBlocking {
        dataStore.edit {
            it[SettingsKeys.LOCATION_SOURCE] = "RS2_TCP"
            it[SettingsKeys.APP_THEME_MODE] = "neon"
            it[SettingsKeys.GNSS_CAPTURE_RTK_STATUS] = "bogus"
        }
        assertEquals("RS2_TCP", ds.locationSourceRaw.first())
        assertEquals("neon", ds.appThemeModeRaw.first())
        assertEquals("bogus", ds.gnssCaptureRtkStatus.first())
    }
}
